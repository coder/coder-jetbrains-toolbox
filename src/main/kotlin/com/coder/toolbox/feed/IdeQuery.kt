package com.coder.toolbox.feed

/**
 * Query object for finding the best matching IDE from loaded feeds.
 *
 * This encapsulates the filtering criteria for IDE selection, including
 * product code, type (release/eap), and optionally available versions.
 */
data class IdeQuery(
    /**
     * The IntelliJ product code (e.g., "RR" for RustRover, "IU" for IntelliJ IDEA Ultimate)
     */
    val productCode: String,

    /**
     * The type of IDE release to filter for
     */
    val type: IdeType,

    /**
     * List of available builds to install.
     */
    val availableBuilds: List<String>
)
