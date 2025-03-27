package com.coder.toolbox.settings

/**
 * Describes where a setting came from.
 */
enum class SettingSource {
    CONFIG,            // Pulled from the global Coder CLI config.
    DEPLOYMENT_CONFIG, // Pulled from the config for a deployment.
    ENVIRONMENT,       // Pulled from environment variables.
    LAST_USED,         // Last used token.
    QUERY,             // From the Gateway link as a query parameter.
    SETTINGS,          // Pulled from settings.
    USER,              // Input by the user.
    ;

    /**
     * Return a description of the source.
     */
    fun description(name: String): String = when (this) {
        CONFIG -> "This $name was pulled from your global CLI config."
        DEPLOYMENT_CONFIG -> "This $name was pulled from your deployment's CLI config."
        LAST_USED -> "This was the last used $name."
        QUERY -> "This $name was pulled from the Gateway link."
        USER -> "This was the last used $name."
        ENVIRONMENT -> "This $name was pulled from an environment variable."
        SETTINGS -> "This $name was pulled from your settings."
    }
}

