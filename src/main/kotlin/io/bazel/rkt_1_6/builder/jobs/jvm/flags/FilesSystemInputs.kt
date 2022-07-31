package io.bazel.rkt_1_6.builder.jobs.jvm.flags

import io.bazel.kotlin.builder.utils.Arguments
import java.nio.file.FileSystem
import java.nio.file.Path

interface FilesSystemInputs {
  val fileSystem : FileSystem

  fun Arguments.path(name: String, description: String, required: Boolean = false) =
    flag<Path>(name, description, required = required) {
      fileSystem.getPath(toString())
    }

  fun Arguments.path(name: String, description: String, default:String) =
    flag(name, description, required = true, default = fileSystem.getPath(default)) {
      fileSystem.getPath(toString())
    }

  fun Arguments.paths(name: String, description: String, required: Boolean = false) =
    flag<List<Path>>(
      name,
      description,
      emptyList(),
      required
    ) {
      split(",").map { fileSystem.getPath(it) }
    }
}