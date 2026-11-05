package com.hexnext.themer.shizuku

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** Bounds exceed measured 9–14 MB resource tables; oversize output fails instead of being parsed. */
object ProcessOutputPolicy {
    const val MAX_CHARS = 32 * 1024 * 1024
    const val MAX_UTF8_BYTES = 64 * 1024 * 1024

    fun isSpillPath(path: String, stateDirectory: String): Boolean {
        if (!path.startsWith("$stateDirectory/exec/")) return false
        return Regex("out-[0-9]+-[0-9]+-[0-9]+\\.txt").matches(path.substringAfterLast('/')) &&
            path == "$stateDirectory/exec/${path.substringAfterLast('/')}"
    }

    fun readUtf8(input: InputStream, maxBytes: Int = MAX_UTF8_BYTES): String {
        require(maxBytes > 0)
        val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
        val chunk = ByteArray(8192)
        while (true) {
            val count = input.read(chunk)
            if (count < 0) break
            if (count > maxBytes - output.size()) throw IOException("Command output exceeds the safety limit")
            output.write(chunk, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    class Capture(private val limit: Int = MAX_CHARS) {
        init { require(limit > 0) }
        private val text = StringBuilder()
        private var overflow = false

        @Synchronized
        fun append(chunk: CharArray, count: Int): Boolean {
            require(count in 0..chunk.size)
            val accepted = minOf(count, limit - text.length)
            text.append(chunk, 0, accepted)
            if (accepted != count) overflow = true
            return !overflow
        }

        @Synchronized fun exceeded(): Boolean = overflow
        @Synchronized fun snapshot(): String = text.toString()
    }
}
