package io.bazel.kotlin.integration

import java.io.*
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Files.exists
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.attribute.FileTime
import java.time.Instant

object WriteWorkspace {
  fun using(prefix: String, contents: Workspace.() -> Unit): Path {
    return Files.createTempDirectory(prefix).also { root ->
      CreateWorkspace(root).use(contents)
    }
  }

  inline fun <reified CLASS> using(noinline contents: Workspace.() -> Unit): Path = using(
    CLASS::class.run { qualifiedName ?: simpleName ?: error("Cannot use unnamed class") }, contents)

  /**
   * [open] the [root] of an existing [Workspace] and apply [contents]
   *
   * An opened workspace only allows modification of non-workspace files.
   */
  fun open(root: Path, contents: Package.() -> Unit) {
    ModifyWorkspace(root).use(contents)
  }

  @DslMarker
  annotation class WorkspaceContext

  interface Text : Appendable {

    operator fun CharSequence.unaryPlus(): CharSequence = apply {
      line(toString())
    }

    fun line(contents: CharSequence) {
      append(contents).appendLine()
    }

    fun indent(contents: Text.() -> Unit)
  }

  class Indenting(out: Appendable) : Text, Appendable by out {
    private var indent = 0
    override fun indent(contents: Text.() -> Unit) {
      indent++
      apply(contents)
      indent--
    }

    override fun line(contents: CharSequence) {
      append("  ".repeat(indent)).append(contents).appendLine()
    }
  }

  @WorkspaceContext
  interface Paths {
    fun new(path: String, contents: Text.() -> Unit): Path {
      val text = StringBuilder().apply {
        Indenting(this).apply(contents)
      }
      return new(path, text.toString().toByteArray(StandardCharsets.UTF_8))
    }

    fun new(path: String, contents: ByteArray): Path {
      return write(path, contents, CREATE_NEW)
    }

    fun write(path: String, contents: ByteArray, vararg options: StandardOpenOption): Path {
      return Files.write(resolve(path), contents, *options).also {
        Files.setLastModifiedTime(it, FileTime.from(Instant.EPOCH))
      }
    }

    fun resolve(path: String): Path
  }


  private class Rendered(private val value: CharSequence) : CharSequence by value {
    override fun toString(): String = value.toString()
  }

  interface Variables : Text {

    fun Any?.render(): CharSequence = when (this) {
      null -> "null"
      is Rendered -> this
      is String -> Rendered("\"$this\"")
      else -> Rendered(toString())
    }

    fun Array<out CharSequence>.render(
      separator: String = " ",
      prefix: String = "",
      postfix: String = ""
    ): CharSequence = Rendered(joinToString(separator, prefix, postfix) { it.render() })

    operator fun CharSequence.not(): CharSequence = Rendered(this)

    infix fun CharSequence.eq(value: Any) {
      +"$this = ${value.render()}"
    }
  }

  interface Block<T : Block<T>> : Text {
    fun block(label: CharSequence, contents: Block<T>.() -> Unit) {
      +label
      indent {
        apply(contents)
      }
    }

    operator fun CharSequence.invoke(contents: Block<T>.() -> Unit) {
      block(this, contents)
    }
  }

  interface Define<T : Define<T>> {
    fun define(name: CharSequence, vararg arguments: CharSequence, contents: Define<T>.() -> Unit)

    operator fun CharSequence.invoke(
      argument: CharSequence,
      vararg arguments: CharSequence,
      contents: Define<T>.() -> Unit) {
      define(this, *(arrayOf(argument) + arguments), contents = contents)
    }
  }

  interface Invoke : Variables {
    fun call(name: CharSequence) {
      +"$name()"
    }

    fun call(name: CharSequence, vararg arguments: CharSequence) {
      +"$name(${arguments.render(", ")})"
    }

    fun call(name: CharSequence, vararg arguments: Pair<CharSequence, CharSequence>) {
      +"$name("
      indent {
        arguments
          .map { (name, value) -> "$name = ${value.render()}" }
          .forEach { argument ->
            +"$argument,"
          }
      }
      +")"
    }

    operator fun CharSequence.invoke() {
      call(toString())
    }

    operator fun CharSequence.invoke(vararg arguments: CharSequence) {
      call(toString(), *arguments)
    }

    operator fun CharSequence.invoke(vararg arguments: Pair<CharSequence, CharSequence>) {
      call(toString(), *arguments)
    }
  }

  @WorkspaceContext
  interface Starlark<T : Starlark<T>> : Block<T>, Variables, Invoke {

    @Suppress("PropertyName")
    val True:CharSequence get() = Rendered("True")

    @Suppress("PropertyName")
    val False:CharSequence get() = Rendered("False")

    override fun block(label: CharSequence, contents: Block<T>.() -> Unit) {
      super.block("$label:", contents)
    }

    fun load(pkg: CharSequence, vararg functions: CharSequence) : List<CharSequence> {
      "load"(pkg.toString(), functions.render(separator = ", "))
      return functions.map { !it }
    }

