package com.edge2.remote.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Mode CONTRÔLEUR : l'app pilote le toy d'un host distant via WebSocket.
 * Utilisé quand l'app est ouverte par un deep link `edge2remote://control?ws=…`.
 */
class RemoteController(private val scope: CoroutineScope) {

    private val client = HttpClient(CIO) { install(WebSockets) }

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    // File d'envoi conflatée : seul le dernier message compte (latence basse).
    private val commandQueue = Channel<String>(Channel.CONFLATED)
    private var job: Job? = null

    fun connect(wsUrl: String) {
        disconnect()
        job = scope.launch {
            runCatching {
                client.webSocket(urlString = wsUrl) {
                    _connected.value = true
                    val sender = launch {
                        for (msg in commandQueue) {
                            if (!isActive) break
                            send(Frame.Text(msg))
                        }
                    }
                    // Maintient la session ouverte jusqu'à sa fermeture.
                    closeReason.await()
                    sender.cancel()
                }
            }
            _connected.value = false
        }
    }

    fun send(command: RemoteCommand) {
        commandQueue.trySend(RemoteCommand.format(command))
    }

    fun disconnect() {
        job?.cancel()
        job = null
        _connected.value = false
    }

    fun release() {
        disconnect()
        client.close()
    }
}
