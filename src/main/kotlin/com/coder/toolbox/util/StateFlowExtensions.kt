package com.coder.toolbox.util

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/**
 * Suspends the coroutine until first true value is received.
 */
suspend fun StateFlow<Boolean>.waitForTrue() = this.first { it }

/**
 * Suspends the coroutine until first false value is received.
 */
suspend fun StateFlow<Boolean>.waitForFalseWithTimeout(duration: Duration): Boolean? {
    if (!this.value) return false

    return withTimeoutOrNull(duration) {
        this@waitForFalseWithTimeout.first { !it }
    }
}