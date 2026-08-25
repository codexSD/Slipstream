package com.slipstream.core

import java.io.File

/**
 * Resolves protocol/vectors/ from the test working directory. These fixtures are the
 * only thing keeping this implementation and the C# one on the same protocol, so
 * failing to find them must be loud, not a silently skipped test.
 */
object Vectors {
    val root: File by lazy {
        generateSequence(File(System.getProperty("user.dir")!!)) { it.parentFile }
            .firstOrNull { File(it, "protocol/vectors").isDirectory }
            ?.let { File(it, "protocol/vectors") }
            ?: error("Could not locate protocol/vectors from ${System.getProperty("user.dir")}")
    }

    fun read(name: String): String = File(root, name).readText()
}
