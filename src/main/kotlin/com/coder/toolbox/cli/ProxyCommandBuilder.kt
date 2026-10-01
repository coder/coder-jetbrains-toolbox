package com.coder.toolbox.cli

import com.coder.toolbox.util.OS
import com.coder.toolbox.util.getOS

/** Renders literal argv and deliberate SSH expansion tokens into one ProxyCommand value. */
internal class ProxyCommandBuilder(
    private val os: OS? = getOS(),
) {
    private sealed interface Part {
        data class Literal(val value: String) : Part
        data class SshToken(val value: String) : Part
    }

    private val parts = mutableListOf<Part>()

    fun argument(value: String): ProxyCommandBuilder = apply {
        parts.add(Part.Literal(value))
    }

    fun arguments(values: Iterable<String>): ProxyCommandBuilder = apply {
        values.forEach(::argument)
    }

    fun sshToken(value: String): ProxyCommandBuilder = apply {
        require(value == "%h") { "Only the SSH host expansion token is supported" }
        parts.add(Part.SshToken(value))
    }

    fun render(): String = parts.joinToString(" ") { part ->
        when (part) {
            is Part.Literal -> quoteLiteral(part.value)
            is Part.SshToken -> part.value
        }
    }

    private fun quoteLiteral(value: String): String {
        require(!value.containsAny('\r', '\n', '\u0000')) {
            "ProxyCommand arguments cannot contain carriage returns, newlines, or NUL characters"
        }

        // OpenSSH expands percent tokens before starting the command, even when they appear in shell quotes.
        val escapedSshTokens = value.replace("%", "%%")
        return if (os == OS.WINDOWS) {
            quoteWindowsArgument(escapedSshTokens)
        } else {
            quotePosixArgument(escapedSshTokens)
        }
    }

    private fun quotePosixArgument(value: String): String {
        if (value.isNotEmpty() && POSIX_SAFE_ARGUMENT.matches(value)) return value
        return "'" + value.replace("'", "'\\''") + "'"
    }

    /** Quotes one argument using the Windows C command-line parsing rules. */
    private fun quoteWindowsArgument(value: String): String {
        if (value.isNotEmpty() && WINDOWS_SAFE_ARGUMENT.matches(value)) return value

        return buildString {
            append('"')
            var backslashes = 0
            value.forEach { character ->
                when (character) {
                    '\\' -> backslashes++
                    '"' -> {
                        repeat(backslashes * 2 + 1) { append('\\') }
                        append('"')
                        backslashes = 0
                    }

                    else -> {
                        repeat(backslashes) { append('\\') }
                        append(character)
                        backslashes = 0
                    }
                }
            }
            repeat(backslashes * 2) { append('\\') }
            append('"')
        }
    }

    private fun String.containsAny(vararg characters: Char): Boolean = characters.any(::contains)

    companion object {
        private val POSIX_SAFE_ARGUMENT = Regex("[A-Za-z0-9_@%+=:,./-]+")
        private val WINDOWS_SAFE_ARGUMENT = Regex("[A-Za-z0-9_@+=:,./\\\\-]+")
    }
}
