package com.edge2.remote.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Mode HOST par tunnel (Phase 4B) : le téléphone se connecte **en sortant** au
 * relais public et y reçoit les commandes d'un contrôleur web distant — pas
 * besoin d'être sur le même WiFi ni d'ouvrir un port (traversée NAT).
 *
 * Symétrique du [RemoteServer] LAN : même callback [onCommand], mais la socket
 * part du téléphone au lieu d'être servie par lui. L'[sessionId] est conservé
 * tant que le partage est ouvert → le lien partagé survit aux reconnexions.
 */
class RemoteTunnel(
    private val scope: CoroutineScope,
    private val onCommand: (RemoteCommand) -> Unit,
) {
    private val client = HttpClient(CIO) { install(WebSockets) }

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    @Volatile
    var sessionId: String = ""
        private set

    private var job: Job? = null

    /** Lien à partager, ou null si le tunnel n'est pas (encore) ouvert. */
    val shareUrl: String?
        get() = sessionId.takeIf { it.isNotBlank() }?.let(RelayConfig::shareUrl)

    fun start() {
        if (!RelayConfig.enabled || job != null) return
        sessionId = randomId()
        job = scope.launch {
            while (isActive) {
                runCatching {
                    client.webSocket(urlString = RelayConfig.hostWsUrl(sessionId)) {
                        _connected.value = true
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                RemoteCommand.parse(frame.readText())?.let(onCommand)
                            }
                        }
                    }
                }
                _connected.value = false
                if (isActive) delay(2000) // relais injoignable / coupure → on retente
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _connected.value = false
        sessionId = ""
    }

    fun release() {
        stop()
        client.close()
    }

    private fun randomId(): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..8).map { alphabet[Random.nextInt(alphabet.length)] }.joinToString("")
    }
}
