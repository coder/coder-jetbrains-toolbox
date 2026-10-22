package com.coder.toolbox.diagnostics

import com.coder.toolbox.CoderRemoteEnvironment
import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.sdk.DataGen
import com.coder.toolbox.sdk.v2.models.WorkspaceAgentStatus
import com.coder.toolbox.sdk.v2.models.WorkspaceStatus
import com.coder.toolbox.session.SessionId
import com.coder.toolbox.session.SessionIdRegistry
import com.coder.toolbox.settings.ReadOnlyCoderSettings
import com.coder.toolbox.views.Action
import com.jetbrains.toolbox.api.core.diagnostics.LogLevel
import com.jetbrains.toolbox.api.core.diagnostics.Logger
import com.jetbrains.toolbox.api.localization.LocalizableStringFactory
import com.jetbrains.toolbox.api.ui.ToolboxUi
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CoderLoggerFlightRecorderTest {
    private val logLevel = MutableStateFlow(LogLevel.INFO)
    private val bufferSize = MutableStateFlow(1000)
    private val calls = mutableListOf<LogCall>()
    private val delegate = mockk<Logger>(relaxed = true) {
        every { effectiveLevel } returns logLevel
        every { isEnabled(any()) } answers { enabled(firstArg()) }
        every { trace(any<String>()) } answers { emit(LogLevel.TRACE, firstArg()) }
        every { debug(any<String>()) } answers { emit(LogLevel.DEBUG, firstArg()) }
        every { info(any<String>()) } answers { emit(LogLevel.INFO, firstArg()) }
        every { warn(any<String>()) } answers { emit(LogLevel.WARN, firstArg()) }
        every { error(any<String>()) } answers { emit(LogLevel.ERROR, firstArg()) }
        every { trace(any<() -> String>()) } answers { emitLazy(LogLevel.TRACE, firstArg()) }
        every { debug(any<() -> String>()) } answers { emitLazy(LogLevel.DEBUG, firstArg()) }
        every { info(any<() -> String>()) } answers { emitLazy(LogLevel.INFO, firstArg()) }
        every { warn(any<() -> String>()) } answers { emitLazy(LogLevel.WARN, firstArg()) }
        every { error(any<() -> String>()) } answers { emitLazy(LogLevel.ERROR, firstArg()) }
    }
    private val settings = mockk<ReadOnlyCoderSettings> {
        every { connectionLogBufferSize } answers { bufferSize.value }
        every { observe(any<(ReadOnlyCoderSettings) -> Int>()) } returns bufferSize
    }
    private val ui = mockk<ToolboxUi>(relaxed = true)
    private val i18n = mockk<LocalizableStringFactory>(relaxed = true)

    @Test
    fun `flush replays 250 entries oldest first in five calls and drains the buffer`() = runTest {
        // given
        bufferSize.value = 300
        val logger = CoderLogger(delegate, ui, backgroundScope, i18n, settings)
        repeat(250) { logger.debug("entry-$it") }
        assertTrue(calls.isEmpty())

        // when
        logger.flush("support_bundle")

        // then
        assertEquals(5, calls.size)
        assertEquals("[buffered] replaying 250 buffered entries (support_bundle)", calls.first().message)
        assertEquals("[buffered] end of buffered logs (support_bundle)", calls.last().message)
        val chunks = calls.subList(1, 4)
        assertEquals(listOf(100, 100, 50), chunks.map { it.message.lines().size })
        assertEquals(
            List(250) { "entry-$it" },
            chunks.flatMap { it.message.lines() }.map { it.substringAfter(" DEBUG ") },
        )
        assertTrue(calls.all { it.level == LogLevel.INFO })

        // when
        calls.clear()
        logger.flush("support_bundle")

        // then
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `capacity limits recording to the newest entries and the emptied buffer can be reused`() = runTest {
        // given
        bufferSize.value = 3
        val logger = CoderLogger(delegate, ui, backgroundScope, i18n, settings)
        repeat(5) { logger.debug("entry-$it") }

        // when
        logger.flush("remote_closed")

        // then
        assertEquals("[buffered] replaying 3 buffered entries (remote_closed)", calls.first().message)
        assertEquals(listOf("entry-2", "entry-3", "entry-4"), replayedDebugMessages())

        // when
        calls.clear()
        logger.debug("after flush")
        logger.flush("support_bundle")

        // then
        assertEquals(listOf("after flush"), replayedDebugMessages())
    }

    @Test
    fun `shrinking before a flush drops older entries and enforces the new capacity`() = runTest {
        // given
        bufferSize.value = 5
        val logger = CoderLogger(delegate, ui, backgroundScope, i18n, settings)
        repeat(7) { logger.debug("entry-$it") }

        // when
        bufferSize.value = 2
        runCurrent()
        logger.flush("remote_closed")

        // then
        assertEquals("[buffered] replaying 2 buffered entries (remote_closed)", calls.first().message)
        assertEquals(listOf("entry-5", "entry-6"), replayedDebugMessages())

        // when
        calls.clear()
        repeat(4) { logger.debug("after shrinking-$it") }
        logger.flush("smaller capacity overflow")

        // then
        assertEquals(listOf("after shrinking-2", "after shrinking-3"), replayedDebugMessages())
    }

    @Test
    fun `shrinking a partially filled buffer preserves entries that still fit`() = runTest {
        // given
        bufferSize.value = 5
        val logger = CoderLogger(delegate, ui, backgroundScope, i18n, settings)
        logger.debug("first")
        logger.debug("second")

        // when
        bufferSize.value = 3
        runCurrent()
        logger.flush("remote_closed")

        // then
        assertEquals(listOf("first", "second"), replayedDebugMessages())
    }

    @Test
    fun `growing a wrapped buffer immediately before flushing preserves all retained entries`() = runTest {
        // given
        bufferSize.value = 3
        val logger = CoderLogger(delegate, ui, backgroundScope, i18n, settings)
        repeat(5) { logger.debug("entry-$it") }

        // when
        bufferSize.value = 5
        runCurrent()
        logger.flush("remote_closed")

        // then
        assertEquals("[buffered] replaying 3 buffered entries (remote_closed)", calls.first().message)
        assertEquals(listOf("entry-2", "entry-3", "entry-4"), replayedDebugMessages())
    }

    @Test
    fun `growing capacity changes the recording limit from X to Y`() = runTest {
        // given
        bufferSize.value = 3
        val logger = CoderLogger(delegate, ui, backgroundScope, i18n, settings)

        // when
        repeat(5) { logger.debug("original-$it") }
        logger.flush("original capacity")

        // then
        assertEquals(listOf("original-2", "original-3", "original-4"), replayedDebugMessages())

        // when
        calls.clear()
        logger.debug("retained before growing")
        bufferSize.value = 5
        runCurrent()
        repeat(4) { logger.debug("grown-$it") }
        logger.flush("larger capacity")

        // then
        assertEquals(
            listOf("retained before growing", "grown-0", "grown-1", "grown-2", "grown-3"),
            replayedDebugMessages(),
        )

        // when
        calls.clear()
        repeat(7) { logger.debug("overflow-$it") }
        logger.flush("larger capacity overflow")

        // then
        assertEquals(
            listOf("overflow-2", "overflow-3", "overflow-4", "overflow-5", "overflow-6"),
            replayedDebugMessages(),
        )
    }

    @Test
    fun `batching handles entries below at and above each 100 entry boundary`() = runTest {
        listOf(
            1 to listOf(1),
            99 to listOf(99),
            100 to listOf(100),
            101 to listOf(100, 1),
            200 to listOf(100, 100),
            201 to listOf(100, 100, 1),
        ).forEach { (entryCount, expectedChunkSizes) ->
            // given
            calls.clear()
            bufferSize.value = entryCount
            val logger = CoderLogger(delegate, ui, backgroundScope, i18n, settings)
            repeat(entryCount) { logger.debug("entry-$it") }

            // when
            logger.flush("support_bundle")

            // then
            assertEquals(expectedChunkSizes.size + 2, calls.size, "Calls for $entryCount entries")
            assertEquals(
                expectedChunkSizes,
                calls.drop(1).dropLast(1).map { it.message.lines().size },
                "Chunk sizes for $entryCount entries",
            )
        }
    }

    @Test
    fun `buffered block contains the exact header entry metadata and footer`() = runTest {
        // given
        val logger = CoderLogger(delegate, ui, backgroundScope, i18n, settings)
        logger.debug("Connecting")
        logger.trace("Retrying")

        // when
        logger.flush("unreachable /route")

        // then
        assertEquals(3, calls.size)
        assertEquals("[buffered] replaying 2 buffered entries (unreachable /route)", calls[0].message)
        val timestamp = """\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z"""
        assertTrue(
            Regex("""\[buffered\] $timestamp DEBUG Connecting\n\[buffered\] $timestamp TRACE Retrying""")
                .matches(calls[1].message),
            calls[1].message,
        )
        assertEquals("[buffered] end of buffered logs (unreachable /route)", calls[2].message)
        assertEquals(List(3) { LogLevel.INFO }, calls.map { it.level })
    }

    @Test
    fun `error logs normally at ERROR while warn info debug and trace replay as error`() = runTest {
        // given
        logLevel.value = LogLevel.ERROR
        val logger = CoderLogger(delegate, ui, backgroundScope, i18n, settings)

        // when
        logAtEveryLevel(logger)

        // then
        assertEquals(
            listOf(LogLevel.ERROR).map { LogCall(it, "${it.name} message") },
            calls,
        )

        // when
        calls.clear()
        logger.flush("support_bundle")

        // then
        assertReplayedLevels(listOf(LogLevel.WARN, LogLevel.INFO, LogLevel.DEBUG, LogLevel.TRACE), LogLevel.ERROR)
    }

    @Test
    fun `error and warn log normally at WARN while info debug and trace replay as warn`() = runTest {
        // given
        logLevel.value = LogLevel.WARN
        val logger = CoderLogger(delegate, ui, backgroundScope, i18n, settings)

        // when
        logAtEveryLevel(logger)

        // then
        assertEquals(
            listOf(LogLevel.ERROR, LogLevel.WARN).map { LogCall(it, "${it.name} message") },
            calls,
        )

        // when
        calls.clear()
        logger.flush("support_bundle")

        // then
        assertReplayedLevels(listOf(LogLevel.INFO, LogLevel.DEBUG, LogLevel.TRACE), LogLevel.WARN)
    }

    @Test
    fun `error warn and info log normally at INFO while debug and trace replay as info`() = runTest {
        // given
        logLevel.value = LogLevel.INFO
        val logger = CoderLogger(delegate, ui, backgroundScope, i18n, settings)

        // when
        logAtEveryLevel(logger)

        // then
        assertEquals(
            listOf(LogLevel.ERROR, LogLevel.WARN, LogLevel.INFO).map { LogCall(it, "${it.name} message") },
            calls,
        )

        // when
        calls.clear()
        logger.flush("support_bundle")

        // then
        assertReplayedLevels(listOf(LogLevel.DEBUG, LogLevel.TRACE), LogLevel.INFO)
    }

    @Test
    fun `error warn info and debug log normally at DEBUG while trace replays as info`() = runTest {
        // given
        logLevel.value = LogLevel.DEBUG
        val logger = CoderLogger(delegate, ui, backgroundScope, i18n, settings)

        // when
        logAtEveryLevel(logger)

        // then
        assertEquals(
            listOf(LogLevel.ERROR, LogLevel.WARN, LogLevel.INFO, LogLevel.DEBUG)
                .map { LogCall(it, "${it.name} message") },
            calls,
        )

        // when
        calls.clear()
        logger.flush("support_bundle")

        // then
        assertReplayedLevels(listOf(LogLevel.TRACE), LogLevel.INFO)
    }

    @Test
    fun `every level logs normally at TRACE and flush emits nothing`() = runTest {
        // given
        logLevel.value = LogLevel.TRACE
        val logger = CoderLogger(delegate, ui, backgroundScope, i18n, settings)

        // when
        logAtEveryLevel(logger)

        // then
        assertEquals(
            listOf(LogLevel.ERROR, LogLevel.WARN, LogLevel.INFO, LogLevel.DEBUG, LogLevel.TRACE)
                .map { LogCall(it, "${it.name} message") },
            calls,
        )

        // when
        calls.clear()
        logger.flush("support_bundle")

        // then
        assertReplayedLevels(emptyList(), LogLevel.INFO)
    }

    @Test
    fun `flush selects the active replay method and preserves original levels within a chunk`() = runTest {
        mapOf(
            LogLevel.TRACE to LogLevel.INFO,
            LogLevel.DEBUG to LogLevel.INFO,
            LogLevel.INFO to LogLevel.INFO,
            LogLevel.WARN to LogLevel.WARN,
            LogLevel.ERROR to LogLevel.ERROR,
        ).forEach { (activeLevel, replayLevel) ->
            // given
            calls.clear()
            logLevel.value = LogLevel.ERROR
            val logger = CoderLogger(delegate, ui, backgroundScope, i18n, settings)
            logger.trace("trace")
            logger.debug("debug")
            logger.info("info")
            logger.warn("warning")

            // when
            logLevel.value = activeLevel
            logger.flush("unreachable /route")

            // then
            assertEquals(List(3) { replayLevel }, calls.map { it.level })
            assertEquals(
                listOf("TRACE trace", "DEBUG debug", "INFO info", "WARN warning"),
                calls[1].message.lines().map { it.substringAfter("Z ") },
            )
        }
    }

    @Test
    fun `replay keeps original UTC timestamps and prefixes continuation lines and session calls`() = runTest {
        // given
        val logger = CoderLogger(delegate, ui, backgroundScope, i18n, settings)
        val sessionId = SessionId.generate()
        val before = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        logger.trace("first\r\nsecond\rthird")
        logger.debug(IllegalStateException("failure"), "failed")
        val after = Instant.now().truncatedTo(ChronoUnit.MILLIS)

        // when
        logger.flush("certificate_error /route", sessionId)

        // then
        val sessionPrefix = "client_session_id=$sessionId "
        assertEquals(
            "${sessionPrefix}[buffered] replaying 2 buffered entries (certificate_error /route)",
            calls.first().message,
        )
        assertEquals(
            "${sessionPrefix}[buffered] end of buffered logs (certificate_error /route)",
            calls.last().message,
        )
        val lines = calls[1].message.removePrefix(sessionPrefix).lines()
        val firstLine = assertNotNull(
            Regex("""\[buffered\] (\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z) TRACE first""")
                .matchEntire(lines.first()),
        )
        assertTrue(Instant.parse(firstLine.groupValues[1]) in before..after)
        assertEquals("[buffered] second", lines[1])
        assertEquals("[buffered] third", lines[2])
        assertTrue(lines[3].endsWith("Z DEBUG failed"))
        assertEquals("[buffered] java.lang.IllegalStateException: failure", lines[4])
        assertTrue(lines.drop(1).all { it.startsWith("[buffered] ") })
    }

    @Test
    fun `initial OFF records no strings or lambdas and flush makes no logging calls`() = runTest {
        // given
        logLevel.value = LogLevel.OFF
        val logger = CoderLogger(delegate, ui, backgroundScope, i18n, settings)
        var evaluations = 0

        // when
        logAtEveryLevel(logger)
        logLazyAtEveryLevel(logger) { level -> evaluations++; "${level.name} message" }

        // then
        assertEquals(0, evaluations)
        assertTrue(calls.isEmpty())

        // when
        clearMocks(delegate, answers = false, recordedCalls = true)
        logger.flush("support_bundle")

        // then
        assertNoLoggingCalls(delegate)

        // when
        logLevel.value = LogLevel.INFO
        logger.flush("after enabling logging")

        // then
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `initial zero capacity skips recording and flush makes no logging calls`() = runTest {
        // given
        bufferSize.value = 0
        val logger = CoderLogger(delegate, ui, backgroundScope, i18n, settings)

        // when
        logAtEveryLevel(logger)

        // then
        assertEquals(
            listOf(LogLevel.ERROR, LogLevel.WARN, LogLevel.INFO).map { LogCall(it, "${it.name} message") },
            calls,
        )

        // when
        var evaluations = 0
        logger.debug { evaluations++; "ignored debug" }
        logger.trace { evaluations++; "ignored trace" }

        // then
        assertEquals(0, evaluations)

        // when
        calls.clear()
        clearMocks(delegate, answers = false, recordedCalls = true)
        logger.flush("support_bundle")

        // then
        assertNoLoggingCalls(delegate)

        // when
        bufferSize.value = 3
        runCurrent()
        logger.flush("after enabling recording")

        // then
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `flushing an empty recorder makes no logging calls`() = runTest {
        // given
        val logger = CoderLogger(delegate, ui, backgroundScope, i18n, settings)

        // when
        logger.flush("support_bundle")

        // then
        assertTrue(calls.isEmpty())
        assertNoLoggingCalls(delegate)
    }

    @Test
    fun `off skips capture and flushing without discarding existing records`() = runTest {
        // given
        val logger = CoderLogger(delegate, ui, backgroundScope, i18n, settings)
        logger.debug("before off")
        var evaluations = 0

        // when
        logLevel.value = LogLevel.OFF
        logger.debug("ignored string")
        logger.debug { evaluations++; "ignored lambda" }
        logger.flush("remote_closed")

        // then
        assertEquals(0, evaluations)
        assertTrue(calls.isEmpty())

        // when
        logLevel.value = LogLevel.INFO
        logger.flush("support_bundle")

        // then
        assertEquals("[buffered] replaying 1 buffered entries (support_bundle)", calls.first().message)
        assertTrue(calls[1].message.endsWith("DEBUG before off"))
    }

    @Test
    fun `zero capacity clears records and recording resumes after increasing capacity`() = runTest {
        // given
        bufferSize.value = 2
        val logger = CoderLogger(delegate, ui, backgroundScope, i18n, settings)
        logger.debug("discarded")
        var evaluations = 0

        // when
        bufferSize.value = 0
        runCurrent()
        logger.debug { evaluations++; "disabled" }
        logger.flush("support_bundle")

        // then
        assertEquals(0, evaluations)
        assertTrue(calls.isEmpty())

        // when
        bufferSize.value = 2
        runCurrent()
        logger.debug("after enabling")
        logger.flush("support_bundle")

        // then
        assertEquals("[buffered] replaying 1 buffered entries (support_bundle)", calls.first().message)
        assertTrue(calls[1].message.endsWith("DEBUG after enabling"))
    }

    @Test
    fun `failed replay leaves the unwritten chunk available for another flush`() = runTest {
        // given
        val logger = CoderLogger(delegate, ui, backgroundScope, i18n, settings)
        logger.debug("retained")
        every { delegate.info(match<String> { it.contains(" DEBUG ") }) } throws
                IllegalStateException("log write failed")

        // when / then
        assertFailsWith<IllegalStateException> { logger.flush("first attempt") }

        // given
        calls.clear()
        every { delegate.info(any<String>()) } answers {
            calls += LogCall(LogLevel.INFO, firstArg())
        }

        // when
        logger.flush("second attempt")

        // then
        assertEquals("[buffered] replaying 1 buffered entries (second attempt)", calls.first().message)
        assertTrue(calls[1].message.endsWith("DEBUG retained"))
    }

    @Test
    fun `turning logging off during a flush retains the remaining chunks`() = runTest {
        // given
        bufferSize.value = 300
        val logger = CoderLogger(delegate, ui, backgroundScope, i18n, settings)
        repeat(250) { logger.debug("entry-$it") }
        every { delegate.info(any<String>()) } answers {
            val message = firstArg<String>()
            calls += LogCall(LogLevel.INFO, message)
            if (message.contains(" DEBUG ")) logLevel.value = LogLevel.OFF
        }

        // when
        logger.flush("remote_closed")

        // then
        assertEquals(2, calls.size)
        assertEquals(100, calls[1].message.lines().size)

        // given
        calls.clear()
        logLevel.value = LogLevel.INFO
        every { delegate.info(any<String>()) } answers {
            calls += LogCall(LogLevel.INFO, firstArg())
        }

        // when
        logger.flush("support_bundle")

        // then
        assertEquals(4, calls.size)
        assertEquals("[buffered] replaying 150 buffered entries (support_bundle)", calls.first().message)
        assertTrue(calls[1].message.lines().first().endsWith("DEBUG entry-100"))
    }

    @Test
    fun `captured lambda is evaluated once even if Toolbox enables its level before delegation`() = runTest {
        // given
        val logger = CoderLogger(delegate, ui, backgroundScope, i18n, settings)
        var evaluations = 0

        // when
        logger.debug {
            evaluations++
            logLevel.value = LogLevel.DEBUG
            "lazy message"
        }

        // then
        assertEquals(listOf(LogCall(LogLevel.DEBUG, "lazy message")), calls)

        // when
        calls.clear()
        logLevel.value = LogLevel.INFO
        logger.flush("support_bundle")

        // then
        assertEquals(1, evaluations)
        assertTrue(calls[1].message.endsWith("DEBUG lazy message"))
    }

    @Test
    fun `enabled lambdas log normally once and do not enter the recorder`() = runTest {
        // given
        logLevel.value = LogLevel.TRACE
        val logger = CoderLogger(delegate, ui, backgroundScope, i18n, settings)
        var evaluations = 0

        // when
        logLazyAtEveryLevel(logger) { level -> evaluations++; "${level.name} message" }

        // then
        assertEquals(5, evaluations)
        assertEquals(
            listOf(LogLevel.ERROR, LogLevel.WARN, LogLevel.INFO, LogLevel.DEBUG, LogLevel.TRACE)
                .map { LogCall(it, "${it.name} message") },
            calls,
        )

        // when
        calls.clear()
        logger.flush("support_bundle")

        // then
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `filtered lambdas are evaluated once and replay with their original levels`() = runTest {
        // given
        logLevel.value = LogLevel.ERROR
        val logger = CoderLogger(delegate, ui, backgroundScope, i18n, settings)
        var evaluations = 0

        // when
        logger.warn { evaluations++; "warning" }
        logger.info { evaluations++; "information" }
        logger.debug { evaluations++; "debugging" }
        logger.trace { evaluations++; "tracing" }

        // then
        assertEquals(4, evaluations)
        assertTrue(calls.isEmpty())

        // when
        logger.flush("support_bundle")

        // then
        assertEquals(4, evaluations)
        assertEquals(List(3) { LogLevel.ERROR }, calls.map { it.level })
        assertEquals(
            listOf("WARN warning", "INFO information", "DEBUG debugging", "TRACE tracing"),
            calls[1].message.lines().map { it.substringAfter("Z ") },
        )
    }

    @Test
    fun `filtered exception lambda replays its message and exception without evaluating again`() = runTest {
        // given
        val logger = CoderLogger(delegate, ui, backgroundScope, i18n, settings)
        var evaluations = 0
        logger.debug(IllegalStateException("failure")) { evaluations++; "failed request" }
        assertTrue(calls.isEmpty())

        // when
        logger.flush("support_bundle")

        // then
        assertEquals(1, evaluations)
        val lines = calls[1].message.lines()
        assertTrue(lines.first().endsWith("Z DEBUG failed request"))
        assertEquals("[buffered] java.lang.IllegalStateException: failure", lines[1])
        assertTrue(lines.all { it.startsWith("[buffered] ") })
    }

    @Test
    fun `action failure replays the preceding context with its current session`() = runTest {
        // given
        val logger = CoderLogger(delegate, ui, backgroundScope, i18n, settings)
        val context = mockk<CoderToolboxContext>(relaxed = true)
        every { context.logger } returns logger
        every { context.cs } returns this
        val sessionId = SessionId.generate()
        val failure = IllegalStateException("stop failed")
        val action = Action(context, "Stop workspace") { throw failure }
            .withCurrentSessionId { sessionId }
        logger.debug(sessionId, "before stopping")

        // when
        action.run()
        runCurrent()

        // then
        verify(exactly = 1) { delegate.error(failure, "client_session_id=$sessionId stop failed") }
        val prefix = "client_session_id=$sessionId "
        assertEquals(3, calls.size)
        assertEquals(
            "${prefix}[buffered] replaying 1 buffered entries (action_failed Stop workspace)",
            calls.first().message,
        )
        assertTrue(calls[1].message.endsWith("DEBUG ${prefix}before stopping"))
        assertEquals(
            "${prefix}[buffered] end of buffered logs (action_failed Stop workspace)",
            calls.last().message,
        )
    }

    @Test
    fun `environment becoming unreachable replays context and the filtered trigger through CoderLogger`() = runTest {
        // given
        logLevel.value = LogLevel.ERROR
        val logger = CoderLogger(delegate, ui, backgroundScope, i18n, settings)
        val context = mockk<CoderToolboxContext>(relaxed = true)
        every { context.logger } returns logger
        every { context.cs } returns backgroundScope
        every { context.i18n } returns i18n
        val workspace = DataGen.workspace(
            name = "workspace-${UUID.randomUUID()}",
            agents = mapOf("agent" to UUID.randomUUID().toString()),
        )
        val agent = requireNotNull(workspace.latestBuild.resources.single().agents).single()
        val environment = CoderRemoteEnvironment(
            context, mockk(relaxed = true), mockk(relaxed = true), Channel(Channel.CONFLATED), workspace, agent,
        )
        val sessionId = SessionIdRegistry.startSession(context, workspace.name, agent.name)
        try {
            logger.flush("setup")
            calls.clear()
            logger.debug(sessionId, "before status update")
            assertTrue(environment.state.value.isReachable)

            // when
            environment.update(
                workspace.copy(latestBuild = workspace.latestBuild.copy(status = WorkspaceStatus.STOPPING)),
                agent,
            )

            // then
            assertFalse(environment.state.value.isReachable)
            val prefix = "client_session_id=$sessionId "
            val reason = "environment_status_changed ${environment.id}"
            assertEquals(List(3) { LogLevel.ERROR }, calls.map { it.level })
            assertEquals("${prefix}[buffered] replaying 2 buffered entries ($reason)", calls.first().message)
            val lines = calls[1].message.lines()
            assertTrue(lines[0].endsWith("DEBUG ${prefix}before status update"))
            assertTrue(lines[1].contains("INFO ${prefix}Overall status for workspace ${environment.id}"))
            assertEquals("${prefix}[buffered] end of buffered logs ($reason)", calls.last().message)
        } finally {
            environment.dispose()
            SessionIdRegistry.removeSession(workspace.name, agent.name)
        }
    }

    @Test
    fun `status updates without losing reachability preserve buffered context`() = runTest {
        // given
        val logger = CoderLogger(delegate, ui, backgroundScope, i18n, settings)
        val context = mockk<CoderToolboxContext>(relaxed = true)
        every { context.logger } returns logger
        every { context.cs } returns backgroundScope
        every { context.i18n } returns i18n
        val workspace = DataGen.workspace(
            name = "workspace-${UUID.randomUUID()}",
            agents = mapOf("agent" to UUID.randomUUID().toString()),
        ).let { it.copy(latestBuild = it.latestBuild.copy(status = WorkspaceStatus.STOPPED)) }
        val agent = requireNotNull(workspace.latestBuild.resources.single().agents).single()
        val environment = CoderRemoteEnvironment(
            context, mockk(relaxed = true), mockk(relaxed = true), Channel(Channel.CONFLATED), workspace, agent,
        )
        try {
            assertFalse(environment.state.value.isReachable)
            logger.debug("before status updates")
            val updates = listOf(
                Triple(WorkspaceStatus.FAILED, agent, false),
                Triple(WorkspaceStatus.RUNNING, agent, true),
                Triple(WorkspaceStatus.RUNNING, agent.copy(status = WorkspaceAgentStatus.DISCONNECTED), true),
            )
            for ((status, updatedAgent, expectedReachability) in updates) {
                // when
                environment.update(
                    workspace.copy(latestBuild = workspace.latestBuild.copy(id = UUID.randomUUID(), status = status)),
                    updatedAgent,
                )

                // then
                assertEquals(expectedReachability, environment.state.value.isReachable)
                assertTrue(calls.none { it.message.startsWith("[buffered]") })
            }
            assertEquals(3, calls.size)
            assertTrue(calls.all { it.level == LogLevel.INFO && it.message.startsWith("Overall status for workspace") })

            // when
            calls.clear()
            logger.flush("support_bundle")

            // then
            assertEquals(listOf("before status updates"), replayedDebugMessages())
        } finally {
            environment.dispose()
        }
    }

    private fun assertReplayedLevels(bufferedLevels: List<LogLevel>, replayLevel: LogLevel) {
        if (bufferedLevels.isEmpty()) {
            assertTrue(calls.isEmpty())
        } else {
            assertEquals(List(3) { replayLevel }, calls.map { it.level })
            assertEquals(
                "[buffered] replaying ${bufferedLevels.size} buffered entries (support_bundle)",
                calls.first().message,
            )
            assertEquals(
                bufferedLevels.map { "${it.name} ${it.name} message" },
                calls[1].message.lines().map { it.substringAfter("Z ") },
            )
            assertEquals("[buffered] end of buffered logs (support_bundle)", calls.last().message)
        }
    }

    private fun logAtEveryLevel(logger: CoderLogger) {
        logger.error("ERROR message")
        logger.warn("WARN message")
        logger.info("INFO message")
        logger.debug("DEBUG message")
        logger.trace("TRACE message")
    }

    private fun logLazyAtEveryLevel(logger: CoderLogger, message: (LogLevel) -> String) {
        logger.error { message(LogLevel.ERROR) }
        logger.warn { message(LogLevel.WARN) }
        logger.info { message(LogLevel.INFO) }
        logger.debug { message(LogLevel.DEBUG) }
        logger.trace { message(LogLevel.TRACE) }
    }

    private fun replayedDebugMessages(): List<String> =
        calls.drop(1).dropLast(1).flatMap { it.message.lines() }.map { it.substringAfter(" DEBUG ") }

    private fun assertNoLoggingCalls(logger: Logger) {
        verify(exactly = 0) {
            logger.error(any<String>())
            logger.warn(any<String>())
            logger.info(any<String>())
            logger.debug(any<String>())
            logger.trace(any<String>())
        }
    }

    private data class LogCall(val level: LogLevel, val message: String)

    private fun enabled(level: LogLevel): Boolean = logLevel.value != LogLevel.OFF && level >= logLevel.value

    private fun emit(level: LogLevel, message: String) {
        if (enabled(level)) calls += LogCall(level, message)
    }

    private fun emitLazy(level: LogLevel, message: () -> String) {
        if (enabled(level)) calls += LogCall(level, message())
    }
}
