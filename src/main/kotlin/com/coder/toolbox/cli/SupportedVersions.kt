package com.coder.toolbox.cli

import com.coder.toolbox.util.InvalidVersionException
import com.coder.toolbox.util.SemVer

/**
 * The oldest Coder server and CLI release the plugin supports.
 *
 * Every CLI flag the plugin relies on is available from this version on, so
 * no per-feature version gating is needed. The server and CLI versions are
 * checked independently: a mismatched CLI is allowed as long as both meet the
 * minimum.
 */
object SupportedVersions {
    private val MINIMUM_VERSION = SemVer(2, 29, 0)
    private const val MINIMUM_VERSION_LABEL = "v2.29.0"

    // Unversioned development builds of Coder report v0.0.0-devel.
    private val DEVELOPMENT_VERSION = SemVer()

    fun requireSupportedCli(version: SemVer) = requireSupported(version, "CLI")

    fun requireSupportedServer(rawVersion: String) {
        val version = try {
            SemVer.parse(rawVersion)
        } catch (_: InvalidVersionException) {
            error("Unable to determine your Coder server version. Coder $MINIMUM_VERSION_LABEL or newer is required.")
        }
        requireSupported(version, "server")
    }

    private fun requireSupported(version: SemVer, component: String) {
        check(version == DEVELOPMENT_VERSION || version >= MINIMUM_VERSION) {
            "Your Coder $component is too old. Please upgrade to $MINIMUM_VERSION_LABEL or newer."
        }
    }
}
