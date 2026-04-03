package com.coder.toolbox.util

import com.coder.toolbox.cli.ex.ResponseException
import com.coder.toolbox.sdk.ex.APIResponseException
import org.zeroturnaround.exec.InvalidExitValueException
import java.net.ConnectException
import java.net.UnknownHostException

private fun accessDenied(file: String) = "Access denied to $file"
private fun fileSystemFailed(file: String) = "A file system operation failed when trying to access $file"

fun Throwable.prettify(): String {
    val reason = this.message ?: "No reason was provided"
    return when (this) {
        is AccessDeniedException -> accessDenied(this.file.toString())
        is java.nio.file.AccessDeniedException -> accessDenied(this.file)
        is FileSystemException -> fileSystemFailed(this.file.toString())
        is java.nio.file.FileSystemException -> fileSystemFailed(this.file)
        is UnknownHostException -> "Unknown host $reason"
        is InvalidExitValueException -> "CLI exited unexpectedly with ${this.exitValue}."
        is APIResponseException -> {
            if (this.isUnauthorized) {
                "Authorization failed"
            } else {
                reason
            }
        }

        is ResponseException, is ConnectException -> "Failed to download Coder CLI: $reason"
        else -> reason
    }
}
