package com.coder.toolbox.settings

import com.coder.toolbox.store.BINARY_NAME
import com.coder.toolbox.store.CODER_SSH_CONFIG_OPTIONS
import com.coder.toolbox.store.CoderSettingsStore
import com.coder.toolbox.store.DEFAULT_URL
import com.coder.toolbox.store.DISABLE_AUTOSTART
import com.coder.toolbox.store.ENABLE_BINARY_DIR_FALLBACK
import com.coder.toolbox.store.ENABLE_DOWNLOADS
import com.coder.toolbox.store.HEADER_COMMAND
import com.coder.toolbox.store.SSH_CONFIG_OPTIONS
import com.coder.toolbox.store.SSH_LOG_DIR
import com.coder.toolbox.store.TLS_ALTERNATE_HOSTNAME
import com.coder.toolbox.store.TLS_CA_PATH
import com.coder.toolbox.store.TLS_CERT_PATH
import com.coder.toolbox.store.TLS_KEY_PATH
import com.coder.toolbox.util.OS
import com.coder.toolbox.util.getOS
import com.coder.toolbox.util.pluginTestSettingsStore
import com.coder.toolbox.util.withPath
import com.jetbrains.toolbox.api.core.diagnostics.Logger
import io.mockk.mockk
import java.net.URL
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

internal class CoderSettingsTest {
    private val logger = mockk<Logger>(relaxed = true)

    @Test
    fun testExpands() {
        val settings = CoderSettingsStore(pluginTestSettingsStore(), Environment(), logger)
        val url = URL("http://localhost")
        val home = Path.of(System.getProperty("user.home"))

        settings.updateBinaryDirectory(Path.of("~/coder-toolbox-test/expand-bin-dir").toString())
        var expected = home.resolve("coder-toolbox-test/expand-bin-dir/localhost")
        assertEquals(expected.toAbsolutePath(), settings.readOnly().binPath(url).parent)

        settings.updateDataDirectory(Path.of("~/coder-toolbox-test/expand-data-dir").toString())
        expected = home.resolve("coder-toolbox-test/expand-data-dir/localhost")
        assertEquals(expected.toAbsolutePath(), settings.readOnly().dataDir(url))
    }

    @Test
    fun testDataDir() {
        val sharedStore = pluginTestSettingsStore()
        val url = URL("http://localhost")
        var settings = CoderSettingsStore(
            sharedStore,
            Environment(
                mapOf(
                    "LOCALAPPDATA" to "/tmp/coder-toolbox-test/localappdata",
                    "HOME" to "/tmp/coder-toolbox-test/home",
                    "XDG_DATA_HOME" to "/tmp/coder-toolbox-test/xdg-data",
                ),
            ),
            logger,
        )
        var expected =
            when (getOS()) {
                OS.WINDOWS -> "/tmp/coder-toolbox-test/localappdata/coder-toolbox/localhost"
                OS.MAC -> "/tmp/coder-toolbox-test/home/Library/Application Support/coder-toolbox/localhost"
                else -> "/tmp/coder-toolbox-test/xdg-data/coder-toolbox/localhost"
            }

        assertEquals(Path.of(expected).toAbsolutePath(), settings.readOnly().dataDir(url))
        assertEquals(Path.of(expected).toAbsolutePath(), settings.readOnly().binPath(url).parent)

        // Fall back to HOME on Linux.
        if (getOS() == OS.LINUX) {
            settings = CoderSettingsStore(
                sharedStore,
                Environment(
                    mapOf(
                        "XDG_DATA_HOME" to "",
                        "HOME" to "/tmp/coder-toolbox-test/home",
                    ),
                ),
                logger,
            )
            expected = "/tmp/coder-toolbox-test/home/.local/share/coder-toolbox/localhost"

            assertEquals(Path.of(expected).toAbsolutePath(), settings.readOnly().dataDir(url))
            assertEquals(Path.of(expected).toAbsolutePath(), settings.readOnly().binPath(url).parent)
        }

        // Override environment with settings.
        settings.updateDataDirectory("/tmp/coder-toolbox-test/data-dir")
        settings = CoderSettingsStore(
            sharedStore,
            Environment(
                mapOf(
                    "LOCALAPPDATA" to "/ignore",
                    "HOME" to "/ignore",
                    "XDG_DATA_HOME" to "/ignore",
                ),
            ),
            logger,
        )
        expected = "/tmp/coder-toolbox-test/data-dir/localhost"
        assertEquals(Path.of(expected).toAbsolutePath(), settings.readOnly().dataDir(url))
        assertEquals(Path.of(expected).toAbsolutePath(), settings.readOnly().binPath(url).parent)

        // Check that the URL is encoded and includes the port, also omit environment.
        val newUrl = URL("https://dev.😉-coder.com:8080")
        settings.updateDataDirectory("/tmp/coder-toolbox-test/data-dir")
        settings = CoderSettingsStore(sharedStore, Environment(), logger)
        expected = "/tmp/coder-toolbox-test/data-dir/dev.xn---coder-vx74e.com-8080"
        assertEquals(Path.of(expected).toAbsolutePath(), settings.readOnly().dataDir(newUrl))
        assertEquals(Path.of(expected).toAbsolutePath(), settings.readOnly().binPath(newUrl).parent)
    }