    fun tuple(vararg values: CharSequence): CharSequence {
      return Rendered(values.render(prefix = "(", separator = ",", postfix = ")"))
    }

    operator fun String.rem(value: CharSequence): CharSequence {
      return Rendered("${render()} % ${value.render()}")
    }
  }

  interface BzlWorkspace : Starlark<BzlWorkspace> {
    fun local_repository(name: CharSequence, location: Path) {
      "local_repository"(
        "name" to name,
        "path" to location.toString()
      )
    }
  }

  interface Bzl : Starlark<Bzl>, Define<Bzl> {

    override fun define(name: CharSequence, vararg arguments: CharSequence, contents: Define<Bzl>.() -> Unit) {
      block(arguments.render(prefix = "$name(", separator = ", ", postfix = ")")) {
        contents()
      }
    }

    fun rule(name: String, implementation: CharSequence, vararg attributes: Pair<CharSequence, CharSequence>) {
      +"$name = rule("
      indent {
        "implementation" eq "$implementation,"
        attributes.forEach { (name, value) ->
          name eq value
        }
      }
    }
  }

  interface BuildBazel : Starlark<BuildBazel>

  @WorkspaceContext
  interface Jvm<T : Jvm<T>> : Block<T>, Define<T>, Invoke {
    override fun block(label: CharSequence, contents: Block<T>.() -> Unit) {
      super.block("$label {") {
        contents()
      }
      +"}"
    }

    override fun define(
      name: CharSequence,
      vararg arguments: CharSequence,
      contents: Define<T>.() -> Unit) {
      block(arguments.render(separator = ", ", prefix = "$name(", postfix = ")")) {
        contents()
      }
    }
  }

  interface KotlinSource : Variables, Jvm<KotlinSource>

  interface Resolve {
    fun target(name: String): String
  }

  interface Package : Paths, Closeable {

    fun build(contents: BuildBazel.() -> Unit) {
      new("BUILD.bazel") {
        object : BuildBazel, Text by this {}.apply(contents)
      }
    }

    fun kotlin(path: String, contents: KotlinSource.() -> Unit) {
      new(path) {
        object : KotlinSource, Text by this {}.apply(contents)
      }
    }

    fun starlark(path: String, contents: Bzl.() -> Unit) {
      new(path) {
        object : Bzl, Text by this {}.apply(contents)
      }
    }

    fun pkg(name: String, contents: Package.() -> Unit): Resolve

    operator fun String.invoke(contents: Package.() -> Unit): Resolve {
      return pkg(this, contents)
    }

    override fun close() {
      if (!exists(resolve("BUILD.bazel"))) {
        build { +"# default package marker" }
      }
    }
  }


  interface Workspace : Paths, Closeable, Package {
    fun workspace(contents: BzlWorkspace.() -> Unit) {
      new("WORKSPACE") {
        object : BzlWorkspace, Text by this {}.apply(contents)
      }
    }

    override fun close() {
      if (!exists(resolve("WORKSPACE"))) {
        workspace {
          +"# Workspace Marker"
        }
      }
      super<Package>.close()
    }
  }

  private interface Structure : Paths {
    val workspace: Path
    val root: Path
    fun child(path: String): Structure

    override fun resolve(path: String): Path {
      return root.resolve(path).toAbsolutePath().also { newPath ->
        check(newPath == root || newPath.startsWith(root)) {
          "$path is invalid. Only paths under the $root are allowed."
        }
        Files.createDirectories(newPath.parent)
      }
    }
  }

  private class WriteStructure(override val workspace: Path, override val root: Path) : Structure {
    override fun child(path: String): Structure {
      return WriteStructure(workspace, resolve(path))
    }
  }

  private class ReplaceStructure(override val workspace: Path, override val root: Path) : Structure {
    override fun write(path: String, contents: ByteArray, vararg options: StandardOpenOption): Path {
      Files.deleteIfExists(resolve(path))
      return super.write(path, contents, *options)
    }

    override fun child(path: String): Structure {
      return ReplaceStructure(workspace, resolve(path))
    }
  }

  private interface WritePackage : Package, Structure, Resolve {
    override fun target(name: String): String {
      return "\"//${workspace.relativize(root)}:$name\""
    }

    override fun pkg(name: String, contents: Package.() -> Unit): Resolve {
      return object : WritePackage, Structure by child(name) {}.apply { use(contents) }
    }
  }

  private class CreateWorkspace(
    root: Path,
    write: WriteStructure = WriteStructure(root, root)
  ) : Workspace, WritePackage, Structure by write {
    override fun close() {
      super<WritePackage>.close()
      super<Workspace>.close()
    }
  }

  private class ModifyWorkspace(
    root: Path,
    val replace: ReplaceStructure = ReplaceStructure(root, root)
  ) : WritePackage, Structure by replace {
    override fun resolve(path: String): Path {
      check(!path.endsWith("WORKSPACE")) {
        "cannot modify the WORKSPACE file."
      }
      return replace.resolve(path)
    }
  }
}
