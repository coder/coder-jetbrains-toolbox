package com.coder.toolbox.oauth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

private const val CODE_VERIFIER_LENGTH = 128

/**
 * Generates OAuth2 PKCE code verifier and code challenge
 */
object PKCEGenerator {

    /**
     * Generates a cryptographically random code verifier 128 chars in size
     * @return Base64 URL-encoded code verifier
     */
    fun generateCodeVerifier(): String {
        val secureRandom = SecureRandom()
        val bytes = ByteArray(CODE_VERIFIER_LENGTH)
        secureRandom.nextBytes(bytes)

        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(bytes)
            .take(CODE_VERIFIER_LENGTH)
    }

    /**
     * Generates code challenge from code verifier using S256 method
     * @param codeVerifier The code verifier string
     * @return Base64 URL-encoded SHA-256 hash of the code verifier
     */
    fun generateCodeChallenge(codeVerifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(codeVerifier.toByteArray(Charsets.US_ASCII))

        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(hash)
    }
}