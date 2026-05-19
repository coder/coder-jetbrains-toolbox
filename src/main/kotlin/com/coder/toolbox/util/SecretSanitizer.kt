package com.coder.toolbox.util

private val sensitivePatterns = listOf(
    Regex("""(CODER_SESSION_TOKEN=)([^,\s}]+)"""),
    Regex("""(Coder-Session-Token:\s*)([^\s,]+)""", RegexOption.IGNORE_CASE),
    Regex("""(--token\s+)(\S+)"""),
    Regex("""([?&]token=)([^&\s]+)""", RegexOption.IGNORE_CASE),
)

fun String.sanitizeSecrets(): String {
    return sensitivePatterns.fold(this) { acc, regex ->
        acc.replace(regex, "$1<redacted>")
    }
}
