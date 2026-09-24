package com.edge2.remote.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Serveur de partage réel (127.0.0.1) + vrai client WebSocket. */
class RemoteServerTest {

    private val port = 18787 + (System.nanoTime() % 1000).toInt()
    private val commands = CopyOnWriteArrayList<RemoteCommand>()
    private val lockouts = AtomicInteger()
    private lateinit var server: RemoteServer
    private val client = HttpClient(CIO) { install(WebSockets) }

    private val html = "<html><script>var x = 1;</script></html>"

    @Before fun setUp() {
        server = RemoteServer(
            loadHtml = { html },
            loadFont = { ByteArray(4) },
            onCommand = { _, c -> commands += c },
            onLockout = { lockouts.incrementAndGet() },
            port = port,
        )
        assertTrue(server.start(lanIp = null))
    }

    @After fun tearDown() {
        server.stop()
        client.close()
    }

    private val base get() = "http://127.0.0.1:$port"
    private val ws get() = "ws://127.0.0.1:$port/ws/${server.sessionId}"

    private suspend fun DefaultClientWebSocketSession.next(ms: Long = 3_000): String? =
        withTimeoutOrNull(ms) { (incoming.receiveCatching().getOrNull() as? Frame.Text)?.readText() }

    @Test fun pageOnlyForCurrentSessionWithSecurityHeaders() = runBlocking {
        val bad: HttpResponse = client.get("$base/s/nope")
        assertEquals(HttpStatusCode.NotFound, bad.status)
        assertEquals(HttpStatusCode.NotFound, client.get("$base/").status)
        assertEquals(HttpStatusCode.NotFound, client.get("$base/font/..%2F..%2Fsecret").status)
        val ok = client.get("$base/s/${server.sessionId}")
        assertEquals(HttpStatusCode.OK, ok.status)
        val csp = ok.headers["Content-Security-Policy"]!!
        assertTrue(csp.contains(ShareSecurity.cspHash("var x = 1;")))
        assertTrue(csp.contains("frame-ancestors 'none'"))
        assertEquals("no-referrer", ok.headers["Referrer-Policy"])
        assertEquals("DENY", ok.headers["X-Frame-Options"])
    }

    @Test fun secondServerOnSamePortFailsGracefully() {
        val other = RemoteServer({ html }, { ByteArray(0) }, { _, _ -> }, {}, port)
        assertFalse(other.start(lanIp = null))
    }

    @Test fun wrongPinIsDeniedAndFiveFailuresLockOut() = runBlocking {
        repeat(5) {
            client.webSocket(ws) {
                send(Frame.Text("AUTH:000000".takeIf { server.pin != "000000" } ?: "AUTH:111111"))
                assertEquals("DENIED", next())
            }
        }
        withTimeout(3_000) { while (lockouts.get() == 0) delay(20) }
        assertTrue(lockouts.get() >= 1)
        assertTrue(server.controllers.value.isEmpty())
    }

    @Test fun crossSiteOriginIsRejected() = runBlocking {
        client.webSocket(ws, request = { header("Origin", "https://evil.example") }) {
            assertNull(next(1_500)) // fermé sans réponse
        }
        assertTrue(server.controllers.value.isEmpty())
    }

    @Test fun commandsIgnoredUntilHostApprovesThenResumeToken() = runBlocking {
        var token: String? = null
        client.webSocket(ws) {
            send(Frame.Text("AUTH:${server.pin}"))
            assertTrue(next()!!.startsWith("STATE:{\"approved\":false"))
            send(Frame.Text("B:10"))
            delay(200)
            assertTrue("aucune commande avant accord", commands.isEmpty())

            val id = server.controllers.value.single().id
            server.approve(id)
            token = next()?.removePrefix("TOKEN:")
            assertNotNull(token)
            assertTrue(next()!!.startsWith("STATE:{\"approved\":true"))
            send(Frame.Text("@0:M2:14"))
            withTimeout(2_000) { while (commands.isEmpty()) delay(20) }
            assertEquals(RemoteCommand.SetMotor(2, 14, target = 0), commands.single())
        }
        // Reconnexion avec le jeton : accepté d'office (coupure réseau).
        client.webSocket(ws) {
            send(Frame.Text("AUTH:${server.pin}:$token"))
            assertTrue(next()!!.startsWith("STATE:{\"approved\":true"))
        }
        // Jeton inventé : retour en attente.
        client.webSocket(ws) {
            send(Frame.Text("AUTH:${server.pin}:forged"))
            assertTrue(next()!!.startsWith("STATE:{\"approved\":false"))
        }
    }

    @Test fun hostCanKickOneController() = runBlocking {
        client.webSocket(ws) {
            send(Frame.Text("AUTH:${server.pin}"))
            next()
            server.kick(server.controllers.value.single().id)
            assertEquals("DENIED", next())
        }
    }

    @Test fun staleSessionLinkIsRejected() = runBlocking {
        val old = ws
        val oldPin = server.pin
        server.stop()
        assertTrue(server.start(lanIp = null))
        client.webSocket(old) {
            send(Frame.Text("AUTH:$oldPin"))
            assertNull(next(1_500))
        }
    }
}
