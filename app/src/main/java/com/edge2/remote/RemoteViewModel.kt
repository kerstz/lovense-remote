package com.edge2.remote

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.edge2.remote.ble.DiscoveredToy
import com.edge2.remote.pattern.Pattern

/**
 * Thin adapter between Compose and [RemoteEngine] (the process-scoped core).
 * The ViewModel owns NOTHING: it delegates everything to the singleton, which
 * outlives the Activity → control continues in the background / app closed.
 */
class RemoteViewModel(app: Application) : AndroidViewModel(app) {

    private val engine = RemoteEngine.get(app)

    // State (engine flows)
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

    // Actions — toys
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
    fun setXY(address: String, base: Float, shaft: Float) = engine.setXY(address, base, shaft)

    // Actions — patterns
    fun playPattern(pattern: Pattern) = engine.playPattern(pattern)
    fun playTease() = engine.playTease()
    fun startRecording() = engine.startRecording()
    fun stopRecording() = engine.stopRecording()
    fun stopAll() = engine.stopAll()

    // Actions — sharing
    fun startSharing() = engine.startSharing()
    fun stopSharing() = engine.stopSharing()
    fun approveController(id: Int) = engine.approveController(id)
    fun refuseController(id: Int) = engine.refuseController(id)
    fun trustNewRelayKey() = engine.trustNewRelayKey()
    fun importFromUrl(url: String) = engine.importFromUrl(url)
    fun importFromText(content: String) = engine.importFromText(content)

    // No onCleared releasing the engine: it lives at process scope (the
    // foreground service keeps it alive while a session is open).
}
