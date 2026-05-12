package com.luxury.mobile.launcher.core

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * SHA-256 helpers used by [FileVerifier] to detect already-installed files and
 * to validate freshly downloaded ones.
 *
 * The streamed implementation is deliberately small — we read with an 8 KB
 * buffer and update the digest incrementally so hashing a multi-gigabyte
 * `luxury.zip` never spikes memory.
 */
object Hasher {

    fun sha256(file: File, onProgress: ((Long) -> Unit)? = null): String {
        if (!file.isFile) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(8 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
                total += read
                onProgress?.invoke(total)
            }
        }
        return digest.digest().toHexString()
    }

    private fun ByteArray.toHexString(): String {
        val hex = CharArray(size * 2)
        val chars = "0123456789abcdef".toCharArray()
        for (i in indices) {
            val v = this[i].toInt() and 0xFF
            hex[i * 2] = chars[v ushr 4]
            hex[i * 2 + 1] = chars[v and 0x0F]
        }
        return String(hex)
    }
}
