package com.coder.toolbox.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

internal class ProcessRunnerTest {
    @Test
    fun `timeout terminates an observed child after its parent exits`() {
        withChildCommand("exit-parent") { command, releaseParent, handles ->
            assertFailsWith<ProcessTimeoutException> {
                runProcess(command, timeoutMillis = 5000, onOutputLine = {
                    // Keep the parent alive across several observation intervals before allowing it to exit.
                    if (it == "ready") Thread.sleep(500)
                    releaseParent(it)
                })
            }
            handles().forEach { assertFalse(it.isAlive, "Process ${it.pid()} survived timeout") }
        }
    }

    @Test
    fun `timeout terminates a parent and its running child`() {
        withChildCommand("keep-parent") { command, _, handles ->
            assertFailsWith<ProcessTimeoutException> {
                runProcess(command, timeoutMillis = 2000)
            }
            handles().forEach { assertFalse(it.isAlive, "Process ${it.pid()} survived timeout") }
        }
    }

    @Test
    fun `successful command can leave a background child with redirected output`() {
        withChildCommand("detached-output") { command, releaseParent, handles ->
            val result = runProcess(command, timeoutMillis = 5000, onOutputLine = { releaseParent(it) })
            assertEquals(0, result.exitCode)
            assertEquals("ready", result.stdout.trim())
            assertEquals(1, handles().count { it.isAlive })
        }
    }

    @Test
    fun `cancellation terminates both parent and child`() = runBlocking {
        val ready = CompletableDeferred<Unit>()
        withChildCommand("keep-parent") { command, _, handles ->
            val job = launch(Dispatchers.Default) {
                runInterruptible {
                    runProcess(command, onOutputLine = { if (it == "ready") ready.complete(Unit) })
                }
            }
            try {
                withTimeout(5000) { ready.await() }
                withTimeout(5000) { job.cancelAndJoin() }
                handles().forEach { assertFalse(it.isAlive, "Process ${it.pid()} survived cancellation") }
            } finally {
                job.cancelAndJoin()
            }
        }
    }

    @Test
    fun `missing command raises an execution error`() {
        val error = assertFailsWith<ProcessExecutionException> {
            runProcess(listOf("coder-review-command-that-does-not-exist"), timeoutMillis = 5000)
        }
        assertContains(error.message.orEmpty(), "Failed to start process")
    }

    @Test
    fun `reader failure terminates both parent and child`() {
        withChildCommand("keep-parent") { command, _, handles ->
            val error = assertFailsWith<ProcessExecutionException> {
                runProcess(command, timeoutMillis = 5000, onOutputLine = { error("callback failed") })
            }
            assertContains(error.message.orEmpty(), "callback failed")
            handles().forEach { assertFalse(it.isAlive, "Process ${it.pid()} survived reader failure") }
        }
    }

    @Test
    @IgnoreOnWindows
    fun `cancellation terminates a running process after delivering live progress`() = runBlocking {
        val pid = CompletableDeferred<Long>()
        val job = launch(Dispatchers.Default) {
            runInterruptible {
                runProcess(listOf("sh", "-c", "echo $$; exec sleep 30"), onOutputLine = { pid.complete(it.toLong()) })
            }
        }
        var process: ProcessHandle? = null
        try {
            process = ProcessHandle.of(withTimeout(5000) { pid.await() }).orElseThrow()
            withTimeout(5000) { job.cancelAndJoin() }
            assertFalse(process.isAlive)
        } finally {
            job.cancelAndJoin()
            process?.destroyForcibly()
        }
    }

    @Test
    @IgnoreOnWindows
    fun `runProcess closes stdin so unexpected prompts see EOF`() {
        val result = runProcess(listOf("sh", "-c", "read value || printf eof"), timeoutMillis = 2000)
        assertEquals("eof", result.stdout)
    }

    @Test
    @IgnoreOnWindows
    fun `runProcess streams complete lines and retains original output`() {
        val lines = mutableListOf<String>()
        val text = "x".repeat(8193)
        val result = runProcess(
            listOf("sh", "-c", "printf '%s' '$text'; printf ' done\\r\\nsecond\\rfinal'"),
            onOutputLine = lines::add,
        )
        assertEquals(listOf("$text done", "second", "final"), lines)
        assertEquals("$text done\r\nsecond\rfinal", result.stdout)
    }

