package com.coder.toolbox.util

import com.coder.toolbox.util.WebUrlValidationResult.Invalid
import com.coder.toolbox.util.WebUrlValidationResult.Valid
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.IDN
import java.net.URI
import java.net.URL
import java.net.URLDecoder

fun String.toURL(): URL = URI.create(this).toURL()

/** Canonical HTTP(S) origin, without credentials or a path; default ports are omitted. */
fun URL.origin(): String? = toString().toHttpUrlOrNull()?.origin()

fun HttpUrl.origin(): String = newBuilder()
    .username("")
    .password("")
    .encodedPath("/")
    .query(null)
    .fragment(null)
    .build()
    .toString()
    .removeSuffix("/")

fun URL.hasSameOrigin(other: URL?): Boolean = origin()?.let { it == other?.origin() } ?: false

fun String.toBaseURL(): URL {
    val url = this.toURL()
    val port = if (url.port != -1) ":${url.port}" else ""
    return URI.create("${url.protocol}://${url.host}$port").toURL()
}

/** Validate the supplied text before URL normalization can discard unsafe components. */
fun String.validateStrictWebUrl(originOnly: Boolean = false): WebUrlValidationResult = try {
    val uri = URI(this)
    val address = toHttpUrlOrNull()
    when {
        address == null || uri.rawAuthority.isNullOrEmpty() || uri.isOpaque || address.port == 0 ->
            Invalid("The deployment URL must have a valid HTTP(S) host and port.")
        uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ->
            Invalid("The deployment URL cannot include credentials, a query, or a fragment.")
        !SAFE_DEPLOYMENT_URL.matches(this) || uri.rawPath.split('/').any { it == "." || it == ".." } ->
            Invalid("The deployment URL contains unsupported characters or path segments.")
        originOnly && uri.rawPath.isNotEmpty() && uri.rawPath != "/" ->
            Invalid("A workspace link must specify a deployment origin without a path.")
        else -> Valid
    }
} catch (_: java.net.URISyntaxException) {
    Invalid("The deployment URL must have a valid HTTP(S) host and port.")
}

private val SAFE_DEPLOYMENT_URL = Regex("""[\p{L}\p{N}._~:/\[\]+,=\-]+""")

fun URL.withPath(path: String): URL = URL(
    this.protocol,
    this.host,
    this.port,
    if (path.startsWith("/")) path else "/$path",
)

/**
 * Return the host, converting IDN to ASCII in case the file system cannot
 * support the necessary character set.
 */
fun URL.safeHost(): String = IDN.toASCII(this.host, IDN.ALLOW_UNASSIGNED)

fun URI.toQueryParameters(): Map<String, String> = buildMap {
    for (field in rawQuery.orEmpty().split('&')) {
        if (field.isEmpty()) continue
        val name = decodeQueryComponent(field.substringBefore('='))
        val value = decodeQueryComponent(field.substringAfter('=', ""))
        require(put(name, value) == null) { "A query parameter was supplied more than once." }
    }
}

private fun decodeQueryComponent(raw: String): String {
    val decoded = URLDecoder.decode(raw, Charsets.UTF_8)
    require('�' !in decoded) { "Invalid query parameter encoding." }
    return decoded
}

sealed class WebUrlValidationResult {
    object Valid : WebUrlValidationResult()
    data class Invalid(val reason: String) : WebUrlValidationResult()
}