    fun testBinPath() {
        val settings = CoderSettingsStore(
            pluginTestSettingsStore(
                BINARY_NAME to "foo-bar.baz"
            ), Environment(), logger
        )
        // The binary path should fall back to the data directory but that is
        // already tested in the data directory tests.
        val url = URL("http://localhost")

        // Override with settings.
        settings.updateBinaryDirectory("/tmp/coder-toolbox-test/bin-dir")
        var expected = "/tmp/coder-toolbox-test/bin-dir/localhost"
        assertEquals(Path.of(expected).toAbsolutePath(), settings.readOnly().binPath(url).parent)

        // Second argument bypasses override.
        settings.updateDataDirectory("/tmp/coder-toolbox-test/data-dir")
        expected = "/tmp/coder-toolbox-test/data-dir/localhost"
        assertEquals(Path.of(expected).toAbsolutePath(), settings.readOnly().binPath(url, true).parent)

        assertNotEquals("foo-bar.baz", settings.readOnly().binPath(url).fileName.toString())
    }

    @Test
    fun testCoderConfigDir() {
        val localStore = pluginTestSettingsStore()
        var settings = CoderSettingsStore(
            localStore,
            env =
                Environment(
                    mapOf(
                        "APPDATA" to "/tmp/coder-toolbox-test/cli-appdata",
                        "HOME" to "/tmp/coder-toolbox-test/cli-home",
                        "XDG_CONFIG_HOME" to "/tmp/coder-toolbox-test/cli-xdg-config",
                    ),
                ),
            logger,
        )
        var expected =
            when (getOS()) {
                OS.WINDOWS -> "/tmp/coder-toolbox-test/cli-appdata/coderv2"
                OS.MAC -> "/tmp/coder-toolbox-test/cli-home/Library/Application Support/coderv2"
                else -> "/tmp/coder-toolbox-test/cli-xdg-config/coderv2"
            }
        assertEquals(expected, settings.readOnly().globalConfigDir)

        // Fall back to HOME on Linux.
        if (getOS() == OS.LINUX) {
            settings = CoderSettingsStore(
                localStore,
                env =
                    Environment(
                        mapOf(
                            "XDG_CONFIG_HOME" to "",
                            "HOME" to "/tmp/coder-toolbox-test/cli-home",
                        ),
                    ),
                logger
            )
            expected = "/tmp/coder-toolbox-test/cli-home/.config/coderv2"
            assertEquals(expected, settings.readOnly().globalConfigDir)
        }

        // Read CODER_CONFIG_DIR.
        settings =
            CoderSettingsStore(
                localStore,
                env =
                    Environment(
                        mapOf(
                            "CODER_CONFIG_DIR" to "/tmp/coder-toolbox-test/coder-config-dir",
                            "APPDATA" to "/ignore",
                            "HOME" to "/ignore",
                            "XDG_CONFIG_HOME" to "/ignore",
                        ),
                    ),
                logger
            )
        expected = "/tmp/coder-toolbox-test/coder-config-dir"
        assertEquals(expected, settings.readOnly().globalConfigDir)
    }

    @Test
    fun binSource() {
        val localStore = pluginTestSettingsStore()
        val settings = CoderSettingsStore(localStore, Environment(), logger)
        // As-is if no source override.
        val url = URL("http://localhost/")
        assertContains(
            settings.readOnly().binSource(url).toString(),
            url.withPath("/bin/coder-").toString(),
        )

        // Override with absolute URL.
        val absolute = URL("http://dev.coder.com/some-path")
        settings.updateBinarySource(absolute.toString())
        assertEquals(absolute, settings.readOnly().binSource(url))

        // Override with relative URL.
        settings.updateBinarySource("/relative/path")
        assertEquals(url.withPath("/relative/path"), settings.readOnly().binSource(url))
    }

