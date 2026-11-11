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
 *     blip) without asking again. Tokens **rotate on every resume** and the
 *     rotation is **gated by an ACK**: the controller answers `ACK:<new>` once it
 *     has stored the new token, and only then is the presented one burnt. So a
 *     lost `TOKEN` frame does not strand the controller in "pending", while a
 *     copy captured in transit (e.g. by the relay) cannot be replayed once the
 *     rotation is acknowledged. At most two tokens (presented + fresh) are live
 *     per controller; kicking the controller ([kick]) revokes both.
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
        /** Resume grant of this controller (revoked when it is kicked). */
        @Volatile var grant: Grant? = null
    }

    /** The live resume tokens of one approved controller: at most the presented one + a fresh one. */
    private class Grant { val tokens = LinkedHashSet<String>() }

    private val sessions = ConcurrentHashMap<Int, Session>()
    private val nextId = AtomicInteger(1)
    private val failedPins = AtomicInteger(0)
    /** Set after [MAX_PIN_FAILURES]: the share is burnt until the next [start]. */
    private val lockedOut = AtomicBoolean(false)
    private val openSockets = AtomicInteger(0)
    /** Live resume tokens → their grant, for this share. Guarded by [tokenLock]. */
    private val resumeTokens = HashMap<String, Grant>()
    private val tokenLock = Any()

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
        synchronized(tokenLock) { resumeTokens.clear() }
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
            // Resume token: constant-time lookup, then rotation (the old one dies on ACK).
            val resumed = parts.getOrNull(1)?.let { resume(it) }

            // 2. Registration: pending approval (unless resuming an approved controller).
            val s = Session(nextId.getAndIncrement(), this)
            if (resumed != null) {
                s.approved.set(true)
                s.grant = resumed.first
            }
            sessions[s.id] = s
            publish()
            resumed?.let { outgoing.send(Frame.Text("TOKEN:${it.second}")) }
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
                    val text = frame.readText()
                    if (text.startsWith("ACK:")) { s.grant?.let { acknowledge(it, text.removePrefix("ACK:")) }; continue }
                    if (!s.approved.get()) continue
                    RemoteCommand.parse(text)?.let { onCommand(s.id, it) }
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

    /** New resume token added to [grant]. Caller holds [tokenLock]. */
    private fun issueToken(grant: Grant): String =
        ShareSecurity.token(16).also { grant.tokens += it; resumeTokens[it] = grant }

    /** Keeps only [keep] alive in [grant]. Caller holds [tokenLock]. */
    private fun retainOnly(grant: Grant, keep: String?) {
        grant.tokens.filter { it != keep }.forEach { resumeTokens.remove(it); grant.tokens.remove(it) }
    }

    /**
     * Resume with [presented]: if it is live, rotates. The presented token stays
     * valid until the fresh one is acknowledged ([acknowledge]) — in case the
     * `TOKEN` frame is lost — and any other token of the grant (issued after it,
     * so never received) dies now: at most two live tokens per controller.
     */
    private fun resume(presented: String): Pair<Grant, String>? = synchronized(tokenLock) {
        val match = resumeTokens.keys.firstOrNull { ShareSecurity.constantTimeEquals(it, presented) } ?: return null
        val grant = resumeTokens.getValue(match)
        retainOnly(grant, match)
        grant to issueToken(grant)
    }

    /** The controller confirmed it stored [tok]: every other token of [grant] is burnt. */
    private fun acknowledge(grant: Grant, tok: String) = synchronized(tokenLock) {
        val match = grant.tokens.firstOrNull { ShareSecurity.constantTimeEquals(it, tok) } ?: return
        retainOnly(grant, match)
    }

    private fun revoke(grant: Grant?) {
        if (grant != null) synchronized(tokenLock) { retainOnly(grant, null) }
    }

    /** The host approves controller [id]: commands allowed + resume token. */
    fun approve(id: Int) {
        val s = sessions[id] ?: return
        if (!s.approved.compareAndSet(false, true)) return
        val grant = Grant()
        val tok = synchronized(tokenLock) { issueToken(grant) }
        s.grant = grant
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
        revoke(s.grant)
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
        synchronized(tokenLock) { resumeTokens.clear() }
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
