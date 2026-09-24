package com.edge2.remote.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Mode CONTRÔLEUR : l'app pilote les jouets d'un host distant via WebSocket.
 * Ouvert par un deep link `edge2remote://control?ws=…&pin=…` (validé par
 * [validateWsUrl] puis confirmé par l'utilisateur avant toute connexion).
 */
class RemoteController(private val scope: CoroutineScope) {

    enum class Phase { CONNECTING, WAITING, LIVE, DENIED }

    private val client = HttpClient(CIO) {
        install(WebSockets) {
            pingIntervalMillis = 15_000
            maxFrameSize = 4_096 // l'hôte n'envoie que de petits messages d'état
        }
    }

    private val _phase = MutableStateFlow(Phase.CONNECTING)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    /** Noms des jouets de l'hôte (pour cibler un jouet). */
    private val _toys = MutableStateFlow<List<String>>(emptyList())
    val toys: StateFlow<List<String>> = _toys.asStateFlow()

    // Dernière commande par clé (M1/M2/B) : coalescée, rien n'est perdu entre clés.
    private val pending = LinkedHashMap<String, String>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null
    @Volatile private var token: String? = null

    fun connect(wsUrl: String, pin: String) {
        disconnect()
        job = scope.launch {
            while (isActive && _phase.value != Phase.DENIED) {
                _phase.value = Phase.CONNECTING
                runCatching {
                    client.webSocket(urlString = wsUrl) {
                        outgoing.send(Frame.Text("AUTH:$pin" + (token?.let { ":$it" } ?: "")))
                        _phase.value = Phase.WAITING
                        val sender = launch {
                            for (w in wake) {
                                val batch = synchronized(pending) { pending.values.toList().also { pending.clear() } }
                                if (_phase.value == Phase.LIVE) batch.forEach { outgoing.send(Frame.Text(it)) }
                                delay(50) // ≤ 20 envois/s par clé
                            }
                        }
                        try {
                            for (frame in incoming) {
                                if (frame is Frame.Text) onMessage(frame.readText())
                            }
                        } finally {
                            sender.cancel()
                        }
                    }
                }
                if (_phase.value != Phase.DENIED) {
                    _phase.value = Phase.CONNECTING
                    delay(1_500) // reconnexion (avec jeton de reprise si accepté)
                }
            }
        }
    }

    private fun onMessage(m: String) {
        when {
            m == "DENIED" -> _phase.value = Phase.DENIED
            m.startsWith("TOKEN:") -> token = m.removePrefix("TOKEN:").take(64)
            m.startsWith("STATE:") -> runCatching {
                val st = Json.parseToJsonElement(m.removePrefix("STATE:")).jsonObject
                _phase.value = if (st["approved"]?.jsonPrimitive?.boolean == true) Phase.LIVE else Phase.WAITING
                val host = st["host"] as? JsonObject
                _toys.value = host?.get("toys")?.jsonArray?.map {
                    it.jsonObject["name"]?.jsonPrimitive?.content.orEmpty().take(32)
                }.orEmpty()
            }
        }
    }

    fun send(command: RemoteCommand) {
        val text = RemoteCommand.format(command)
        synchronized(pending) {
            if (command is RemoteCommand.Stop) pending.clear()
            pending[key(command)] = text
        }
        wake.trySend(Unit)
    }

    private fun key(c: RemoteCommand) = when (c) {
        is RemoteCommand.SetMotor -> "M${c.index}"
        is RemoteCommand.SetBoth -> "B"
        is RemoteCommand.Stop -> "S"
    }

    fun disconnect() {
        job?.cancel()
        job = null
    }

    fun release() {
        disconnect()
        client.close()
    }

    companion object {
        /**
         * N'accepte qu'une URL `ws(s)://hôte[:port]/ws/<id>` — un site tiers ne
         * peut pas nous faire ouvrir n'importe quoi via le deep link.
         */
        fun validateWsUrl(raw: String?): String? {
            if (raw.isNullOrBlank() || raw.length > 512) return null
            val u = runCatching { URI(raw) }.getOrNull() ?: return null
            if (u.scheme !in setOf("ws", "wss")) return null
            if (u.host.isNullOrBlank() || u.userInfo != null || u.rawQuery != null || u.rawFragment != null) return null
            if (!Regex("/ws/[A-Za-z0-9_-]{16,64}").matches(u.rawPath ?: "")) return null
            return raw
        }

        fun validatePin(raw: String?): String? = raw?.takeIf { Regex("[0-9]{4,8}").matches(it) }
    }
}
