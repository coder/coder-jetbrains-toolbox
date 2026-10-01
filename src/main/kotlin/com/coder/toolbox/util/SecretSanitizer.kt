package com.coder.toolbox.util

private val sensitivePatterns = listOf(
    Regex("""(CODER_SESSION_TOKEN=)([^,\s}]+)"""),
    Regex("""(Coder-Session-Token:\s*)([^\s,]+)""", RegexOption.IGNORE_CASE),
    Regex("""(--token(?:\s+|=))(\S+)"""),
    Regex("""([?&]token=)([^&\s]+)""", RegexOption.IGNORE_CASE),
)

fun String.sanitizeSecrets(sessionToken: String? = null): String {
    val redacted = if (sessionToken.isNullOrEmpty()) this else replace(sessionToken, "<redacted>")
    return sensitivePatterns.fold(redacted) { acc, regex ->
        acc.replace(regex, "$1<redacted>")
    }
}