    @Test
    @IgnoreOnWindows
    fun `runProcess terminates the process on timeout`() {
        val pidFile = java.nio.file.Files.createTempFile("coder-process-timeout", ".pid")
        try {
            assertFailsWith<ProcessTimeoutException> {
                runProcess(
                    listOf("sh", "-c", "echo $$ > '$pidFile'; exec sleep 30"),
                    timeoutMillis = 1000,
                )
            }
            val pid = pidFile.toFile().readText().trim().toLong()
            assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
        } finally {
            java.nio.file.Files.deleteIfExists(pidFile)
        }
    }

    @Test
    @IgnoreOnWindows
    fun `runProcess reports reader callback failure`() {
        val error = assertFailsWith<ProcessExecutionException> {
            runProcess(listOf("sh", "-c", "printf 'progress\\n'"), onOutputLine = { error("callback failed") })
        }
        assertContains(error.message.orEmpty(), "callback failed")
    }

    @Test
    @IgnoreOnWindows
    fun `runProcess redacts unlabeled token output in progress and failures`() {
        val lines = mutableListOf<String>()
        val error = assertFailsWith<ProcessExitException> {
            runProcess(
                listOf("sh", "-c", "printf '%s\\n' \"${'$'}CODER_SESSION_TOKEN\"; exit 1"),
                environment = mapOf("CODER_SESSION_TOKEN" to "secret-value"),
                onOutputLine = lines::add,
            )
        }
        assertEquals(listOf("<redacted>"), lines)
        assertFalse(error.message.orEmpty().contains("secret-value"))
    }

    @Test
    @IgnoreOnWindows
    fun `runProcess captures stdout and stderr`() {
        val result = runProcess(listOf("sh", "-c", "printf hello && printf problem >&2"))

        assertEquals(0, result.exitCode)
        assertEquals("hello", result.stdout)
        assertEquals("problem", result.stderr)
    }

    @Test
    @IgnoreOnWindows
    fun `runProcess can discard stderr on success`() {
        val result = runProcess(
            listOf("sh", "-c", "printf hello && printf problem >&2"),
            stderrMode = ProcessStderrMode.DISCARD_ON_SUCCESS,
        )

        assertEquals(0, result.exitCode)
        assertEquals("hello", result.stdout)
        assertEquals("", result.stderr)
    }

    @Test
    @IgnoreOnWindows
    fun `runProcess includes stderr on failure when discarding stderr on success`() {
        val ex = assertFailsWith<ProcessExitException> {
            runProcess(
                listOf("sh", "-c", "printf problem >&2; exit 5"),
                stderrMode = ProcessStderrMode.DISCARD_ON_SUCCESS,
            )
        }

        assertEquals(5, ex.result.exitCode)
        assertEquals("problem", ex.result.stderr)
        assertContains(ex.message.orEmpty(), "problem")
    }

    @Test
    @IgnoreOnUnix
    fun `runProcess captures stdout and stderr on windows`() {
        val result = runProcess(listOf("cmd.exe", "/c", "echo hello&&echo problem 1>&2"))

        assertEquals(0, result.exitCode)
        assertContains(result.stdout, "hello")
        assertContains(result.stderr, "problem")
    }

    @Test
    @IgnoreOnUnix
    fun `runProcess can discard stderr on success on windows`() {
        val result = runProcess(
            listOf("cmd.exe", "/c", "echo hello&&echo problem 1>&2"),
            stderrMode = ProcessStderrMode.DISCARD_ON_SUCCESS,
        )

        assertEquals(0, result.exitCode)
        assertContains(result.stdout, "hello")
        assertEquals("", result.stderr)
    }

    @Test
    @IgnoreOnUnix
    fun `runProcess includes stderr on failure when discarding stderr on success on windows`() {
        val ex = assertFailsWith<ProcessExitException> {
            runProcess(
                listOf("cmd.exe", "/c", "echo problem 1>&2&&exit /b 5"),
                stderrMode = ProcessStderrMode.DISCARD_ON_SUCCESS,
            )
        }

        assertEquals(5, ex.result.exitCode)
        assertContains(ex.result.stderr, "problem")
        assertContains(ex.message.orEmpty(), "problem")
    }

