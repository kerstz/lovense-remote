package com.edge2.remote

import android.content.Context
import com.edge2.remote.ble.ConnectionState
import com.edge2.remote.ble.DiscoveredToy
import com.edge2.remote.ble.Edge2BleManager
import com.edge2.remote.pattern.LovenseImporter
import com.edge2.remote.pattern.Pattern
import com.edge2.remote.pattern.PatternPlayer
import com.edge2.remote.remote.NetworkUtils
import com.edge2.remote.remote.RelayConfig
import com.edge2.remote.remote.RemoteCommand
import com.edge2.remote.remote.RemoteServer
import com.edge2.remote.remote.RemoteTunnel
import com.edge2.remote.service.AppActions
import com.edge2.remote.service.RemoteForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Cœur de l'app au **scope processus** (pas lié à l'Activity) : détient le BLE,
 * le lecteur de patterns, le serveur de partage et le tunnel, plus l'état exposé
 * à l'UI. Maintenu vivant par [RemoteForegroundService] tant qu'une session est
 * active → le contrôle survit à la fermeture de l'app (retour / balayage).
 *
 * [RemoteViewModel] n'est qu'un adaptateur mince au-dessus de ce singleton.
 */
class RemoteEngine private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val ble = Edge2BleManager(appContext)
    private val player = PatternPlayer(ble, scope)
    private val importer = LovenseImporter()
    private val server = RemoteServer(appContext.assets) { cmd -> applyRemote(cmd) }
    private val tunnel = RemoteTunnel(scope) { cmd -> applyRemote(cmd) }

    // --- État exposé -----------------------------------------------------
    val connectionState = ble.connectionState
    val actuatorLevels = ble.actuatorLevels
    val discovered = ble.discovered
    val playing: StateFlow<String?> = player.playing
    val tunnelConnected: StateFlow<Boolean> = tunnel.connected

    private val _linkMode = MutableStateFlow(false)
    val linkMode: StateFlow<Boolean> = _linkMode.asStateFlow()

    private val _shareUrl = MutableStateFlow<String?>(null)
    val shareUrl: StateFlow<String?> = _shareUrl.asStateFlow()

    private val _tunnelUrl = MutableStateFlow<String?>(null)
    val tunnelUrl: StateFlow<String?> = _tunnelUrl.asStateFlow()

    private val _importedPatterns = MutableStateFlow<List<Pattern>>(emptyList())
    val importedPatterns: StateFlow<List<Pattern>> = _importedPatterns.asStateFlow()

    init {
        // Bouton « Couper » de la notification.
        AppActions.onStop = { stopSharing(); disconnect() }
        // Service premier-plan actif tant qu'on est connecté OU en partage :
        // garde le processus (donc le BLE + le serveur) vivant en arrière-plan
        // et même après fermeture de l'app.
        scope.launch {
            combine(connectionState, _shareUrl, _tunnelUrl) { st, lan, tun ->
                st is ConnectionState.Connected || lan != null || tun != null
            }.collect { active ->
                if (active) RemoteForegroundService.start(appContext, currentName())
                else RemoteForegroundService.stop(appContext)
            }
        }
    }

    private fun currentName(): String =
        (connectionState.value as? ConnectionState.Connected)?.deviceName ?: "Lovense"

    // --- Actions ---------------------------------------------------------

    fun scan() = ble.startDiscovery()
    fun connectTo(toy: DiscoveredToy) = ble.connectTo(toy)
    fun disconnect() = ble.disconnect()
    fun toggleLink() { _linkMode.value = !_linkMode.value }
    fun reverse(index: Int) = ble.reverse(index)
    fun playPattern(pattern: Pattern) = player.play(pattern)
    fun stopAll() = player.stop()

    fun setActuator(index: Int, fraction: Float) {
        player.cancel()
        ble.setActuatorFraction(index, fraction)
    }

    fun setBoth(fraction: Float) {
        player.cancel()
        ble.setAllFraction(fraction)
    }

    fun setXY(base: Float, tige: Float) {
        player.cancel()
        ble.setActuatorFraction(0, base)
        ble.setActuatorFraction(1, tige)
    }

    fun startSharing() {
        server.start()
        val ip = NetworkUtils.lanIpv4()
        _shareUrl.value = ip?.let { "http://$it:${server.port}/s/${server.sessionId}" }
        if (RelayConfig.enabled) {
            tunnel.start()
            _tunnelUrl.value = tunnel.shareUrl
        }
    }

    fun stopSharing() {
        server.stop()
        tunnel.stop()
        _shareUrl.value = null
        _tunnelUrl.value = null
    }

    fun importFromUrl(url: String) {
        scope.launch {
            importer.fromUrl(url.trim())?.let { p -> _importedPatterns.update { it + p } }
        }
    }

    fun importFromText(content: String) {
        importer.fromText(content.trim())?.let { p -> _importedPatterns.update { it + p } }
    }

    /** Commande reçue d'un contrôleur distant ; M1/M2 → actionneurs 0/1. */
    private fun applyRemote(cmd: RemoteCommand) {
        player.cancel()
        when (cmd) {
            is RemoteCommand.SetMotor -> ble.setActuator(cmd.index - 1, cmd.level)
            is RemoteCommand.SetBoth -> { ble.setActuator(0, cmd.level); ble.setActuator(1, cmd.level) }
            RemoteCommand.Stop -> ble.stopAll()
        }
    }

    companion object {
        @Volatile
        private var instance: RemoteEngine? = null

        /** Singleton processus : créé une seule fois, survit aux Activity/ViewModel. */
        fun get(context: Context): RemoteEngine =
            instance ?: synchronized(this) {
                instance ?: RemoteEngine(context).also { instance = it }
            }
    }
}
