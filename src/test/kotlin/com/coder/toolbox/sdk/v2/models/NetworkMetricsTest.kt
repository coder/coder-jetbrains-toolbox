package com.coder.toolbox.sdk.v2.models

import kotlin.test.Test
import kotlin.test.assertEquals

class NetworkMetricsTest {

    @Test
    fun `toPretty should return message for Coder Connect`() {
        val metrics = NetworkMetrics(
            p2p = null,
            latency = null,
            preferredDerp = null,
            derpLatency = null,
            uploadBytesSec = null,
            downloadBytesSec = null,
            usingCoderConnect = true
        )

        val expected = "You're connected using Coder Connect"
        assertEquals(expected, metrics.toPretty())
    }

    @Test
    fun `toPretty should return message for P2P connection`() {
        val metrics = NetworkMetrics(
            p2p = true,
            latency = 35.526,
            preferredDerp = null,
            derpLatency = null,
            uploadBytesSec = null,
            downloadBytesSec = null,
            usingCoderConnect = false
        )

        val expected = "Direct (35.53ms). You're connected peer-to-peer"
        assertEquals(expected, metrics.toPretty())
    }

    @Test
    fun `toPretty should round latency with more than two decimals correctly for P2P`() {
        val metrics = NetworkMetrics(
            p2p = true,
            latency = 42.6789,
            preferredDerp = null,
            derpLatency = null,
            uploadBytesSec = null,
            downloadBytesSec = null,
            usingCoderConnect = false
        )

        val expected = "Direct (42.68ms). You're connected peer-to-peer"
        assertEquals(expected, metrics.toPretty())
    }

    @Test
    fun `toPretty should pad latency with one decimal correctly for P2P`() {
        val metrics = NetworkMetrics(
            p2p = true,
            latency = 12.5,
            preferredDerp = null,
            derpLatency = null,
            uploadBytesSec = null,
            downloadBytesSec = null,
            usingCoderConnect = false
        )

        val expected = "Direct (12.50ms). You're connected peer-to-peer"
        assertEquals(expected, metrics.toPretty())
    }

    @Test
    fun `toPretty should return message for DERP relay connection`() {
        val metrics = NetworkMetrics(
            p2p = false,
            latency = 80.0,
            preferredDerp = "derp1",
            derpLatency = mapOf("derp1" to 30.0),
            uploadBytesSec = null,
            downloadBytesSec = null,
            usingCoderConnect = false
        )

        val expected = "You ↔ derp1 (30.00ms) ↔ Workspace (50.00ms). You are connected through a relay"
        assertEquals(expected, metrics.toPretty())
    }

    @Test
    fun `toPretty should round and pad latencies correctly for DERP`() {
        val metrics = NetworkMetrics(
            p2p = false,
            latency = 78.1267,
            preferredDerp = "derp2",
            derpLatency = mapOf("derp2" to 23.5),
            uploadBytesSec = null,
            downloadBytesSec = null,
            usingCoderConnect = false
        )

        // Total latency: 78.1267
        // DERP latency: 23.5 → formatted as 23.50
        // Workspace latency: 78.1267 - 23.5 = 54.6267 → formatted as 54.63

        val expected = "You ↔ derp2 (23.50ms) ↔ Workspace (54.63ms). You are connected through a relay"
        assertEquals(expected, metrics.toPretty())
    }
}