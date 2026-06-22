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

    val port: Int = 8787

    private var engine: io.ktor.server.engine.ApplicationEngine? = null

    private val html: String by lazy {
        // Serveur embarqué (LAN) → le WS de la page pointe sur "/ws".
        assets.open("controller.html").bufferedReader().use { it.readText() }
            .replace("__WS_PATH__", "/ws")
    }

    fun start() {
        if (engine != null) return
        sessionId = randomId()
        engine = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            install(WebSockets)
            routing {
                get("/") { call.respondText(html, ContentType.Text.Html) }
                get("/s/{id}") { call.respondText(html, ContentType.Text.Html) }
                webSocket("/ws") {
                    // Gate « lien seul » : on rejette les sessions périmées.
                    if (call.request.queryParameters["id"] != sessionId) {
                        close()
                        return@webSocket
                    }
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            RemoteCommand.parse(frame.readText())?.let(onCommand)
                        }
                    }
                }
            }
        }.also { it.start(wait = false) }
    }

    fun stop() {
        engine?.stop(gracePeriodMillis = 200, timeoutMillis = 800)
        engine = null
        sessionId = ""
    }

    val isRunning: Boolean get() = engine != null

    private fun randomId(): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..8).map { alphabet[Random.nextInt(alphabet.length)] }.joinToString("")
    }
}
