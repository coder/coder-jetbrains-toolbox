package com.coder.toolbox.diagnostics

import com.coder.toolbox.session.SessionId
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
import java.time.format.DateTimeFormatterBuilder

private const val REPLAY_CHUNK_SIZE = 100
private const val BUFFERED_PREFIX = "[buffered] "
private val BUFFERED_TIMESTAMP_FORMATTER = DateTimeFormatterBuilder().appendInstant(3).toFormatter()

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
        if (capacity == 0 || logger.effectiveLevel.value == LogLevel.OFF || logger.isEnabled(level)) return
        recordEntry(level, message, exception)
    }

    fun record(level: LogLevel, exception: Throwable?, message: () -> String): () -> String {
        if (capacity == 0 || logger.effectiveLevel.value == LogLevel.OFF || logger.isEnabled(level)) return message
        val resolved = message()
        recordEntry(level, resolved, exception)
        // Toolbox may evaluate this later if its level changes. Never evaluate the original supplier twice.
        return { resolved }
    }

    fun flush(reason: String, sessionId: SessionId? = null) {
        synchronized(lock) {
            if (count == 0 || logger.effectiveLevel.value == LogLevel.OFF) return
            if (!replay("${BUFFERED_PREFIX}replaying $count buffered entries ($reason)", sessionId)) return

            while (count > 0) {
                if (logger.effectiveLevel.value == LogLevel.OFF) return
                val chunkSize = minOf(count, REPLAY_CHUNK_SIZE)
                val message = buildString {
                    repeat(chunkSize) { index ->
                        if (index > 0) append('\n')
                        val entry = requireNotNull(entries[(oldestRecordIndex + index) % entries.size])
                        appendBufferedEntry(entry)
                    }
                }
                if (!replay(message, sessionId)) return

                repeat(chunkSize) {
                    entries[oldestRecordIndex] = null
                    oldestRecordIndex = if (oldestRecordIndex == entries.lastIndex) 0 else oldestRecordIndex + 1
                }
                count -= chunkSize
            }

            replay("${BUFFERED_PREFIX}end of buffered logs ($reason)", sessionId)
        }
    }

    private fun StringBuilder.appendBufferedEntry(entry: FlightRecorderEntry) {
        append(BUFFERED_PREFIX)
        append(BUFFERED_TIMESTAMP_FORMATTER.format(entry.timestamp))
        append(' ').append(entry.level.name).append(' ')
        entry.message.lineSequence().forEachIndexed { index, line ->
            if (index > 0) append('\n').append(BUFFERED_PREFIX)
            append(line)
        }
    }

    private fun replay(message: String, sessionId: SessionId?): Boolean {
        val level = logger.effectiveLevel.value
        if (level == LogLevel.OFF) return false
        val text = withSessionId(sessionId, message)
        when (level) {
            LogLevel.TRACE, LogLevel.DEBUG, LogLevel.INFO -> logger.info(text)
            LogLevel.WARN -> logger.warn(text)
            LogLevel.ERROR -> logger.error(text)
            LogLevel.OFF -> return false
        }
        return true
    }

    private fun recordEntry(level: LogLevel, message: String, exception: Throwable?) {
        val text = if (exception == null) message else "$message\n${exception.stackTraceToString()}"
        synchronized(lock) {
            if (entries.isEmpty()) return
            val entry = FlightRecorderEntry(level, clock.instant(), text)
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
