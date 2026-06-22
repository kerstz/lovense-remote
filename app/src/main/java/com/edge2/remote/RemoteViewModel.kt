package com.edge2.remote

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.edge2.remote.ble.Edge2BleManager
import com.edge2.remote.ble.Motor
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.edge2.remote.pattern.LovenseImporter
import com.edge2.remote.pattern.Pattern
import com.edge2.remote.pattern.PatternPlayer
import com.edge2.remote.remote.NetworkUtils
import com.edge2.remote.remote.RelayConfig
import com.edge2.remote.remote.RemoteCommand
import com.edge2.remote.remote.RemoteServer
import com.edge2.remote.remote.RemoteTunnel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Détient la couche BLE et le lecteur de patterns, survit aux rotations.
 * Expose l'état à Compose et route les actions UI vers le BLE.
 */
class RemoteViewModel(app: Application) : AndroidViewModel(app) {

    private val ble = Edge2BleManager(app)
    private val player = PatternPlayer(ble, viewModelScope)

    val connectionState = ble.connectionState
    val motorLevels = ble.motorLevels
    val playing: StateFlow<String?> = player.playing

    /** Mode Link : un seul geste pilote les deux moteurs ensemble. */
    private val _linkMode = MutableStateFlow(false)
    val linkMode: StateFlow<Boolean> = _linkMode.asStateFlow()

    // --- Partage à distance (host) ---------------------------------------

    private val server = RemoteServer(app.assets) { cmd -> applyRemote(cmd) }
    private val tunnel = RemoteTunnel(viewModelScope) { cmd -> applyRemote(cmd) }

    /** URL de partage LAN `http://<ip-lan>:<port>/s/<id>`, ou null si pas de WiFi. */
    private val _shareUrl = MutableStateFlow<String?>(null)
    val shareUrl: StateFlow<String?> = _shareUrl.asStateFlow()

    /** URL de partage internet `https://<relais>/s/<id>`, ou null si tunnel inactif. */
    private val _tunnelUrl = MutableStateFlow<String?>(null)
    val tunnelUrl: StateFlow<String?> = _tunnelUrl.asStateFlow()

    /** true quand le téléphone est bien rattaché au relais (joignable depuis internet). */
    val tunnelConnected: StateFlow<Boolean> = tunnel.connected

    fun startSharing() {
        server.start()
        val ip = NetworkUtils.lanIpv4()
        _shareUrl.value = ip?.let { "http://$it:${server.port}/s/${server.sessionId}" }

        // Tunnel internet : actif seulement si un relais est configuré (RelayConfig).
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

    /** Applique une commande reçue d'un contrôleur distant (web ou app). */
    private fun applyRemote(cmd: RemoteCommand) {
        player.cancel()
        when (cmd) {
            is RemoteCommand.SetMotor ->
                Motor.entries.firstOrNull { it.index == cmd.index }
                    ?.let { ble.setMotor(it, cmd.level) }
            is RemoteCommand.SetBoth -> {
                ble.setMotor(Motor.BASE, cmd.level)
                ble.setMotor(Motor.SHAFT, cmd.level)
            }
            RemoteCommand.Stop -> ble.stopAll()
        }
    }

    fun connect() = ble.connect()
    fun disconnect() = ble.disconnect()

    fun toggleLink() { _linkMode.value = !_linkMode.value }

    /** Réglage manuel d'un moteur (fraction 0..1). Interrompt un pattern en cours. */
    fun setMotor(motor: Motor, fraction: Float) {
        player.cancel()
        ble.setMotor(motor, fraction)
    }

    /** Réglage manuel des deux moteurs à la même valeur (mode Link / presets). */
    fun setBoth(fraction: Float) {
        player.cancel()
        ble.setMotor(Motor.BASE, fraction)
        ble.setMotor(Motor.SHAFT, fraction)
    }

    /** Réglage du pad XY : base et tige indépendants (interrompt un pattern). */
    fun setXY(base: Float, tige: Float) {
        player.cancel()
        ble.setMotor(Motor.BASE, base)
        ble.setMotor(Motor.SHAFT, tige)
    }

    fun playPattern(pattern: Pattern) = player.play(pattern)

    // --- Import de patterns Lovense (.ta public) -------------------------

    private val importer = LovenseImporter()

    private val _importedPatterns = MutableStateFlow<List<Pattern>>(emptyList())
    val importedPatterns: StateFlow<List<Pattern>> = _importedPatterns.asStateFlow()

    /** Importe depuis une URL `.ta` publique (téléchargement asynchrone). */
    fun importFromUrl(url: String) {
        viewModelScope.launch {
            importer.fromUrl(url.trim())?.let { p -> _importedPatterns.update { it + p } }
        }
    }

    /** Importe depuis un contenu `.ta` collé. */
    fun importFromText(content: String) {
        importer.fromText(content.trim())?.let { p -> _importedPatterns.update { it + p } }
    }

    /** Stoppe tout : lecture de pattern + moteurs. */
    fun stopAll() = player.stop()

    override fun onCleared() {
        super.onCleared()
        server.stop()
        tunnel.release()
        importer.close()
        ble.release()
    }
}
