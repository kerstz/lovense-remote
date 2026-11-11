package com.edge2.remote.remote

import android.content.res.AssetManager
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** A connected, authenticated controller (PIN OK), pending or approved. */
data class ControllerInfo(val id: Int, val approved: Boolean)

/**
 * Embedded HTTP + WebSocket server (host mode). Defence in depth:
 *
 *  1. **Secret link**: `GET /s/{id}`, its fonts and `WS /ws/{id}` only exist for
 *     the current session id (128 bits, SecureRandom, regenerated on every
 *     share). Nothing is served outside the link, so a LAN scanner cannot even
 *     tell that this app is running.
 *  2. **6-digit PIN**, sent in the first WS message (never in the URL → absent
 *     from relay logs), compared in constant time. **5 failures burn the
 *     session**: the server itself refuses every further attempt — even with
 *     the right PIN — and tells the host ([onLockout]); no brute force possible.
 *  3. **Host approval PER controller**: an authenticated controller stays
 *     "pending" (commands ignored, host state hidden) until the host approves
 *     THAT controller. Once approved, a resume token lets it reconnect (network
 *     blip) without asking again. The token is **single use**: each resume
 *     burns it and issues a new one, so a copy captured in transit (e.g. by the
 *     relay) cannot be replayed; kicking the controller ([kick]) revokes it.
 *  4. **Anti-CSWSH**: `Origin` must name our own host *and* port.
 *  5. **Anti-DoS**: frames ≤ 256 B, rate limiting, max 3 sockets,
 *     authentication within 10 s, pending approval ≤ 2 min.
 *  6. **Minimal network surface**: listens on 127.0.0.1 (tunnel) + the Wi-Fi
 *     IP only — never 0.0.0.0 (no exposure on cellular / VPN).
 *  7. **Headers**: strict CSP (script pinned by hash), no-referrer, no-store,
 *     anti-framing, nosniff.
 */
