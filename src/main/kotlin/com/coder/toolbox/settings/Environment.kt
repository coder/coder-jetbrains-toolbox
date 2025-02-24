package com.coder.toolbox.settings

/**
 * Environment provides a way to override values in the actual environment.
 * Exists only so we can override the environment in tests.
 */
class Environment(private val env: Map<String, String> = emptyMap()) {
    fun get(name: String): String = env[name] ?: System.getenv(name) ?: ""
}
