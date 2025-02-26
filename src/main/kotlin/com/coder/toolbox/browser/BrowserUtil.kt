package com.coder.toolbox.browser

import com.coder.toolbox.util.OS
import com.coder.toolbox.util.getOS
import org.zeroturnaround.exec.ProcessExecutor

class BrowserUtil {
    companion object {
        fun browse(url: String, errorHandler: (BrowserException) -> Unit) {
            val os = getOS()
            if (os == null) {
                errorHandler(BrowserException("Failed to open the URL because we can't detect the OS"))
                return
            }
            when (os) {
                OS.LINUX -> linuxBrowse(url, errorHandler)
                OS.MAC -> macBrowse(url, errorHandler)
                OS.WINDOWS -> windowsBrowse(url, errorHandler)
            }
        }

        private fun linuxBrowse(url: String, errorHandler: (BrowserException) -> Unit) {
            try {
                if (OS.LINUX.getDesktopEnvironment()?.uppercase()?.contains("GNOME") == true) {
                    exec("gnome-open", url)
                } else {
                    exec("xdg-open", url)
                }
            } catch (e: Exception) {
                errorHandler(
                    BrowserException(
                        "Failed to open URL because an error was encountered. Please make sure xdg-open from package xdg-utils is available!",
                        e
                    )
                )
            }
        }

        private fun macBrowse(url: String, errorHandler: (BrowserException) -> Unit) {
            try {
                exec("open", url)
            } catch (e: Exception) {
                errorHandler(BrowserException("Failed to open URL because an error was encountered.", e))
            }
        }

        private fun windowsBrowse(url: String, errorHandler: (BrowserException) -> Unit) {
            try {
                exec("cmd", "start \"$url\"")
            } catch (e: Exception) {
                errorHandler(BrowserException("Failed to open URL because an error was encountered.", e))
            }
        }

        private fun exec(vararg args: String): String {
            val stdout =
                ProcessExecutor()
                    .command(*args)
                    .exitValues(0)
                    .readOutput(true)
                    .execute()
                    .outputUTF8()
            return stdout
        }
    }
}