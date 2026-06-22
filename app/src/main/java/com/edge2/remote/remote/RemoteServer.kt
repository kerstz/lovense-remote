package com.edge2.remote.remote

import android.content.res.AssetManager
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.random.Random

/**
 * Serveur HTTP + WebSocket embarqué dans l'app (mode host).
 *
 *  - `GET /s/{id}` → sert la page HTML de contrôle (assets/controller.html).
 *  - `WS  /ws?id=` → reçoit les commandes texte ([RemoteCommand]) et les
 *    pousse vers [onCommand] (qui les applique au toy via le BLE).
 *
 * Sécurité v1 « lien seul » : un [sessionId] aléatoire est régénéré à chaque
 * [start]. Le WS n'accepte que l'id courant → les anciens liens meurent dès
 * qu'on relance un partage.
 */
class RemoteServer(
    private val assets: AssetManager,
    private val onCommand: (RemoteCommand) -> Unit,
) {
    @Volatile
    var sessionId: String = ""
        private set

    /** Code PIN exigé du contrôleur (null = pas de PIN). Défini par le moteur. */
    @Volatile
    var pin: String? = null

    val port: Int = 8787

    /** Nombre de contrôleurs actuellement connectés (pour l'écran « On contrôle »). */
    private val _controllers = MutableStateFlow(0)
    val controllers: StateFlow<Int> = _controllers.asStateFlow()

    private var engine: io.ktor.server.engine.ApplicationEngine? = null

    private val html: String by lazy {
        // Serveur embarqué (LAN) → le WS de la page pointe sur "/ws".
        assets.open("controller.html").bufferedReader().use { it.readText() }
            .replace("__WS_PATH__", "/ws")
    }

    /** HTML servi : on indique à la page si un PIN est requis (par requête). */
    private fun servedHtml(): String =
        html.replace("__PIN_REQUIRED__", if (pin != null) "1" else "0")

    /**
     * Démarre le serveur. Renvoie false si l'app ne peut pas créer de socket
     * (réseau bloqué par un pare-feu / autorisation réseau coupée) : dans ce cas
     * on n'amorce PAS Ktor, dont l'erreur de bind (asynchrone) crasherait l'app.
     */
    fun start(): Boolean {
        if (engine != null) return true
        if (!canBindSocket()) return false
        sessionId = randomId()
        engine = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            install(WebSockets)
            routing {
                get("/") { call.respondText(servedHtml(), ContentType.Text.Html) }
                get("/s/{id}") { call.respondText(servedHtml(), ContentType.Text.Html) }
                webSocket("/ws") {
                    // Gate « lien seul » : on rejette les sessions périmées.
                    if (call.request.queryParameters["id"] != sessionId) {
                        close()
                        return@webSocket
                    }
                    // Gate PIN : code obligatoire pour piloter.
                    val pinNow = pin
                    if (pinNow != null && call.request.queryParameters["pin"] != pinNow) {
                        close()
                        return@webSocket
                    }
                    _controllers.update { it + 1 }
                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                RemoteCommand.parse(frame.readText())?.let(onCommand)
                            }
                        }
                    } finally {
                        _controllers.update { it - 1 }
                    }
                }
            }
        }.also { it.start(wait = false) }
        return true
    }

    /** Teste si l'app peut créer/binder un socket serveur (réseau autorisé ?). */
    private fun canBindSocket(): Boolean = runCatching {
        java.net.ServerSocket().use { it.bind(java.net.InetSocketAddress(0)) }
    }.isSuccess

    fun stop() {
        engine?.stop(gracePeriodMillis = 200, timeoutMillis = 800)
        engine = null
        sessionId = ""
        _controllers.value = 0
    }

    val isRunning: Boolean get() = engine != null

    private fun randomId(): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..8).map { alphabet[Random.nextInt(alphabet.length)] }.joinToString("")
    }
}
