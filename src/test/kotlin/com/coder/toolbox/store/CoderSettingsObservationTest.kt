package com.coder.toolbox.store

import com.coder.toolbox.settings.Environment
import com.coder.toolbox.settings.ReadOnlyTLSSettings
import com.coder.toolbox.util.pluginTestSettingsStore
import com.jetbrains.toolbox.api.core.PluginSettingsStore
import com.jetbrains.toolbox.api.core.diagnostics.Logger
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CoderSettingsObservationTest {
    @Test
    fun `observation starts with the effective persisted buffer size`() = runTest {
        listOf("2500" to 2500, "0" to 0, "10000" to 10000, "invalid" to 1000, "10001" to 1000)
            .forEach { (raw, expected) ->
                val settings = settings(pluginTestSettingsStore(CONNECTION_LOG_BUFFER_SIZE to raw)).readOnly()

                assertEquals(expected, settings.observe { it.connectionLogBufferSize }.first())
            }
        assertEquals(1000, settings().readOnly().observe { it.connectionLogBufferSize }.first())
    }

    @Test
    fun `observation reads the current value when collection starts`() = runTest {
        val settings = settings()
        val sizes = settings.readOnly().observe { it.connectionLogBufferSize }

        settings.updateConnectionLogBufferSize(2500)

        assertEquals(2500, sizes.first())
    }

    @Test
    fun `buffer updates are delivered after persistence`() = runTest {
        val backingStore = pluginTestSettingsStore()
        val settings = settings(backingStore)
        val observed = mutableListOf<Pair<Int, String?>>()
        settings.readOnly().observe { it.connectionLogBufferSize }
            .onEach { observed += it to backingStore[CONNECTION_LOG_BUFFER_SIZE] }
            .launchIn(backgroundScope)
        runCurrent()

        settings.updateConnectionLogBufferSize(0)
        runCurrent()
        settings.updateConnectionLogBufferSize(10000)
        runCurrent()

        assertEquals(listOf(1000 to null, 0 to "0", 10000 to "10000"), observed)
    }

    @Test
    fun `unchanged effective values and unrelated settings do not emit`() = runTest {
        val settings = settings(pluginTestSettingsStore(CONNECTION_LOG_BUFFER_SIZE to "invalid"))
        val observed = mutableListOf<Int>()
        settings.observe { it.connectionLogBufferSize }.onEach { observed += it }.launchIn(backgroundScope)
        runCurrent()

        settings.updateConnectionLogBufferSize(1000)
        runCurrent()
        settings.updateHeaderCommand("new header command")
        runCurrent()
        settings.updateConnectionLogBufferSize(1000)
        runCurrent()

        assertEquals(listOf(1000), observed)
    }

    @Test
    fun `rejected buffer sizes do not invalidate observers`() = runTest {
        val settings = settings()
        var reads = 0
        val observed = mutableListOf<Int>()
        settings.observe {
            reads++
            it.connectionLogBufferSize
        }
            .onEach { observed += it }
            .launchIn(backgroundScope)
        runCurrent()

        assertFailsWith<IllegalArgumentException> { settings.updateConnectionLogBufferSize(-1) }
        assertFailsWith<IllegalArgumentException> { settings.updateConnectionLogBufferSize(10001) }
        runCurrent()

        assertEquals(1, reads)
        assertEquals(listOf(1000), observed)
    }

    @Test
    fun `failed persistence does not invalidate observers and a later successful write does`() = runTest {
        val persisted = pluginTestSettingsStore()
        var failWrites = true
        val backingStore = object : PluginSettingsStore by persisted {
            override fun put(key: String, value: String): String? {
                if (failWrites) throw IOException("Cannot persist settings")
                return persisted.put(key, value)
            }
        }
        val settings = settings(backingStore)
        var reads = 0
        val observed = mutableListOf<Int>()
        settings.observe {
            reads++
            it.connectionLogBufferSize
        }
            .onEach { observed += it }
            .launchIn(backgroundScope)
        runCurrent()

        assertFailsWith<IOException> { settings.updateConnectionLogBufferSize(2500) }
        runCurrent()
        assertEquals(1, reads)
        assertEquals(1000, settings.connectionLogBufferSize)

        failWrites = false
        settings.updateConnectionLogBufferSize(2500)
        runCurrent()

        assertEquals(listOf(1000, 2500), observed)
    }

    @Test
    fun `subscribers receive the current value and can unsubscribe independently`() = runTest {
        val settings = settings()
        val sizes = settings.readOnly().observe { it.connectionLogBufferSize }
        val first = mutableListOf<Int>()
        val second = mutableListOf<Int>()
        val subscription = sizes.onEach { first += it }.launchIn(backgroundScope)
        runCurrent()
        settings.updateConnectionLogBufferSize(2500)
        runCurrent()

        sizes.onEach { second += it }.launchIn(backgroundScope)
        runCurrent()
        settings.updateConnectionLogBufferSize(0)
        runCurrent()
        subscription.cancelAndJoin()
        settings.updateConnectionLogBufferSize(10000)
        runCurrent()

        assertEquals(listOf(1000, 2500, 0), first)
        assertEquals(listOf(2500, 0, 10000), second)
    }

    @Test
    fun `slow subscribers receive the latest size without blocking writes`() = runTest {
        val settings = settings()
        val release = CompletableDeferred<Unit>()
        val observed = mutableListOf<Int>()
        settings.observe { it.connectionLogBufferSize }.onEach {
            observed += it
            if (it == 1000) release.await()
        }.launchIn(backgroundScope)
        runCurrent()

        settings.updateConnectionLogBufferSize(0)
        settings.updateConnectionLogBufferSize(10000)
        settings.updateConnectionLogBufferSize(2500)
        runCurrent()
        assertEquals(listOf(1000), observed)

        release.complete(Unit)
        runCurrent()

        assertEquals(listOf(1000, 2500), observed)
    }

    @Test
    fun `TLS selections compare their values rather than their object identities`() = runTest {
        val settings = settings()
        val observed = mutableListOf<ReadOnlyTLSSettings>()
        settings.readOnly().observe { it.tls }.onEach { observed += it }.launchIn(backgroundScope)
        runCurrent()

        settings.updateConnectionLogBufferSize(0)
        runCurrent()
        settings.updateCertPath("cert.pem")
        runCurrent()
        settings.updateCertPath("cert.pem")
        runCurrent()
        settings.updateKeyPath("key.pem")
        runCurrent()

        assertEquals(
            listOf(null to null, "cert.pem" to null, "cert.pem" to "key.pem"),
            observed.map { it.certPath to it.keyPath }
        )
    }

    @Test
    fun `selectors can observe several settings as one immutable value`() = runTest {
        val settings = settings()
        val observed = mutableListOf<Pair<Int, Boolean>>()
        settings.readOnly().observe { it.connectionLogBufferSize to it.useKeyring }
            .onEach { observed += it }
            .launchIn(backgroundScope)
        runCurrent()

        settings.updateUseKeyring(false)
        runCurrent()
        settings.updateConnectionLogBufferSize(2500)
        runCurrent()
        settings.updateUseKeyring(false)
        runCurrent()

        assertEquals(listOf(1000 to true, 1000 to false, 2500 to false), observed)
    }

    @Test
    fun `workspace setting changes notify only affected selections`() = runTest {
        val settings = settings()
        val observed = mutableListOf<Boolean>()
        settings.readOnly().observe { it.shouldAutoConnect("workspace") }
            .onEach { observed += it }
            .launchIn(backgroundScope)
        runCurrent()

        settings.updateAutoConnect("other-workspace", true)
        runCurrent()
        settings.updateAutoConnect("workspace", true)
        runCurrent()

        assertEquals(listOf(false, true), observed)
    }

    @Test
    fun `concurrent writes leave observers consistent with persisted settings`() = runTest {
        val backingStore = pluginTestSettingsStore()
        val settings = settings(backingStore)
        val observed = mutableListOf<Pair<Int, Int>>()
        settings.observe { it.connectionLogBufferSize to it.connectionLogBufferSize }
            .onEach { observed += it }
            .launchIn(backgroundScope)
        runCurrent()

        withContext(Dispatchers.Default) {
            coroutineScope {
                repeat(100) { size -> launch { settings.updateConnectionLogBufferSize(size) } }
            }
        }
        runCurrent()

        assertTrue(observed.all { (first, second) -> first == second })
        val persisted = backingStore[CONNECTION_LOG_BUFFER_SIZE]?.toInt()
        assertEquals(persisted to persisted, observed.last())
    }

    private fun settings(backingStore: PluginSettingsStore = pluginTestSettingsStore()): CoderSettingsStore =
        CoderSettingsStore(backingStore, Environment(), mockk<Logger>(relaxed = true))
}
