package com.coder.toolbox.util

import com.coder.toolbox.util.WebUrlValidationResult.Invalid
import com.coder.toolbox.util.WebUrlValidationResult.Valid
import java.net.IDN
import java.net.URI
import java.net.URL

fun String.toURL(): URL = URI.create(this).toURL()

fun String.validateStrictWebUrl(): WebUrlValidationResult = try {
    val uri = URI(this)

    when {
        uri.isOpaque -> Invalid(
            "The URL \"$this\" is invalid because it is not in the standard format. " +
                    "Please enter a full web address like \"https://example.com\""
        )

        !uri.isAbsolute -> Invalid(
            "The URL \"$this\" is missing a scheme (like https://). " +
                    "Please enter a full web address like \"https://example.com\""
        )
        uri.scheme?.lowercase() !in setOf("http", "https") ->
            Invalid(
                "The URL \"$this\" must start with http:// or https://, not \"${uri.scheme}\""
            )
        uri.authority.isNullOrBlank() ->
            Invalid(
                "The URL \"$this\" does not include a valid website name. " +
                        "Please enter a full web address like \"https://example.com\""
            )
        else -> Valid
    }
} catch (_: Exception) {
    Invalid(
        "The input \"$this\" is not a valid web address. " +
                "Please enter a full web address like \"https://example.com\""
    )
}

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

sealed class WebUrlValidationResult {
    object Valid : WebUrlValidationResult()
    data class Invalid(val reason: String) : WebUrlValidationResult()
}