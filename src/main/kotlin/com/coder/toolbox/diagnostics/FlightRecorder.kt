package com.coder.toolbox.diagnostics

import com.coder.toolbox.settings.CONNECTION_LOG_BUFFER_SIZE_RANGE
import com.coder.toolbox.settings.ReadOnlyCoderSettings
import com.jetbrains.toolbox.api.core.diagnostics.LogLevel
import com.jetbrains.toolbox.api.core.diagnostics.Logger
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant

internal data class FlightRecorderEntry(val level: LogLevel, val timestamp: Instant, val message: String)

/** Captures recent messages filtered by Toolbox without retaining message suppliers or exceptions. */
internal class FlightRecorder(
    readOnlySettings: ReadOnlyCoderSettings,
    scope: CoroutineScope,
    private val logger: Logger,
) {
    private val lock = Any()
    private val clock: Clock = Clock.systemUTC()
    private var entries = arrayOfNulls<FlightRecorderEntry>(readOnlySettings.connectionLogBufferSize)
    private var oldestRecordIndex = 0
    private var count = 0

    init {
        scope.launch(CoroutineName("Connection log buffer capacity"), start = CoroutineStart.UNDISPATCHED) {
            try {
                readOnlySettings.observe { it.connectionLogBufferSize }.collect(::resize)
            } finally {
                resize(0)
            }
        }
    }

    private val capacity: Int get() = synchronized(lock) { entries.size }

    fun record(level: LogLevel, message: String, exception: Throwable? = null) {
        if (capacity == 0 || logger.isEnabled(level)) return
        record(level, clock.instant(), message, exception)
    }

    fun record(level: LogLevel, exception: Throwable?, message: () -> String): () -> String {
        if (capacity == 0 || logger.isEnabled(level)) return message
        val timestamp = clock.instant()
        val resolved = message()
        record(level, timestamp, resolved, exception)
        // Toolbox may evaluate this later if its level changes. Never evaluate the original supplier twice.
        return { resolved }
    }

    private fun record(level: LogLevel, timestamp: Instant, message: String, exception: Throwable?) {
        val text = if (exception == null) message else "$message\n${exception.stackTraceToString()}"
        val entry = FlightRecorderEntry(level, timestamp, text)
        synchronized(lock) {
            if (entries.isEmpty()) return
            val newestRecordIndex = oldestRecordIndex + count
            entries[if (newestRecordIndex >= entries.size) newestRecordIndex - entries.size else newestRecordIndex] =
                entry
            if (count < entries.size) {
                count++
            } else {
                oldestRecordIndex = if (oldestRecordIndex == entries.lastIndex) 0 else oldestRecordIndex + 1
            }
        }
    }

    /** Retains the newest entries, in insertion order, when the capacity changes. Zero clears stored entries. */
    private fun resize(capacity: Int) {
        require(capacity in CONNECTION_LOG_BUFFER_SIZE_RANGE)
        synchronized(lock) {
            if (capacity == entries.size) return
            val retained = minOf(count, capacity)
            val resized = arrayOfNulls<FlightRecorderEntry>(capacity)
            if (retained > 0) {
                val start = (oldestRecordIndex + count - retained) % entries.size
                val firstPart = minOf(retained, entries.size - start)
                entries.copyInto(resized, destinationOffset = 0, startIndex = start, endIndex = start + firstPart)
                if (firstPart < retained) {
                    entries.copyInto(
                        resized,
                        destinationOffset = firstPart,
                        startIndex = 0,
                        endIndex = retained - firstPart
                    )
                }
            }
            entries = resized
            oldestRecordIndex = 0
            count = retained
        }
    }
}
