package com.coder.toolbox.util

import java.net.IDN
import java.net.URI
import java.net.URL

fun String.toURL(): URL = URI.create(this).toURL()

fun URL.withPath(path: String): URL = URL(
    this.protocol,
    this.host,
    this.port,
    if (path.startsWith("/")) path else "/$path",
)

fun URL.withLastSegment(segment: String): URL {
    val uri = this.toURI()
    val basePath = uri.path.substringBeforeLast('/')
    val newPath = "$basePath/$segment"
    val newUri = URI(
        uri.scheme,
        uri.authority,
        newPath,
        uri.query,
        uri.fragment
    )
    return newUri.toURL()
}

/**
 * Return the host, converting IDN to ASCII in case the file system cannot
 * support the necessary character set.
 */
fun URL.safeHost(): String = IDN.toASCII(this.host, IDN.ALLOW_UNASSIGNED)

fun URI.toQueryParameters(): Map<String, String> = (this.query ?: "")
    .split("&").filter {
        it.isNotEmpty()
    }.associate {
        val parts = it.split("=", limit = 2)
        if (parts.size == 2) {
            parts[0] to parts[1]
        } else {
            parts[0] to ""
        }
    }
