package com.edge2.remote.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Real sharing server (127.0.0.1) + real WebSocket client. */
class RemoteServerTest {

    private val port = 18787 + (System.nanoTime() % 1000).toInt()
    private val commands = CopyOnWriteArrayList<RemoteCommand>()
    private val lockouts = AtomicInteger()
    private val gone = AtomicInteger()
    private lateinit var server: RemoteServer
    private val client = HttpClient(CIO) { install(WebSockets) }

    private val html = "<html><style>@font-face{src:url('/font/space_grotesk.ttf')}</style><script>var x = 1;</script></html>"

    @Before fun setUp() {
        server = RemoteServer(
            loadHtml = { html },
            loadFont = { ByteArray(4) },
            onCommand = { _, c -> commands += c },
            onLockout = { lockouts.incrementAndGet() },
            onControllersGone = { gone.incrementAndGet() },
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

    private fun wrongPin() = "000000".takeIf { server.pin != "000000" } ?: "111111"

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
        val other = RemoteServer({ html }, { ByteArray(0) }, { _, _ -> }, {}, port = port)
        assertFalse(other.start(lanIp = null))
    }

    @Test fun wrongPinIsDeniedAndFiveFailuresLockOut() = runBlocking {
        repeat(5) {
            client.webSocket(ws) {
                send(Frame.Text("AUTH:${wrongPin()}"))
                assertEquals("DENIED", next())
            }
        }
        withTimeout(3_000) { while (lockouts.get() == 0) delay(20) }
        assertEquals(1, lockouts.get())
        assertTrue(server.controllers.value.isEmpty())
    }

    @Test fun lockoutIsEnforcedByTheServerItself() = runBlocking {
        repeat(5) { client.webSocket(ws) { send(Frame.Text("AUTH:${wrongPin()}")); assertEquals("DENIED", next()) } }
        // Even the RIGHT pin is refused now, whatever the host does with the callback …
        client.webSocket(ws) { send(Frame.Text("AUTH:${server.pin}")); assertEquals("DENIED", next()) }
        assertTrue(server.controllers.value.isEmpty())
        // … the page is gone too, and the host is told once only.
        assertEquals(HttpStatusCode.NotFound, client.get("$base/s/${server.sessionId}").status)
        assertEquals(1, lockouts.get())
        // A new share (new link + pin) works again.
        server.stop()
        assertTrue(server.start(lanIp = null))
        client.webSocket(ws) { send(Frame.Text("AUTH:${server.pin}")); assertTrue(next()!!.startsWith("STATE:")) }
    }

    @Test fun fontsOnlyBehindTheSecretLink() = runBlocking {
        assertEquals(HttpStatusCode.NotFound, client.get("$base/font/space_grotesk.ttf").status)
        assertEquals(HttpStatusCode.NotFound, client.get("$base/s/nope/font/space_grotesk.ttf").status)
        assertEquals(HttpStatusCode.NotFound, client.get("$base/s/${server.sessionId}/font/other.ttf").status)
        val ok = client.get("$base/s/${server.sessionId}/font/space_grotesk.ttf")
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals("nosniff", ok.headers["X-Content-Type-Options"])
        val page = client.get("$base/s/${server.sessionId}").bodyAsText()
        assertTrue(page.contains("url('/s/${server.sessionId}/font/space_grotesk.ttf')"))
        assertFalse(page.contains("url('/font/"))
    }

    @Test fun pendingControllerSeesNoHostState() = runBlocking {
        val toys = "{\"toys\":[{\"name\":\"Lovense Edge\",\"acts\":2}]}"
        server.pushState(toys)
        client.webSocket(ws) {
            send(Frame.Text("AUTH:${server.pin}"))
            assertEquals("STATE:{\"approved\":false,\"host\":{}}", next())
            server.pushState(toys)
            assertFalse(next()!!.contains("Lovense Edge"))
            server.approve(server.controllers.value.single().id)
            assertTrue(next()!!.startsWith("TOKEN:"))
            assertEquals("STATE:{\"approved\":true,\"host\":$toys}", next())
        }
    }

    @Test fun crossSiteOriginIsRejected() = runBlocking {
        client.webSocket(ws, request = { header("Origin", "https://evil.example") }) {
            assertNull(next(1_500)) // closed without a reply
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
            assertTrue("no command before approval", commands.isEmpty())

            val id = server.controllers.value.single().id
            server.approve(id)
            token = next()?.removePrefix("TOKEN:")
            assertNotNull(token)
            assertTrue(next()!!.startsWith("STATE:{\"approved\":true"))
            send(Frame.Text("@0:M2:14"))
            withTimeout(2_000) { while (commands.isEmpty()) delay(20) }
            assertEquals(RemoteCommand.SetMotor(2, 14, target = 0), commands.single())
        }
        // Reconnecting with the token: approved straight away (network blip), with a fresh token.
        var fresh: String? = null
        client.webSocket(ws) {
            send(Frame.Text("AUTH:${server.pin}:$token"))
            fresh = next()?.removePrefix("TOKEN:")
            assertNotNull(fresh)
            assertNotEquals(token, fresh)
            assertTrue(next()!!.startsWith("STATE:{\"approved\":true"))
        }
        // The token was single use: a replay (e.g. by the relay) is back to pending.
        client.webSocket(ws) {
            send(Frame.Text("AUTH:${server.pin}:$token"))
            assertTrue(next()!!.startsWith("STATE:{\"approved\":false"))
        }
        // Forged token: back to pending.
        client.webSocket(ws) {
            send(Frame.Text("AUTH:${server.pin}:forged"))
            assertTrue(next()!!.startsWith("STATE:{\"approved\":false"))
        }
        // The fresh token is the valid one.
        client.webSocket(ws) {
            send(Frame.Text("AUTH:${server.pin}:$fresh"))
            assertTrue(next()!!.startsWith("TOKEN:"))
            assertTrue(next()!!.startsWith("STATE:{\"approved\":true"))
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

    @Test fun kickedControllerCannotResumeWithItsToken() = runBlocking {
        var token: String? = null
        client.webSocket(ws) {
            send(Frame.Text("AUTH:${server.pin}"))
            assertTrue(next()!!.startsWith("STATE:{\"approved\":false"))
            server.approve(server.controllers.value.single().id)
            token = next()?.removePrefix("TOKEN:")
            assertNotNull(token)
            assertTrue(next()!!.startsWith("STATE:{\"approved\":true"))
            server.kick(server.controllers.value.single().id)
            withTimeout(3_000) { while (server.controllers.value.isNotEmpty()) delay(20) }
        }
        client.webSocket(ws) {
            send(Frame.Text("AUTH:${server.pin}:$token"))
            assertTrue(next()!!.startsWith("STATE:{\"approved\":false"))
            send(Frame.Text("B:10"))
            delay(200)
            assertTrue("kicked controller must not drive", commands.isEmpty())
        }
    }

    @Test fun controllersGoneFiresOnlyWhenLastApprovedControllerLeaves() = runBlocking {
        // A pending controller leaving does not fire.
        client.webSocket(ws) {
            send(Frame.Text("AUTH:${server.pin}"))
            assertTrue(next()!!.startsWith("STATE:"))
        }
        withTimeout(3_000) { while (server.controllers.value.isNotEmpty()) delay(20) }
        delay(100)
        assertEquals(0, gone.get())
        // An approved one disconnecting does.
        client.webSocket(ws) {
            send(Frame.Text("AUTH:${server.pin}"))
            assertTrue(next()!!.startsWith("STATE:"))
            server.approve(server.controllers.value.single().id)
            assertTrue(next()!!.startsWith("TOKEN:"))
            assertTrue(next()!!.startsWith("STATE:{\"approved\":true"))
        }
        withTimeout(3_000) { while (gone.get() == 0) delay(20) }
        assertEquals(1, gone.get())
    }

    @Test fun controllersGoneFiresOnKickOfApprovedController() = runBlocking {
        client.webSocket(ws) {
            send(Frame.Text("AUTH:${server.pin}"))
            assertTrue(next()!!.startsWith("STATE:"))
            val id = server.controllers.value.single().id
            server.approve(id)
            assertTrue(next()!!.startsWith("TOKEN:"))
            assertTrue(next()!!.startsWith("STATE:{\"approved\":true"))
            server.kick(id)
            withTimeout(3_000) { while (gone.get() == 0) delay(20) }
        }
        delay(200)
        assertEquals(1, gone.get()) // not fired a second time by the socket closing
    }
}
