package com.edge2.remote

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.edge2.remote.ble.DiscoveredToy
import com.edge2.remote.pattern.Pattern

/**
 * Adaptateur mince entre Compose et [RemoteEngine] (le cœur au scope processus).
 * Le ViewModel ne possède RIEN : il délègue tout au singleton, qui survit à la
 * destruction de l'Activity → le contrôle continue en arrière-plan / app fermée.
 */
class RemoteViewModel(app: Application) : AndroidViewModel(app) {

    private val engine = RemoteEngine.get(app)

    // État (flows du moteur)
    val connectionState = engine.connectionState
    val actuatorLevels = engine.actuatorLevels
    val discovered = engine.discovered
    val playing = engine.playing
    val recording = engine.recording
    val controllers = engine.controllers
    val linkMode = engine.linkMode
    val shareUrl = engine.shareUrl
    val tunnelUrl = engine.tunnelUrl
    val tunnelConnected = engine.tunnelConnected
    val tunnelPreparing = engine.tunnelPreparing
    val shareError = engine.shareError
    val sharing = engine.sharing
    val pin = engine.pin
    val approved = engine.approved
    val importedPatterns = engine.importedPatterns

    // Actions
    fun scan() = engine.scan()
    fun connectTo(toy: DiscoveredToy) = engine.connectTo(toy)
    fun disconnect() = engine.disconnect()
    fun toggleLink() = engine.toggleLink()
    fun setActuator(index: Int, fraction: Float) = engine.setActuator(index, fraction)
    fun reverse(index: Int) = engine.reverse(index)
    fun setBoth(fraction: Float) = engine.setBoth(fraction)
    fun setXY(base: Float, tige: Float) = engine.setXY(base, tige)
    fun playPattern(pattern: Pattern) = engine.playPattern(pattern)
    fun playTease() = engine.playTease()
    fun startRecording() = engine.startRecording()
    fun stopRecording() = engine.stopRecording()
    fun stopAll() = engine.stopAll()
    fun startSharing() = engine.startSharing()
    fun stopSharing() = engine.stopSharing()
    fun approveControl() = engine.approveControl()
    fun refuseControl() = engine.refuseControl()
    fun importFromUrl(url: String) = engine.importFromUrl(url)
    fun importFromText(content: String) = engine.importFromText(content)

    // Pas d'onCleared qui libère le moteur : il vit au scope processus (le service
    // premier-plan le maintient actif tant qu'une session est ouverte).
}
