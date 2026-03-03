package com.coder.toolbox.util

import java.io.BufferedInputStream
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest

private const val BUFFER_SIZE = 8192

fun ByteArray.toHex() = joinToString(separator = "") { byte -> "%02x".format(byte) }

/**
 * Return the SHA-1 for the provided stream.
 */
fun sha1(stream: InputStream): String {
    val md = MessageDigest.getInstance("SHA-1")
    DigestInputStream(BufferedInputStream(stream), md).use { dis ->
        val buffer = ByteArray(BUFFER_SIZE)
        while (dis.read(buffer) != -1) {
            // Read until EOF
        }
    }
    return md.digest().toHex()
}
