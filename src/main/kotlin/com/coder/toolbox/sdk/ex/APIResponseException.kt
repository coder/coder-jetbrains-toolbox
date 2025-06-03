package com.coder.toolbox.sdk.ex

import com.coder.toolbox.sdk.v2.models.ApiErrorResponse
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class APIResponseException(action: String, url: URL, code: Int, errorResponse: ApiErrorResponse?) :
    IOException(formatToPretty(action, url, code, errorResponse)) {

    val reason = errorResponse?.detail
    val isUnauthorized = HttpURLConnection.HTTP_UNAUTHORIZED == code
    val isTokenExpired = isUnauthorized && reason?.contains("API key expired") == true

    companion object {
        private fun formatToPretty(
            action: String,
            url: URL,
            code: Int,
            errorResponse: ApiErrorResponse?,
        ): String {
            return if (errorResponse == null) {
                "Unable to $action: url=$url, code=$code, details=${HttpErrorStatusMapper.getMessage(code)}"
            } else {
                var msg = "Unable to $action: url=$url, code=$code, message=${errorResponse.message}"
                if (errorResponse.detail?.isNotEmpty() == true) {
                    msg += ", reason=${errorResponse.detail}"
                }

                // Be careful with the length because if you try to show a
                // notification in Toolbox that is too large it crashes the
                // application.
                if (msg.length > 500) {
                    msg = "${msg.substring(0, 500)}…"
                }
                msg
            }
        }
    }
}

private object HttpErrorStatusMapper {
    private val errorStatusMap = mapOf(
        // 4xx: Client Errors
        400 to "Bad Request",
        401 to "Unauthorized",
        402 to "Payment Required",
        403 to "Forbidden",
        404 to "Not Found",
        405 to "Method Not Allowed",
        406 to "Not Acceptable",
        407 to "Proxy Authentication Required",
        408 to "Request Timeout",
        409 to "Conflict",
        410 to "Gone",
        411 to "Length Required",
        412 to "Precondition Failed",
        413 to "Payload Too Large",
        414 to "URI Too Long",
        415 to "Unsupported Media Type",
        416 to "Range Not Satisfiable",
        417 to "Expectation Failed",
        418 to "I'm a teapot",
        421 to "Misdirected Request",
        422 to "Unprocessable Entity",
        423 to "Locked",
        424 to "Failed Dependency",
        425 to "Too Early",
        426 to "Upgrade Required",
        428 to "Precondition Required",
        429 to "Too Many Requests",
        431 to "Request Header Fields Too Large",
        451 to "Unavailable For Legal Reasons",

        // 5xx: Server Errors
        500 to "Internal Server Error",
        501 to "Not Implemented",
        502 to "Bad Gateway",
        503 to "Service Unavailable",
        504 to "Gateway Timeout",
        505 to "HTTP Version Not Supported",
        506 to "Variant Also Negotiates",
        507 to "Insufficient Storage",
        508 to "Loop Detected",
        510 to "Not Extended",
        511 to "Network Authentication Required"
    )

    fun getMessage(code: Int): String =
        errorStatusMap[code] ?: "Unknown Error Status"
}
