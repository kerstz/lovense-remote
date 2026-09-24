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
    val toys = engine.toys
    val discovered = engine.discovered
    val scanState = engine.scanState
    val target = engine.target
    val playing = engine.playing
    val recording = engine.recording
    val controllers = engine.controllers
    val linkMode = engine.linkMode
    val shareUrl = engine.shareUrl
    val tunnelUrl = engine.tunnelUrl
    val tunnelPreparing = engine.tunnelPreparing
    val tunnelHostKeyMismatch = engine.tunnelHostKeyMismatch
    val shareError = engine.shareError
    val sharing = engine.sharing
    val pin = engine.pin
    val importedPatterns = engine.importedPatterns

    // Actions — jouets
    fun scan() = engine.scan()
    fun stopScan() = engine.stopScan()
    fun connectTo(toy: DiscoveredToy) = engine.connectTo(toy)
    fun disconnect(address: String) = engine.disconnect(address)
    fun disconnectAll() = engine.disconnectAll()
    fun selectTarget(address: String?) = engine.selectTarget(address)
    fun toggleLink() = engine.toggleLink()
    fun setActuator(address: String, index: Int, fraction: Float) = engine.setActuator(address, index, fraction)
    fun reverse(address: String, index: Int) = engine.reverse(address, index)
    fun setAll(address: String?, fraction: Float) = engine.setAll(address, fraction)
    fun setXY(address: String, base: Float, tige: Float) = engine.setXY(address, base, tige)

    // Actions — patterns
    fun playPattern(pattern: Pattern) = engine.playPattern(pattern)
    fun playTease() = engine.playTease()
    fun startRecording() = engine.startRecording()
    fun stopRecording() = engine.stopRecording()
    fun stopAll() = engine.stopAll()

    // Actions — partage
    fun startSharing() = engine.startSharing()
    fun stopSharing() = engine.stopSharing()
    fun approveController(id: Int) = engine.approveController(id)
    fun refuseController(id: Int) = engine.refuseController(id)
    fun trustNewRelayKey() = engine.trustNewRelayKey()
    fun importFromUrl(url: String) = engine.importFromUrl(url)
    fun importFromText(content: String) = engine.importFromText(content)

    // Pas d'onCleared qui libère le moteur : il vit au scope processus (le service
    // premier-plan le maintient actif tant qu'une session est ouverte).
}
