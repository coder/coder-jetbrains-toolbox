package com.coder.toolbox.oauth

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi

/**
 * A successful token response per RFC 6749.
 */
@JsonClass(generateAdapter = true)
data class OAuthTokenResponse(
    @field:Json(name = "access_token") val accessToken: String,
    @field:Json(name = "token_type") val tokenType: String,
    @field:Json(name = "expires_in") val expiresIn: Long?,
    @field:Json(name = "refresh_token") val refreshToken: String?,
    @field:Json(name = "scope") val scope: String?
)


/**
 * RFC 6749 §5.2 — Token Endpoint Error Response.
 *
 * Returned as a JSON body with HTTP 400 (or 401 for invalid_client).
 */
@JsonClass(generateAdapter = true)
data class OAuthTokenErrorResponse(
    @field:Json(name = "error") val error: String,
    @field:Json(name = "error_description") val errorDescription: String? = null,
    @field:Json(name = "error_uri") val errorUri: String? = null,
) {
    fun toMessage(): String = buildString {
        append(error)
        if (!errorDescription.isNullOrBlank()) append(": $errorDescription")
        if (!errorUri.isNullOrBlank()) append(" (see $errorUri)")
    }

    companion object {
        private val adapter = Moshi.Builder().build().adapter(OAuthTokenErrorResponse::class.java)

        fun fromJson(json: String): OAuthTokenErrorResponse? = try {
            adapter.fromJson(json)
        } catch (_: Exception) {
            null
        }
    }
}