    @Test
    fun testReadConfig() {
        val tmp = Path.of(System.getProperty("java.io.tmpdir"))

        val expected = tmp.resolve("coder-toolbox-test/test-config")
        expected.toFile().mkdirs()
        expected.resolve("url").toFile().writeText("http://test.toolbox.coder.com$expected")
        expected.resolve("session").toFile().writeText("fake-token")

        var got = CoderSettingsStore(pluginTestSettingsStore(), Environment(), logger).readOnly().readConfig(expected)
        assertEquals(Pair("http://test.toolbox.coder.com$expected", "fake-token"), got)

        // Ignore token if missing.
        expected.resolve("session").toFile().delete()
        got = CoderSettingsStore(pluginTestSettingsStore(), Environment(), logger).readOnly().readConfig(expected)
        assertEquals(Pair("http://test.toolbox.coder.com$expected", null), got)
    }

    @Test
    fun testSSHConfigOptions() {
        var settings = CoderSettingsStore(
            pluginTestSettingsStore(SSH_CONFIG_OPTIONS to "ssh config options from state"),
            Environment(), logger
        )
        assertEquals("ssh config options from state", settings.readOnly().sshConfigOptions)

        settings = CoderSettingsStore(
            pluginTestSettingsStore(),
            env = Environment(mapOf(CODER_SSH_CONFIG_OPTIONS to "ssh config options from env")),
            logger
        )
        assertEquals("ssh config options from env", settings.readOnly().sshConfigOptions)

        // State has precedence.
        settings = CoderSettingsStore(
            pluginTestSettingsStore(SSH_CONFIG_OPTIONS to "ssh config options from state"),
            env = Environment(mapOf(CODER_SSH_CONFIG_OPTIONS to "ssh config options from env")),
            logger
        )
        assertEquals("ssh config options from state", settings.readOnly().sshConfigOptions)
    }

    @Test
    fun testRequireTokenAuth() {
        var settings = CoderSettingsStore(pluginTestSettingsStore(), Environment(), logger)
        assertEquals(true, settings.readOnly().requireTokenAuth)

        settings = CoderSettingsStore(pluginTestSettingsStore(TLS_CERT_PATH to "cert path"), Environment(), logger)
        assertEquals(true, settings.readOnly().requireTokenAuth)

        settings = CoderSettingsStore(pluginTestSettingsStore(TLS_KEY_PATH to "key path"), Environment(), logger)
        assertEquals(true, settings.readOnly().requireTokenAuth)

        settings = CoderSettingsStore(
            pluginTestSettingsStore(TLS_CERT_PATH to "cert path", TLS_KEY_PATH to "key path"),
            Environment(), logger
        )
        assertEquals(false, settings.readOnly().requireTokenAuth)
    }

    @Test
    fun testDefaultURL() {
        val tmp = Path.of(System.getProperty("java.io.tmpdir"))
        val dir = tmp.resolve("coder-toolbox-test/test-default-url")
        var env = Environment(mapOf("CODER_CONFIG_DIR" to dir.toString()))
        dir.toFile().deleteRecursively()

        // No config.
        var settings = CoderSettingsStore(pluginTestSettingsStore(), env, logger)
        assertEquals(null, settings.defaultURL())

        // Read from global config.
        val globalConfigPath = Path.of(settings.readOnly().globalConfigDir)
        globalConfigPath.toFile().mkdirs()
        globalConfigPath.resolve("url").toFile().writeText("url-from-global-config")
        settings = CoderSettingsStore(pluginTestSettingsStore(), env, logger)
        assertEquals("url-from-global-config" to SettingSource.CONFIG, settings.defaultURL())

        // Read from environment.
        env =
            Environment(
                mapOf(
                    "CODER_URL" to "url-from-env",
                    "CODER_CONFIG_DIR" to dir.toString(),
                ),
            )
        settings = CoderSettingsStore(pluginTestSettingsStore(), env, logger)
        assertEquals("url-from-env" to SettingSource.ENVIRONMENT, settings.defaultURL())

        // Read from settings.
        settings =
            CoderSettingsStore(
                pluginTestSettingsStore(
                    DEFAULT_URL to "url-from-settings",
                ),
                env,
                logger
            )
        assertEquals("url-from-settings" to SettingSource.SETTINGS, settings.defaultURL())
    }

