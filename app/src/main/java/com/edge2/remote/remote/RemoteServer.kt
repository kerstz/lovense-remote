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

/** Un contrôleur connecté et authentifié (PIN OK), en attente ou accepté. */
data class ControllerInfo(val id: Int, val approved: Boolean)

/**
 * Serveur HTTP + WebSocket embarqué (mode host). Défense en profondeur :
 *
 *  1. **Lien secret** : `GET /s/{id}` et `WS /ws/{id}` n'existent que pour l'id
 *     de session courant (128 bits, SecureRandom, régénéré à chaque partage).
 *  2. **PIN** 6 chiffres, envoyé dans le 1er message WS (jamais dans l'URL →
 *     absent des logs du relais), comparé en temps constant. **5 échecs → la
 *     session est brûlée** ([onLockout]) : pas de force brute possible.
 *  3. **Accord de l'hôte PAR contrôleur** : un contrôleur authentifié reste
 *     « en attente » (commandes ignorées) tant que l'hôte n'a pas accepté CE
 *     contrôleur. Après accord, un jeton de reprise lui permet de se reconnecter
 *     (coupure réseau) sans redemander.
 *  4. **Anti-CSWSH** : `Origin` doit désigner notre propre hôte.
 *  5. **Anti-DoS** : frames ≤ 256 o, débit limité, 3 contrôleurs max,
 *     authentification sous 10 s, attente d'accord ≤ 2 min.
 *  6. **Surface réseau minimale** : écoute sur 127.0.0.1 (tunnel) + l'IP Wi-Fi
 *     seulement — jamais 0.0.0.0 (pas d'exposition sur le cellulaire / VPN).
 *  7. **En-têtes** : CSP stricte (script par empreinte), no-referrer, no-store,
 *     anti-iframe, nosniff.
 */
