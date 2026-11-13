package com.coder.toolbox.sdk

import com.coder.toolbox.sdk.interceptors.Interceptors
import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetSocketAddress
import java.net.ProtocolException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DeploymentRedirectTest {
    @Test
    fun `reads can follow a relative redirect but cannot leave their origin`() {
        val first = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val second = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val visits = AtomicInteger()
        val origin = "http://127.0.0.1:${first.address.port}"
        val http = OkHttpClient.Builder().followRedirects(false).addInterceptor(Interceptors.sameOriginRedirects()).build()
        first.createContext("/local") { exchange ->
            exchange.responseHeaders.add("Location", "/result")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        first.createContext("/result") { exchange ->
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        first.createContext("/away") { exchange ->
            exchange.responseHeaders.add("Location", "http://127.0.0.1:${second.address.port}/")
            exchange.sendResponseHeaders(307, -1)
            exchange.close()
        }
        second.createContext("/") { exchange ->
            visits.incrementAndGet()
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        try {
            first.start()
            second.start()
            val local = Request.Builder().url("$origin/local").build()
            http.newCall(local).execute().use { assertEquals(204, it.code) }
            http.newCall(local.newBuilder().post("".toRequestBody()).build()).execute().use { assertEquals(302, it.code) }
            val away = Request.Builder().url("$origin/away").build()
            assertFailsWith<ProtocolException> { http.newCall(away).execute().close() }
            assertEquals(0, visits.get())
        } finally {
            first.stop(0)
            second.stop(0)
            http.connectionPool.evictAll()
        }
    }
}
