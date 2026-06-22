package com.edge2.remote

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.edge2.remote.ble.DiscoveredToy
import com.edge2.remote.ble.Edge2BleManager
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

    /** Niveau courant de chaque actionneur du toy connecté (feedback UI). */
    val actuatorLevels = ble.actuatorLevels

    /** Toys Lovense visibles pendant le scan (sélection à la connexion). */
    val discovered = ble.discovered
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

    /**
     * Applique une commande reçue d'un contrôleur distant. Les index M1/M2 du
     * protocole texte mappent les actionneurs 0/1 du toy (pour les toys mono-
     * actionneur, M2 est simplement ignoré).
     */
    private fun applyRemote(cmd: RemoteCommand) {
        player.cancel()
        when (cmd) {
            is RemoteCommand.SetMotor -> ble.setActuator(cmd.index - 1, cmd.level)
            is RemoteCommand.SetBoth -> {
                ble.setActuator(0, cmd.level)
                ble.setActuator(1, cmd.level)
            }
            RemoteCommand.Stop -> ble.stopAll()
        }
    }

    /** Démarre le scan : remplit [discovered] avec les toys visibles. */
    fun scan() = ble.startDiscovery()

    /** Connecte le toy choisi dans la liste. */
    fun connectTo(toy: DiscoveredToy) = ble.connectTo(toy)

    fun disconnect() = ble.disconnect()

    fun toggleLink() { _linkMode.value = !_linkMode.value }

    /** Réglage manuel d'un actionneur (fraction 0..1). Interrompt un pattern. */
    fun setActuator(index: Int, fraction: Float) {
        player.cancel()
        ble.setActuatorFraction(index, fraction)
    }

    /** Inverse le sens d'un actionneur rotatif (Nora). */
    fun reverse(index: Int) = ble.reverse(index)

    /** Réglage de tous les actionneurs à la même valeur (mode Link / presets). */
    fun setBoth(fraction: Float) {
        player.cancel()
        ble.setAllFraction(fraction)
    }

    /** Pad XY : actionneur 0 (base) et 1 (tige) indépendants. Interrompt un pattern. */
    fun setXY(base: Float, tige: Float) {
        player.cancel()
        ble.setActuatorFraction(0, base)
        ble.setActuatorFraction(1, tige)
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
