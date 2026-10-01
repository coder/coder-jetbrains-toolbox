package com.coder.toolbox.util

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

data class ProcessResult(
    val command: List<String>,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

sealed class ProcessRunnerException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message.sanitizeSecrets(), cause)

enum class ProcessStderrMode {
    CAPTURE,
    DISCARD_ON_SUCCESS,
}

class ProcessExecutionException(
    message: String,
    cause: Throwable? = null
) : ProcessRunnerException(message, cause)

class ProcessTimeoutException(command: List<String>, timeoutMillis: Long) :
    ProcessRunnerException("Process timed out after $timeoutMillis ms: $command")

class ProcessExitException(
    val result: ProcessResult,
    private val expectedExitCodes: IntRange,
) : ProcessRunnerException(
    buildString {
        append("Unexpected exit value: ${result.exitCode}, allowed exit values: $expectedExitCodes")
        append(", executed command ${result.command}")
        if (result.stdout.isNotBlank()) {
            append(", stdout was ${result.stdout.length} bytes:\n${result.stdout}")
        }
        if (result.stderr.isNotBlank()) {
            append(", stderr was ${result.stderr.length} bytes:\n${result.stderr}")
        }
    }.sanitizeSecrets()
)

/**
 * Runs a non-interactive process, capturing both output streams. Only exit code 0 is accepted by default.
 * [timeoutMillis] bounds short commands; null leaves workspace starts unbounded.
 * [onOutputLine] receives complete, sanitized stdout/stderr lines, including carriage-return progress updates
 * and a final unterminated line. The captured output retains its original line endings.
 * [ProcessStderrMode.DISCARD_ON_SUCCESS] discards stderr only for successful commands.
 */
fun runProcess(
    command: List<String>,
    environment: Map<String, String> = emptyMap(),
    expectedExitCodes: IntRange = 0..0,
    stderrMode: ProcessStderrMode = ProcessStderrMode.CAPTURE,
    timeoutMillis: Long? = null,
    onOutputLine: ((String) -> Unit)? = null,
): ProcessResult {
    require(timeoutMillis == null || timeoutMillis > 0) { "Process timeout must be positive" }
    val sessionToken = environment["CODER_SESSION_TOKEN"]
    val safeCommand = command.mapIndexed { index, arg ->
        if (index > 0 && command[index - 1] == "--token") "<redacted>" else arg.sanitizeSecrets(sessionToken)
    }
    val process = try {
        ProcessBuilder(command).apply { environment().putAll(environment) }.start()
    } catch (ex: IOException) {
        throw ProcessExecutionException(
            "Failed to start process $safeCommand: ${ex.message.orEmpty().sanitizeSecrets(sessionToken)}"
        )
    }
    val deadline = timeoutMillis?.let { System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(it) }
    val stdout = StringBuilder()
    val stderr = StringBuilder()
    val readerFailure = AtomicReference<Throwable?>()
    val callbackLock = Any()
    val readers = mutableListOf<Thread>()

    fun readOutput(stream: InputStream, output: StringBuilder, name: String): Thread =
        thread(name = name, isDaemon = true) {
            try {
                captureOutput(stream, output, onOutputLine?.let { report ->
                    { line -> synchronized(callbackLock) { report(line.sanitizeSecrets(sessionToken)) } }
                })
            } catch (ex: Exception) {
                readerFailure.compareAndSet(null, ex)
                process.destroyForcibly()
            }
        }

    try {
        // An unexpected prompt must see EOF rather than waiting for input forever.
        process.outputStream.close()
        readers += readOutput(process.inputStream, stdout, "process-stdout-reader")
        readers += readOutput(process.errorStream, stderr, "process-stderr-reader")
        if (deadline == null) {
            process.waitFor()
        } else if (!process.waitFor((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)) {
            throw ProcessTimeoutException(safeCommand, timeoutMillis)
        }
        for (reader in readers) {
            if (deadline == null) {
                reader.join()
            } else {
                val remaining = deadline - System.nanoTime()
                if (remaining > 0) TimeUnit.NANOSECONDS.timedJoin(reader, remaining)
                if (reader.isAlive) throw ProcessTimeoutException(safeCommand, timeoutMillis)
            }
        }
        readerFailure.get()?.let {
            throw ProcessExecutionException(
                "Failed to read output for $safeCommand: ${it.message.orEmpty().sanitizeSecrets(sessionToken)}"
            )
        }
        val result = ProcessResult(safeCommand, process.exitValue(), stdout.toString(), stderr.toString())
        if (result.exitCode !in expectedExitCodes) {
            throw ProcessExitException(
                result.copy(
                    stdout = result.stdout.sanitizeSecrets(sessionToken),
                    stderr = result.stderr.sanitizeSecrets(sessionToken),
                ),
                expectedExitCodes,
            )
        }
        return if (stderrMode == ProcessStderrMode.DISCARD_ON_SUCCESS) result.copy(stderr = "") else result
    } catch (ex: InterruptedException) {
        Thread.currentThread().interrupt()
        // runInterruptible translates this to coroutine cancellation after cleanup.
        throw ex
    } finally {
        // Kill child commands too, so inherited output pipes cannot keep the readers alive.
        if (process.isAlive || readers.any { it.isAlive }) {
            process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
            process.destroyForcibly()
        }
        val interrupted = Thread.interrupted()
        try {
            readers.forEach { it.join(1000) }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}

private fun captureOutput(stream: InputStream, output: StringBuilder, onOutputLine: ((String) -> Unit)?) {
    stream.bufferedReader(Charsets.UTF_8).use { reader ->
        val buffer = CharArray(DEFAULT_BUFFER_SIZE)
        val line = StringBuilder()
        fun reportLine() {
            if (line.isNotEmpty()) {
                onOutputLine?.invoke(line.toString())
                line.setLength(0)
            }
        }
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            output.append(buffer, 0, count)
            if (onOutputLine != null) {
                for (i in 0 until count) {
                    when (val char = buffer[i]) {
                        '\r', '\n' -> reportLine()
                        else -> line.append(char)
                    }
                }
            }
        }
        reportLine()
    }
}