    @Test
    @IgnoreOnUnix
    fun `runProcess passes environment to child process on windows`() {
        val result = runProcess(
            listOf("cmd.exe", "/c", "echo token=%CODER_SESSION_TOKEN%"),
            mapOf("CODER_SESSION_TOKEN" to "token"),
        )

        assertEquals("token=token", result.stdout.trim())
    }

    @Test
    @IgnoreOnUnix
    fun `runProcess accepts configured non-zero exit code on windows`() {
        val result = runProcess(
            listOf("cmd.exe", "/c", "echo expected failure&&exit /b 7"),
            expectedExitCodes = 0..7,
        )

        assertEquals(7, result.exitCode)
        assertContains(result.stdout, "expected failure")
    }

    @Test
    @IgnoreOnUnix
    fun `runProcess redacts labeled token values in failure messages on windows`() {
        val ex = assertFailsWith<ProcessExitException> {
            runProcess(
                listOf("cmd.exe", "/c", "echo CODER_SESSION_TOKEN=%CODER_SESSION_TOKEN% 1>&2&&exit /b 7"),
                mapOf("CODER_SESSION_TOKEN" to "super-secret-token"),
            )
        }

        assertContains(ex.message.orEmpty(), "CODER_SESSION_TOKEN=<redacted>")
        assertFalse(ex.message.orEmpty().contains("super-secret-token"))
    }

    @Test
    fun `runProcess passes environment to child process`() {
        val result =
            if (getOS() == OS.WINDOWS) {
                runProcess(
                    listOf("cmd.exe", "/c", "echo %CODER_SESSION_TOKEN%"),
                    mapOf("CODER_SESSION_TOKEN" to "token"),
                )
            } else {
                runProcess(
                    listOf("sh", "-c", "printf %s \"${'$'}CODER_SESSION_TOKEN\""),
                    mapOf("CODER_SESSION_TOKEN" to "token"),
                )
            }

        assertEquals("token", result.stdout.trim())
    }

    @Test
    fun `runProcess throws sanitized exception on unexpected exit`() {
        val ex =
            if (getOS() == OS.WINDOWS) {
                assertFailsWith<ProcessExitException> {
                    runProcess(
                        listOf("cmd.exe", "/c", "echo CODER_SESSION_TOKEN=%CODER_SESSION_TOKEN% 1>&2&&exit /b 7"),
                        mapOf("CODER_SESSION_TOKEN" to "super-secret-token"),
                    )
                }
            } else {
                assertFailsWith<ProcessExitException> {
                    runProcess(
                        listOf("sh", "-c", "printf 'CODER_SESSION_TOKEN=%s' \"${'$'}CODER_SESSION_TOKEN\" >&2; exit 7"),
                        mapOf("CODER_SESSION_TOKEN" to "super-secret-token"),
                    )
                }
            }

        assertEquals(7, ex.result.exitCode)
        assertFalse(ex.message.orEmpty().contains("super-secret-token"))
    }

    private inline fun withChildCommand(
        mode: String,
        test: (List<String>, (String) -> Unit, () -> List<ProcessHandle>) -> Unit,
    ) {
        val directory = Files.createTempDirectory("coder process tree ")
        val pidFile = directory.resolve("processes.pid")
        val releaseFile = directory.resolve("release parent")
        val handles = {
            Files.readString(pidFile).trim().split(" ").mapNotNull {
                ProcessHandle.of(it.toLong()).orElse(null)
            }
        }
        val command = listOf(
            Path.of(System.getProperty("java.home"), "bin", if (getOS() == OS.WINDOWS) "java.exe" else "java")
                .toString(),
            "-cp",
            Path.of(ProcessRunnerTestCommand::class.java.protectionDomain.codeSource.location.toURI()).toString(),
            ProcessRunnerTestCommand::class.java.name, mode, pidFile.toString(), releaseFile.toString(),
        )
        try {
            test(command, { if (it == "ready") Files.writeString(releaseFile, "exit") }, handles)
        } finally {
            if (Files.exists(pidFile)) {
                stopTestProcesses(handles())
            }
            Files.deleteIfExists(pidFile)
            Files.deleteIfExists(releaseFile)
            Files.deleteIfExists(directory)
        }
    }

    private fun stopTestProcesses(handles: List<ProcessHandle>) {
        handles.filter { it.isAlive }.forEach {
            it.destroyForcibly()
            it.onExit().get(5, TimeUnit.SECONDS)
        }
    }
}
