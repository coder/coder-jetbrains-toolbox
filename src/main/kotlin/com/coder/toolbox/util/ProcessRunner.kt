package com.coder.toolbox.util

import java.io.IOException
import java.nio.charset.Charset
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
 * Runs a process and waits for it to finish.
 *
 * The wait is intentionally unbounded. Only exit code 0 is accepted by default.
 * Pass [expectedExitCodes] when a command has additional valid exit codes.
 *
 * Standard output is always captured and returned in [ProcessResult.stdout] while
 * standard error is captured by default and returned in [ProcessResult.stderr]. Use
 * [ProcessStderrMode.DISCARD_ON_SUCCESS] in order to ignore it.
 * Stderr is ignored for successful results, but preserved in [ProcessExitException] when the process fails.
 */
fun runProcess(
    command: List<String>,
    environment: Map<String, String> = emptyMap(),
    expectedExitCodes: IntRange = 0..0,
    stderrMode: ProcessStderrMode = ProcessStderrMode.CAPTURE,
    charset: Charset = Charsets.UTF_8,
): ProcessResult {
    val process =
        try {
            ProcessBuilder(command)
                .apply { environment().putAll(environment) }
                .start()
        } catch (ex: IOException) {
            throw ProcessExecutionException("Failed to start process $command: ${ex.message}", ex)
        }

    val stdout = StringBuilder()
    val stderr = StringBuilder()
    val stdoutReader = thread(start = true, name = "process-stdout-reader") {
        process.inputStream.bufferedReader(charset).use { stdout.append(it.readText()) }
    }
    val stderrReader = thread(start = true, name = "process-stderr-reader") {
        process.errorStream.bufferedReader(charset).use { stderr.append(it.readText()) }
    }

    val exitCode =
        try {
            process.waitFor()
        } catch (ex: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            throw ProcessExecutionException("Interrupted while waiting for process $command", ex)
        }

    try {
        stdoutReader.join()
        stderrReader.join()
    } catch (ex: InterruptedException) {
        Thread.currentThread().interrupt()
        throw ProcessExecutionException("Interrupted while reading process output for $command", ex)
    }

    val stderrText = stderr.toString()
    val result = ProcessResult(
        command = command,
        exitCode = exitCode,
        stdout = stdout.toString(),
        stderr = if (exitCode in expectedExitCodes && stderrMode == ProcessStderrMode.DISCARD_ON_SUCCESS) {
            ""
        } else {
            stderrText
        },
    )
    if (exitCode !in expectedExitCodes) {
        throw ProcessExitException(result, expectedExitCodes)
    }
    return result
}
