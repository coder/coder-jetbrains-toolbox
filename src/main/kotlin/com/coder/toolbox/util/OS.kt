package com.coder.toolbox.util

import java.util.*

fun getOS(): OS? = OS.from(System.getProperty("os.name"))

fun getArch(): Arch? = Arch.from(System.getProperty("os.arch").lowercase(Locale.getDefault()))

enum class OS {
    WINDOWS,
    LINUX,
    MAC;

    /**
     * The name of the current desktop environment.
     * For Linux systems it can be GNOME, KDE, XFCE, LXDE, and so on,
     * while for macOS it will be Aqua and Windows Shell for Windows.
     */
    fun getDesktopEnvironment(): String? =
        when (this) {
            WINDOWS -> "Windows Shell"
            MAC -> "Aqua"
            LINUX -> System.getenv("XDG_CURRENT_DESKTOP")
        }

    companion object {
        fun from(os: String): OS? = when {
            os.contains("win", true) -> {
                WINDOWS
            }

            os.contains("nix", true) || os.contains("nux", true) || os.contains("aix", true) -> {
                LINUX
            }

            os.contains("mac", true) || os.contains("darwin", true) -> {
                MAC
            }

            else -> null
        }
    }
}

enum class Arch {
    AMD64,
    ARM64,
    ARMV7,
    ;

    companion object {
        fun from(arch: String): Arch? = when {
            arch.contains("amd64", true) || arch.contains("x86_64", true) -> AMD64
            arch.contains("arm64", true) || arch.contains("aarch64", true) -> ARM64
            arch.contains("armv7", true) -> ARMV7
            else -> null
        }
    }
}
