package com.edge2.remote

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.edge2.remote.ble.Edge2BleManager
import com.edge2.remote.ble.Motor
import com.edge2.remote.pattern.Pattern
import com.edge2.remote.pattern.PatternPlayer
import com.edge2.remote.remote.NetworkUtils
import com.edge2.remote.remote.RemoteCommand
import com.edge2.remote.remote.RemoteServer
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

    /** URL de partage `http://<ip-lan>:<port>/s/<id>`, ou null si pas de partage. */
    private val _shareUrl = MutableStateFlow<String?>(null)
    val shareUrl: StateFlow<String?> = _shareUrl.asStateFlow()

    fun startSharing() {
        server.start()
        val ip = NetworkUtils.lanIpv4()
        _shareUrl.value = if (ip != null) {
            "http://$ip:${server.port}/s/${server.sessionId}"
        } else {
            null // pas de réseau local détecté
        }
    }

    fun stopSharing() {
        server.stop()
        _shareUrl.value = null
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

    fun playPattern(pattern: Pattern) = player.play(pattern)

    /** Stoppe tout : lecture de pattern + moteurs. */
    fun stopAll() = player.stop()

    override fun onCleared() {
        super.onCleared()
        server.stop()
        ble.release()
    }
}