class RemoteServer(
    /** Contenu de la page de contrôle (assets/controller.html). */
    private val loadHtml: () -> String,
    /** Fichier de police par nom (déjà filtré par liste blanche). */
    private val loadFont: (String) -> ByteArray,
    private val onCommand: (controllerId: Int, RemoteCommand) -> Unit,
    private val onLockout: () -> Unit,
    val port: Int = 8787,
) {
    /** Constructeur Android : page et polices servies depuis les assets de l'APK. */
    constructor(
        assets: AssetManager,
        onCommand: (controllerId: Int, RemoteCommand) -> Unit,
        onLockout: () -> Unit,
    ) : this(
        loadHtml = { assets.open("controller.html").bufferedReader().use { it.readText() } },
        loadFont = { name -> assets.open("fonts/$name").use { it.readBytes() } },
        onCommand = onCommand,
        onLockout = onLockout,
    )

    @Volatile
    var sessionId: String = ""
        private set

    /** Code PIN exigé du contrôleur. Généré à chaque [start]. */
    @Volatile
    var pin: String = ""
        private set

    private val _controllers = MutableStateFlow<List<ControllerInfo>>(emptyList())
    /** Contrôleurs authentifiés (PIN OK), acceptés ou en attente d'accord. */
    val controllers: StateFlow<List<ControllerInfo>> = _controllers.asStateFlow()

    private class Session(val id: Int, val ws: DefaultWebSocketServerSession) {
        val approved = AtomicBoolean(false)
    }

    private val sessions = ConcurrentHashMap<Int, Session>()
    private val nextId = AtomicInteger(1)
    private val failedPins = AtomicInteger(0)
    private val openSockets = AtomicInteger(0)
    /** Jetons de reprise délivrés aux contrôleurs acceptés (valables pour la session). */
    private val resumeTokens = ConcurrentHashMap.newKeySet<String>()

    /** Dernier état (jouets, …) poussé aux contrôleurs, JSON. */
    @Volatile private var lastState: String = "{}"

    private var engine: EmbeddedServer<*, *>? = null

    private val html: String by lazy(loadHtml)

    /** CSP : seul le script inline livré avec l'app (empreinte SHA-256) peut s'exécuter. */
    private val csp: String by lazy {
        val script = html.substringAfter("<script>").substringBefore("</script>")
        "default-src 'none'; script-src ${ShareSecurity.cspHash(script)}; style-src 'unsafe-inline'; " +
            "font-src 'self'; connect-src 'self'; img-src 'self' data:; base-uri 'none'; " +
            "form-action 'none'; frame-ancestors 'none'"
    }

    /**
     * Démarre le serveur sur 127.0.0.1 (+ [lanIp] si fournie). Renvoie false si
     * l'app ne peut pas ouvrir de socket (pare-feu / autorisation réseau coupée /
     * port occupé) — sans crasher.
     */
    fun start(lanIp: String?): Boolean {
        if (engine != null) return true
        sessionId = ShareSecurity.token(16)
        pin = ShareSecurity.pin(6)
        failedPins.set(0)
        resumeTokens.clear()
        // Pré-contrôle : si une adresse n'est pas bindable (réseau bloqué, port pris),
        // on n'amorce PAS Ktor — son échec de bind fuit aussi en exception non
        // capturée sur un thread de coroutine, ce qui crasherait l'app.
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

    private fun Application.module() {
        install(WebSockets) {
            pingPeriodMillis = 15_000
            timeoutMillis = 30_000
            maxFrameSize = 256
            masking = false
        }
        routing {
            get("/s/{id}") {
                if (!ShareSecurity.constantTimeEquals(call.parameters["id"], sessionId)) {
                    call.respondText("", status = HttpStatusCode.NotFound); return@get
                }
                call.securityHeaders()
                call.respondText(html, ContentType.Text.Html)
            }
            // Fonts self-hébergées (pas de CDN externe) — liste blanche anti-traversée.
            get("/font/{name}") {
                val name = call.parameters["name"]
                if (name !in FONT_ASSETS) { call.respondText("", status = HttpStatusCode.NotFound); return@get }
                val bytes = loadFont(name!!)
                call.response.header("X-Content-Type-Options", "nosniff")
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
        if (!ShareSecurity.constantTimeEquals(call.parameters["id"], sessionId)) { close(policy); return }
        if (!ShareSecurity.originAllowed(call.request.headers[HttpHeaders.Origin], call.request.headers[HttpHeaders.Host])) {
            close(policy); return
        }
        if (openSockets.incrementAndGet() > MAX_SOCKETS) { openSockets.decrementAndGet(); close(policy); return }
        try {
            // 1. Authentification : 1er message `AUTH:<pin>[:<jeton>]` sous 10 s.
            val auth = withTimeoutOrNull(10_000) {
                (incoming.receive() as? Frame.Text)?.readText()
            }
            val parts = auth?.takeIf { it.startsWith("AUTH:") }?.removePrefix("AUTH:")?.split(':', limit = 2)
            if (parts == null || !ShareSecurity.constantTimeEquals(parts[0], pin)) {
                if (failedPins.incrementAndGet() >= MAX_PIN_FAILURES) onLockout()
                delay(1_000) // ralentit les essais
                runCatching { outgoing.send(Frame.Text("DENIED")) }
                close(policy)
                return
            }
            val resumed = parts.getOrNull(1)?.let { tok -> resumeTokens.any { ShareSecurity.constantTimeEquals(it, tok) } } == true

            // 2. Enregistrement : en attente d'accord (sauf reprise d'un contrôleur accepté).
            val s = Session(nextId.getAndIncrement(), this)
            if (resumed) s.approved.set(true)
            sessions[s.id] = s
            publish()
            outgoing.send(Frame.Text("STATE:" + stateFor(s)))
            val approvalTimeout = launch {
                delay(120_000)
                if (!s.approved.get()) close(policy)
            }

            // 3. Commandes (ignorées tant que l'hôte n'a pas accepté ce contrôleur).
            // Les clients légitimes coalescent (~60 msg/s max) ; au-delà on ignore,
            // et un flot continu (300 rejets d'affilée) ferme la connexion.
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
                sessions.remove(s.id)
                publish()
            }
        } finally {
            openSockets.decrementAndGet()
        }
    }

    /** L'hôte accepte le contrôleur [id] : commandes autorisées + jeton de reprise. */
    fun approve(id: Int) {
        val s = sessions[id] ?: return
        if (!s.approved.compareAndSet(false, true)) return
        val tok = ShareSecurity.token(16)
        resumeTokens += tok
        s.ws.launch {
            runCatching {
                s.ws.outgoing.send(Frame.Text("TOKEN:$tok"))
                s.ws.outgoing.send(Frame.Text("STATE:" + stateFor(s)))
            }
        }
        publish()
    }

    /** L'hôte refuse / éjecte le contrôleur [id] (les autres restent connectés). */
    fun kick(id: Int) {
        val s = sessions.remove(id) ?: return
        s.ws.launch {
            runCatching { s.ws.outgoing.send(Frame.Text("DENIED")) }
            s.ws.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, ""))
        }
        publish()
    }

    /** Pousse l'état courant (liste des jouets…) à tous les contrôleurs. */
    fun pushState(stateJson: String) {
        lastState = stateJson
        for (s in sessions.values) {
            s.ws.launch { runCatching { s.ws.outgoing.send(Frame.Text("STATE:" + stateFor(s))) } }
        }
    }

    private fun stateFor(s: Session): String =
        "{\"approved\":${s.approved.get()},\"host\":$lastState}"

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
        /** Fonts servables (anti-traversée de chemin). */
        val FONT_ASSETS = setOf("space_grotesk.ttf", "jetbrains_mono.ttf")
        const val MAX_PIN_FAILURES = 5
        /** Sockets simultanées (authentifiées ou non). */
        const val MAX_SOCKETS = 3
    }
}