class RemoteServer(
    /** Content of the control page (assets/controller.html). */
    private val loadHtml: () -> String,
    /** Font file by name (already allow-listed). */
    private val loadFont: (String) -> ByteArray,
    private val onCommand: (controllerId: Int, RemoteCommand) -> Unit,
    private val onLockout: () -> Unit,
    /**
     * Called when an APPROVED controller is gone (socket closed or kicked) and no
     * other approved controller remains — the host should stop the toys (safety).
     */
    private val onControllersGone: () -> Unit = {},
    val port: Int = 8787,
) {
    /** Android constructor: page and fonts served from the APK assets. */
    constructor(
        assets: AssetManager,
        onCommand: (controllerId: Int, RemoteCommand) -> Unit,
        onLockout: () -> Unit,
        onControllersGone: () -> Unit = {},
    ) : this(
        loadHtml = { assets.open("controller.html").bufferedReader().use { it.readText() } },
        loadFont = { name -> assets.open("fonts/$name").use { it.readBytes() } },
        onCommand = onCommand,
        onLockout = onLockout,
        onControllersGone = onControllersGone,
    )

    @Volatile
    var sessionId: String = ""
        private set

    /** PIN required from controllers. Generated on every [start]. */
    @Volatile
    var pin: String = ""
        private set

    private val _controllers = MutableStateFlow<List<ControllerInfo>>(emptyList())
    /** Authenticated controllers (PIN OK), approved or pending approval. */
    val controllers: StateFlow<List<ControllerInfo>> = _controllers.asStateFlow()

    private class Session(val id: Int, val ws: DefaultWebSocketServerSession) {
        val approved = AtomicBoolean(false)
        /** Resume token tied to this controller (revoked when it is kicked). */
        @Volatile var token: String? = null
    }

    private val sessions = ConcurrentHashMap<Int, Session>()
    private val nextId = AtomicInteger(1)
    private val failedPins = AtomicInteger(0)
    /** Set after [MAX_PIN_FAILURES]: the share is burnt until the next [start]. */
    private val lockedOut = AtomicBoolean(false)
    private val openSockets = AtomicInteger(0)
    /** Resume tokens issued to approved controllers (valid once, for this session). */
    private val resumeTokens = ConcurrentHashMap.newKeySet<String>()

    /** Latest state (toys, …) pushed to controllers, as JSON. */
    @Volatile private var lastState: String = "{}"

    private var engine: EmbeddedServer<*, *>? = null

    private val html: String by lazy(loadHtml)

    /** CSP: only the inline script shipped with the app (SHA-256 hash) may run. */
    private val csp: String by lazy {
        val script = html.substringAfter("<script>").substringBefore("</script>")
        "default-src 'none'; script-src ${ShareSecurity.cspHash(script)}; style-src 'unsafe-inline'; " +
            "font-src 'self'; connect-src 'self'; img-src 'self' data:; base-uri 'none'; " +
            "form-action 'none'; frame-ancestors 'none'"
    }

    /**
     * Starts the server on 127.0.0.1 (+ [lanIp] if given). Returns false if the
     * app cannot open a socket (firewall / network permission off / port in
     * use) — without crashing.
     */
    fun start(lanIp: String?): Boolean {
        if (engine != null) return true
        sessionId = ShareSecurity.token(16)
        pin = ShareSecurity.pin(6)
        failedPins.set(0)
        lockedOut.set(false)
        resumeTokens.clear()
        // Pre-check: if an address can't be bound (network blocked, port taken),
        // do NOT start Ktor — its bind failure also leaks as an uncaught
        // exception on a coroutine thread, which would crash the app.
        val hosts = listOfNotNull("127.0.0.1", lanIp)
        if (!hosts.all { canBind(it, port) }) return false
        val server = runCatching {
            embeddedServer(CIO, configure = {
                connector { host = "127.0.0.1"; port = this@RemoteServer.port }
                if (lanIp != null) connector { host = lanIp; port = this@RemoteServer.port }
            }) { module() }.also { it.start(wait = false) }
        }.getOrElse { return false }
        engine = server
        return true
    }

    private fun canBind(host: String, port: Int): Boolean = runCatching {
        java.net.ServerSocket().use {
            it.reuseAddress = true
            it.bind(java.net.InetSocketAddress(host, port))
        }
    }.isSuccess

    /** true if [id] is the live session (constant time) and the share is not burnt. */
    private fun sessionOk(id: String?): Boolean =
        !lockedOut.get() && ShareSecurity.constantTimeEquals(id, sessionId)

    /** The control page with its font URLs bound to [id] (fonts live behind the secret link too). */
    internal fun pageFor(id: String): String = html.replace("url('/font/", "url('/s/$id/font/")

    private fun Application.module() {
        install(WebSockets) {
            pingPeriodMillis = 15_000
            timeoutMillis = 30_000
            maxFrameSize = 256
            masking = false
        }
        routing {
            get("/s/{id}") {
                val id = call.parameters["id"]
                if (!sessionOk(id)) { call.respondText("", status = HttpStatusCode.NotFound); return@get }
                call.securityHeaders()
                call.respondText(pageFor(id!!), ContentType.Text.Html)
            }
            // Self-hosted fonts (no external CDN), only through the secret link —
            // allow-list against path traversal.
            get("/s/{id}/font/{name}") {
                val name = call.parameters["name"]
                if (!sessionOk(call.parameters["id"]) || name !in FONT_ASSETS) {
                    call.respondText("", status = HttpStatusCode.NotFound); return@get
                }
                val bytes = loadFont(name!!)
                call.response.header("X-Content-Type-Options", "nosniff")
                call.response.header(HttpHeaders.CacheControl, CacheControl.NoStore(null).toString())
                call.respondBytes(bytes, ContentType("font", "ttf"))
            }
            webSocket("/ws/{id}") { handleSocket() }
        }
    }

    private fun ApplicationCall.securityHeaders() {
        response.header("Content-Security-Policy", csp)
        response.header("Referrer-Policy", "no-referrer")
        response.header("X-Frame-Options", "DENY")
        response.header("X-Content-Type-Options", "nosniff")
        response.header(HttpHeaders.CacheControl, CacheControl.NoStore(null).toString())
        response.header("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
    }

    private suspend fun DefaultWebSocketServerSession.handleSocket() {
        val policy = CloseReason(CloseReason.Codes.VIOLATED_POLICY, "")
        // A burnt share still answers DENIED (after the delay) so the page stops reconnecting.
        if (!ShareSecurity.constantTimeEquals(call.parameters["id"], sessionId)) { close(policy); return }
        if (!ShareSecurity.originAllowed(call.request.headers[HttpHeaders.Origin], call.request.headers[HttpHeaders.Host])) {
            close(policy); return
        }
        if (openSockets.incrementAndGet() > MAX_SOCKETS) { openSockets.decrementAndGet(); close(policy); return }
        try {
            // 1. Authentication: first message `AUTH:<pin>[:<token>]` within 10 s.
            val auth = withTimeoutOrNull(10_000) {
                (incoming.receive() as? Frame.Text)?.readText()
            }
            val parts = auth?.takeIf { it.startsWith("AUTH:") }?.removePrefix("AUTH:")?.split(':', limit = 2)
            // Once burnt, the share refuses everyone — the right PIN included — until a new share.
            val pinOk = !lockedOut.get() && parts != null && ShareSecurity.constantTimeEquals(parts[0], pin)
            if (!pinOk) {
                if (failedPins.incrementAndGet() >= MAX_PIN_FAILURES && lockedOut.compareAndSet(false, true)) onLockout()
                delay(1_000) // slows down guessing
                runCatching { outgoing.send(Frame.Text("DENIED")) }
                close(policy)
                return
            }
            // Resume token: constant-time lookup, then atomically burnt (single use).
            val resumed = parts.getOrNull(1)?.let { tok ->
                resumeTokens.firstOrNull { ShareSecurity.constantTimeEquals(it, tok) }?.takeIf { resumeTokens.remove(it) }
            }

            // 2. Registration: pending approval (unless resuming an approved controller).
            val s = Session(nextId.getAndIncrement(), this)
            if (resumed != null) {
                s.approved.set(true)
                s.token = issueToken()
            }
            sessions[s.id] = s
            publish()
            s.token?.let { outgoing.send(Frame.Text("TOKEN:$it")) }
            outgoing.send(Frame.Text("STATE:" + stateFor(s)))
            val approvalTimeout = launch {
                delay(120_000)
                if (!s.approved.get()) close(policy)
            }

            // 3. Commands (ignored until the host approves this controller).
            // Legit clients coalesce (~60 msg/s max); anything above is dropped,
            // and a sustained flood (300 drops in a row) closes the connection.
            val limiter = RateLimiter(perSecond = 80.0, burst = 120.0)
            var dropped = 0
            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    if (!limiter.take()) { if (++dropped > 300) break; continue }
                    dropped = 0
                    if (!s.approved.get()) continue
                    RemoteCommand.parse(frame.readText())?.let { onCommand(s.id, it) }
                }
            } finally {
                approvalTimeout.cancel()
                val removed = sessions.remove(s.id)
                publish()
                if (removed != null && s.approved.get()) notifyIfNoApproved()
            }
        } finally {
            openSockets.decrementAndGet()
        }
    }

    /** New resume token, registered as valid for this share. */
    private fun issueToken(): String = ShareSecurity.token(16).also { resumeTokens += it }

    /** The host approves controller [id]: commands allowed + resume token. */
    fun approve(id: Int) {
        val s = sessions[id] ?: return
        if (!s.approved.compareAndSet(false, true)) return
        val tok = issueToken()
        s.token = tok
        s.ws.launch {
            runCatching {
                s.ws.outgoing.send(Frame.Text("TOKEN:$tok"))
                s.ws.outgoing.send(Frame.Text("STATE:" + stateFor(s)))
            }
        }
        publish()
    }

    /** The host refuses / kicks controller [id] (the others stay connected). */
    fun kick(id: Int) {
        val s = sessions.remove(id) ?: return
        s.token?.let { resumeTokens.remove(it) }
        s.ws.launch {
            runCatching { s.ws.outgoing.send(Frame.Text("DENIED")) }
            s.ws.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, ""))
        }
        publish()
        if (s.approved.get()) notifyIfNoApproved()
    }

    private fun notifyIfNoApproved() {
        if (sessions.values.none { it.approved.get() }) onControllersGone()
    }

    /** Pushes the current state (toy list…) to every controller. */
    fun pushState(stateJson: String) {
        lastState = stateJson
        for (s in sessions.values) {
            s.ws.launch { runCatching { s.ws.outgoing.send(Frame.Text("STATE:" + stateFor(s))) } }
        }
    }

    /** Host details (toy names are intimate data) only once the host has approved this controller. */
    private fun stateFor(s: Session): String =
        if (s.approved.get()) "{\"approved\":true,\"host\":$lastState}" else "{\"approved\":false,\"host\":{}}"

    private fun publish() {
        _controllers.value = sessions.values.sortedBy { it.id }.map { ControllerInfo(it.id, it.approved.get()) }
    }

    fun stop() {
        val e = engine
        engine = null
        sessions.clear()
        resumeTokens.clear()
        _controllers.value = emptyList()
        sessionId = ""
        pin = ""
        e?.stop(gracePeriodMillis = 200, timeoutMillis = 800)
    }

    val isRunning: Boolean get() = engine != null

    private companion object {
        /** Servable fonts (path-traversal guard). */
        val FONT_ASSETS = setOf("space_grotesk.ttf", "jetbrains_mono.ttf")
        const val MAX_PIN_FAILURES = 5
        /** Simultaneous sockets (authenticated or not). */
        const val MAX_SOCKETS = 3
    }
}