    @Test
    fun testToken() {
        val tmp = Path.of(System.getProperty("java.io.tmpdir"))
        val url = URL("http://test.deployment.coder.com")
        val dir = tmp.resolve("coder-toolbox-test/test-default-token")
        val env =
            Environment(
                mapOf(
                    "CODER_CONFIG_DIR" to dir.toString(),
                    "LOCALAPPDATA" to dir.toString(),
                    "XDG_DATA_HOME" to dir.toString(),
                    "HOME" to dir.toString(),
                ),
            )
        dir.toFile().deleteRecursively()

        // No config.
        var settings = CoderSettingsStore(pluginTestSettingsStore(), env, logger)
        assertEquals(null, settings.readOnly().token(url))

        val globalConfigPath = Path.of(settings.readOnly().globalConfigDir)
        globalConfigPath.toFile().mkdirs()
        globalConfigPath.resolve("url").toFile().writeText(url.toString())
        globalConfigPath.resolve("session").toFile().writeText("token-from-global-config")

        // Ignore global config if it does not match.
        assertEquals(null, settings.readOnly().token(URL("http://some.random.url")))

        // Read from global config.
        assertEquals("token-from-global-config" to SettingSource.CONFIG, settings.readOnly().token(url))

        // Compares exactly.
        assertEquals(null, settings.readOnly().token(url.withPath("/test")))

        val deploymentConfigPath = settings.readOnly().dataDir(url).resolve("config")
        deploymentConfigPath.toFile().mkdirs()
        deploymentConfigPath.resolve("url").toFile().writeText("url-from-deployment-config")
        deploymentConfigPath.resolve("session").toFile().writeText("token-from-deployment-config")

        // Read from deployment config.
        assertEquals("token-from-deployment-config" to SettingSource.DEPLOYMENT_CONFIG, settings.readOnly().token(url))

        // Only compares host .
        assertEquals(
            "token-from-deployment-config" to SettingSource.DEPLOYMENT_CONFIG,
            settings.readOnly().token(url.withPath("/test"))
        )

        // Ignore if using mTLS.
        settings =
            CoderSettingsStore(
                pluginTestSettingsStore(
                    TLS_KEY_PATH to "key",
                    TLS_CERT_PATH to "cert",
                ),
                env,
                logger
            )
        assertEquals(null, settings.readOnly().token(url))
    }

    @Test
    fun testDefaults() {
        // Test defaults for the remaining settings.
        val settings = CoderSettingsStore(pluginTestSettingsStore(), Environment(), logger)
        assertEquals(true, settings.readOnly().enableDownloads)
        assertEquals(false, settings.readOnly().enableBinaryDirectoryFallback)
        assertEquals(null, settings.readOnly().headerCommand)
        assertEquals(null, settings.readOnly().tls.certPath)
        assertEquals(null, settings.readOnly().tls.keyPath)
        assertEquals(null, settings.readOnly().tls.caPath)
        assertEquals(null, settings.readOnly().tls.altHostname)
        assertEquals(getOS() == OS.MAC, settings.readOnly().disableAutostart)
    }

    @Test
    fun testSettings() {
        // Make sure the remaining settings are being conveyed.
        val settings =
            CoderSettingsStore(
                pluginTestSettingsStore(
                    ENABLE_DOWNLOADS to false.toString(),
                    ENABLE_BINARY_DIR_FALLBACK to true.toString(),
                    HEADER_COMMAND to "test header",
                    TLS_CERT_PATH to "tls cert path",
                    TLS_KEY_PATH to "tls key path",
                    TLS_CA_PATH to "tls ca path",
                    TLS_ALTERNATE_HOSTNAME to "tls alt hostname",
                    DISABLE_AUTOSTART to (getOS() != OS.MAC).toString(),
                    SSH_LOG_DIR to "test ssh log directory",
                ),
                Environment(),
                logger,
            )

        assertEquals(false, settings.readOnly().enableDownloads)
        assertEquals(true, settings.readOnly().enableBinaryDirectoryFallback)
        assertEquals("test header", settings.readOnly().headerCommand)
        assertEquals("tls cert path", settings.readOnly().tls.certPath)
        assertEquals("tls key path", settings.readOnly().tls.keyPath)
        assertEquals("tls ca path", settings.readOnly().tls.caPath)
        assertEquals("tls alt hostname", settings.readOnly().tls.altHostname)
        assertEquals(getOS() != OS.MAC, settings.readOnly().disableAutostart)
        assertEquals("test ssh log directory", settings.readOnly().sshLogDirectory)
    }
}
