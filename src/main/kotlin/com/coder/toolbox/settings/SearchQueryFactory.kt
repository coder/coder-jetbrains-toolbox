package com.coder.toolbox.settings

import java.util.Locale.getDefault

object SearchQueryFactory {
    fun filterQuery(query: String?): String? =
        parse(query).asQuery()

    fun workspaceQuery(query: String?, scope: WorkspaceScope): String? {
        val terms = parse(query)
        val scoped = if (scope == WorkspaceScope.MY_WORKSPACES && terms.none { it.key == OWNER_KEY }) {
            listOf(WorkspaceSearchTerm(OWNER_KEY, CURRENT_USER)) + terms
        } else {
            terms
        }
        return scoped.asQuery()
    }
}

data class WorkspaceSearchTerm(
    val key: String,
    val value: String
) {
    fun format(): String = "$key:${quote(value)}"
}

fun String?.asWorkspaceFilterQuery(): String? =
    SearchQueryFactory.filterQuery(this)

fun String?.asWorkspaceSearchQuery(scope: WorkspaceScope): String? =
    SearchQueryFactory.workspaceQuery(this, scope)

private const val OWNER_KEY = "owner"
private const val NAME_KEY = "name"
private const val CURRENT_USER = "me"
private val supportedKeys = setOf(OWNER_KEY, "template", NAME_KEY, "status")

private fun List<WorkspaceSearchTerm>.asQuery(): String? =
    joinToString(" ") { it.format() }.takeIf { it.isNotBlank() }

/**
 * Parses a raw filter query into search terms. Recognized `key:value` pairs are kept as-is; any
 * bare words are merged into a single `name:` term. A key may take its value inline (`status:running`)
 * or from the following token (`status: running`), but never from another `key:` token.
 */
private fun parse(query: String?): List<WorkspaceSearchTerm> {
    val tokens = tokenize(query)
    val terms = mutableListOf<WorkspaceSearchTerm>()
    val bareWords = mutableListOf<String>()

    var i = 0
    while (i < tokens.size) {
        val token = tokens[i]
        val colon = token.indexOf(':')
        when {
            colon < 0 -> bareWords.add(token) // bare word -> part of the name search
            colon == 0 -> {} // leading colon (":bad") -> ignore
            else -> {
                val key = token.substring(0, colon).lowercase(getDefault())
                val value = token.substring(colon + 1).ifBlank {
                    tokens.getOrNull(i + 1)?.takeUnless { it.isKeyToken() }?.also { i++ } ?: ""
                }
                if (key in supportedKeys && value.isNotBlank()) {
                    terms.add(WorkspaceSearchTerm(key, value))
                }
            }
        }
        i++
    }

    bareWords.joinToString(" ").takeIf { it.isNotEmpty() }?.let {
        terms.add(WorkspaceSearchTerm(NAME_KEY, it))
    }
    return terms
}

/** Splits a query into whitespace-separated tokens, treating quoted spans as a single token. */
private fun tokenize(query: String?): List<String> {
    if (query.isNullOrBlank()) return emptyList()

    val tokens = mutableListOf<String>()
    val current = StringBuilder()
    var inQuotes = false

    fun flush() {
        current.toString().trim().takeIf { it.isNotEmpty() }?.let(tokens::add)
        current.clear()
    }

    for (char in query) {
        when {
            char == '"' -> inQuotes = !inQuotes
            char.isWhitespace() && !inQuotes -> flush()
            else -> current.append(char)
        }
    }
    flush()
    return tokens
}

private fun String.isKeyToken(): Boolean {
    val colon = indexOf(':')
    return colon > 0 && substring(0, colon).lowercase(getDefault()) in supportedKeys
}

private fun quote(value: String): String {
    val escaped = value.replace("\"", "\\\"")
    return if (escaped.any { it.isWhitespace() }) "\"$escaped\"" else escaped
}