package com.edge2.relay

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
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Relais Edge2 Remote — traversée NAT par connexion sortante (Phase 4B).
 *
 * Le téléphone (host) ne peut pas être joint depuis Internet (NAT). Il se
 * connecte donc *en sortant* à ce relais public ; le contrôleur web s'y connecte
 * aussi ; le relais ne fait que recopier les trames de commande contrôleur→host.
 *
 *  - `GET /s/{id}`     → page de contrôle (WebSocket vers /ctrl).
 *  - `WS  /host?id=…`  → le téléphone s'enregistre comme host de la session.
 *  - `WS  /ctrl?id=…`  → un contrôleur web rejoint ; ses trames texte sont
 *                        poussées vers le host de la même session.
 *
 * Sécurité : l'`id` de session (8+ car. aléatoires côté app) est le seul secret.
 * Le relais ne stocke rien et ne voit que du texte `M1/M2/B/S` opaque. Mets-le
 * derrière TLS (fly.io/Cloudflare fournissent du https/wss automatiquement).
 */

/** Une session host : la socket du téléphone + une file de commandes à lui pousser. */
private class HostSession(val socket: DefaultWebSocketSession) {
    // UNLIMITED (pas CONFLATED) : M1 et M2 ciblent des moteurs différents, on ne
    // doit jamais en écraser un par l'autre. Les payloads sont minuscules.
    val outbox = Channel<String>(Channel.UNLIMITED)
}

/** id de session → host connecté. */
private val hosts = ConcurrentHashMap<String, HostSession>()

private val controllerHtml: String by lazy {
    val raw = object {}.javaClass.getResourceAsStream("/controller.html")
        ?.bufferedReader()?.use { it.readText() }
        ?: error("controller.html introuvable dans les resources du relais")
    raw.replace("__WS_PATH__", "/ctrl")
}

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(CIO, port = port, host = "0.0.0.0") {
        install(WebSockets)
        routing {
            get("/") { call.respondText(controllerHtml, ContentType.Text.Html) }
            get("/s/{id}") { call.respondText(controllerHtml, ContentType.Text.Html) }

            // --- Téléphone (host) : connexion sortante, NAT-friendly ---------
            webSocket("/host") {
                val id = call.request.queryParameters["id"]
                if (id.isNullOrBlank()) { close(); return@webSocket }

                val session = HostSession(this)
                // Un seul host par id : on remplace l'ancien s'il traîne.
                hosts.put(id, session)?.let { runCatching { it.socket.close() } }

                // Pompe série : draine outbox → socket (évite les send concurrents).
                val pump = launch {
                    for (msg in session.outbox) send(Frame.Text(msg))
                }
                try {
                    // Reste ouvert jusqu'à fermeture côté téléphone (ignore l'entrant).
                    for (frame in incoming) { /* le host n'envoie rien pour l'instant */ }
                } finally {
                    pump.cancel()
                    session.outbox.close()
                    hosts.remove(id, session)
                }
            }

            // --- Contrôleur web : rejoint une session existante --------------
            webSocket("/ctrl") {
                val id = call.request.queryParameters["id"]
                val host = id?.let { hosts[it] }
                if (host == null) { close(); return@webSocket }

                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        // Si le host s'est barré entre-temps, trySend échoue → on coupe.
                        if (host.outbox.trySend(frame.readText()).isFailure) break
                    }
                }
            }
        }
    }.start(wait = true)
}
