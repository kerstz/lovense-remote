package com.edge2.remote

import android.content.Context
import com.edge2.remote.ble.DiscoveredToy
import com.edge2.remote.ble.ToyManager
import com.edge2.remote.ble.ToyStatus
import com.edge2.remote.pattern.LovenseImporter
import com.edge2.remote.pattern.Pattern
import com.edge2.remote.pattern.PatternPlayer
import com.edge2.remote.pattern.PatternSink
import com.edge2.remote.pattern.PatternStep
import com.edge2.remote.remote.ControllerInfo
import com.edge2.remote.remote.NetworkUtils
import com.edge2.remote.remote.RemoteCommand
import com.edge2.remote.remote.RemoteServer
import com.edge2.remote.remote.SshTunnel
import com.edge2.remote.service.AppActions
import com.edge2.remote.service.RemoteForegroundService
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject

/** Why sharing failed (shown in the share dialog). */
enum class ShareError { NONE, BLOCKED, LOCKED }

/**
 * The app's **process-scoped** core (not tied to the Activity): owns the toys
 * (multi-brand, several at once), the pattern player, the sharing server and
 * the tunnel, plus the state exposed to the UI. Kept alive by
 * [RemoteForegroundService] while a session is active → control survives the
 * app being closed.
 *
 * [RemoteViewModel] is just a thin adapter on top of this singleton.
 */
class RemoteEngine private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val toyManager = ToyManager(appContext)

    /** Toy targeted by patterns / global presets: address, or null = all. */
    private val _target = MutableStateFlow<String?>(null)
    val target: StateFlow<String?> = _target.asStateFlow()

    private val player = PatternPlayer(object : PatternSink {
        override fun apply(m1: Int, m2: Int) {
            val f1 = m1 / 20f
            val f2 = m2 / 20f
            val t = _target.value
            if (t == null) { toyManager.setMotionAll(0, f1); toyManager.setMotionAll(1, f2) }
            else { toyManager.setMotion(t, 0, f1); toyManager.setMotion(t, 1, f2) }
        }
        override fun stopAll() = toyManager.stopAll()
    }, scope)

    private val importer = LovenseImporter()
    private val server = RemoteServer(
        appContext.assets,
        onCommand = { _, cmd -> applyRemote(cmd) },
        onLockout = { scope.launch { stopSharing(); _shareError.value = ShareError.LOCKED } },
    )
    private val tunnel = SshTunnel(appContext, scope)

    // --- Exposed state ---------------------------------------------------
    val toys: StateFlow<List<ToyStatus>> = toyManager.toys
    val discovered = toyManager.discovered
    val scanState = toyManager.scanState
    val playing: StateFlow<String?> = player.playing

    /** Authenticated remote controllers (approved or pending approval). */
    val controllers: StateFlow<List<ControllerInfo>> = server.controllers

    private val _linkMode = MutableStateFlow(false)
    val linkMode: StateFlow<Boolean> = _linkMode.asStateFlow()

    /** Sharing active (embedded server started) — drives the foreground service. */
    private val _sharing = MutableStateFlow(false)
    val sharing: StateFlow<Boolean> = _sharing.asStateFlow()

    /** PIN of the current share (to give the controller, separately from the link), or null. */
    private val _pin = MutableStateFlow<String?>(null)
    val pin: StateFlow<String?> = _pin.asStateFlow()

    private var expiryJob: Job? = null

    private val _shareUrl = MutableStateFlow<String?>(null)
    val shareUrl: StateFlow<String?> = _shareUrl.asStateFlow()

    /** Internet URL (localhost.run) once ready, or null. */
    private val _tunnelUrl = MutableStateFlow<String?>(null)
    val tunnelUrl: StateFlow<String?> = _tunnelUrl.asStateFlow()

    /** true while the internet tunnel is being set up (URL not ready yet). */
    private val _tunnelPreparing = MutableStateFlow(false)
    val tunnelPreparing: StateFlow<Boolean> = _tunnelPreparing.asStateFlow()

    /** The relay host key changed → tunnel refused (possible interception). */
    val tunnelHostKeyMismatch: StateFlow<Boolean> = tunnel.hostKeyMismatch

    private val _shareError = MutableStateFlow(ShareError.NONE)
    val shareError: StateFlow<ShareError> = _shareError.asStateFlow()

    private val _importedPatterns = MutableStateFlow<List<Pattern>>(emptyList())
    val importedPatterns: StateFlow<List<Pattern>> = _importedPatterns.asStateFlow()

    // --- Pattern recording (perform → save) -----------------------------
    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()
    private var recStart = 0L
    private val recBuf = mutableListOf<Triple<Long, Int, Int>>()
    private var lastBase = 0
    private var lastShaft = 0

    init {
        // Notification "Disconnect" button: stops sharing + every toy.
        AppActions.onStop = { stopSharing(); disconnectAll() }
        // Foreground service active while a toy is managed OR sharing is on.
        scope.launch {
            combine(toys, _sharing) { list, sharing ->
                if (list.isEmpty() && !sharing) null else list.joinToString(" · ") { it.displayName }.ifEmpty { "Remote" }
            }.distinctUntilChanged().collect { name ->
                if (name != null) RemoteForegroundService.start(appContext, name)
                else RemoteForegroundService.stop(appContext)
            }
        }
        // Tunnel ready → internet link.
        scope.launch {
            tunnel.publicUrl.collect { url ->
                if (url != null && _sharing.value) {
                    _tunnelUrl.value = "$url/s/${server.sessionId}"
                    _tunnelPreparing.value = false
                } else {
                    _tunnelUrl.value = null
                }
            }
        }
        scope.launch { tunnel.hostKeyMismatch.collect { if (it) _tunnelPreparing.value = false } }
        // Toy list pushed to controllers (selector on the web page).
        scope.launch {
            toys.map { list -> list.map { it.displayName to it.toy.motion.size } }
                .distinctUntilChanged()
                .collect { list ->
                    val json = buildJsonObject {
                        putJsonArray("toys") {
                            list.forEach { (name, n) -> addJsonObject { put("name", name); put("acts", n) } }
                        }
                    }
                    server.pushState(json.toString())
                }
        }
        // Target gone (toy removed) → back to "all".
        scope.launch {
            toys.collect { list -> _target.update { t -> t?.takeIf { a -> list.any { it.address == a } } } }
        }
    }

    // --- Toys ------------------------------------------------------------

    fun scan() = toyManager.startDiscovery()
    fun stopScan() = toyManager.stopDiscovery()
    fun connectTo(toy: DiscoveredToy) = toyManager.connectTo(toy)
    fun disconnect(address: String) = toyManager.disconnect(address)
    fun disconnectAll() { player.cancel(); toyManager.disconnectAll() }
    fun selectTarget(address: String?) { _target.value = address }
    fun toggleLink() { _linkMode.value = !_linkMode.value }
    fun reverse(address: String, index: Int) = toyManager.reverse(address, index)
    fun playPattern(pattern: Pattern) = player.play(pattern)
    fun playTease() = player.playTease()

    /** Global STOP: cancels any pattern and stops EVERY toy. */
    fun stopAll() { player.cancel(); toyManager.stopAll() }

    fun setActuator(address: String, index: Int, fraction: Float) {
        player.cancel()
        toyManager.setFraction(address, index, fraction)
        // Recording follows the first two motion actuators (pattern m1/m2).
        val motion = toys.value.firstOrNull { it.address == address }?.toy?.motion.orEmpty()
        val lvl = (fraction.coerceIn(0f, 1f) * 20).roundToInt()
        when (motion.indexOf(index)) { 0 -> capture(base = lvl); 1 -> capture(shaft = lvl) }
    }

    fun setXY(address: String, base: Float, shaft: Float) {
        player.cancel()
        toyManager.setMotion(address, 0, base)
        toyManager.setMotion(address, 1, shaft)
        capture((base.coerceIn(0f, 1f) * 20).roundToInt(), (shaft.coerceIn(0f, 1f) * 20).roundToInt())
    }

    /** Every motion actuator of [address] (null = of every toy) to [fraction]. */
    fun setAll(address: String?, fraction: Float) {
        player.cancel()
        if (address == null) toyManager.setAllFraction(fraction) else toyManager.setAllMotion(address, fraction)
        val lvl = (fraction.coerceIn(0f, 1f) * 20).roundToInt()
        capture(base = lvl, shaft = lvl)
    }

    // --- Recording -------------------------------------------------------

    /** Starts recording: your gestures become a pattern. */
    fun startRecording() {
        recBuf.clear()
        recStart = System.currentTimeMillis()
        _recording.value = true
        capture()
    }

    /** Stops recording and saves the pattern (≥ 2 points). */
    fun stopRecording() {
        _recording.value = false
        if (recBuf.size < 2) return
        val steps = recBuf.zipWithNext { a, b ->
            PatternStep(m1 = a.second, m2 = a.third, durationMs = (b.first - a.first).coerceIn(50, 5000))
        }
        if (steps.isEmpty()) return
        val n = _importedPatterns.value.count { it.name.startsWith("Custom") } + 1
        _importedPatterns.update { it + Pattern(name = "Custom $n", steps = steps, loop = true) }
        recBuf.clear()
    }

    private fun capture(base: Int? = null, shaft: Int? = null) {
        if (base != null) lastBase = base
        if (shaft != null) lastShaft = shaft
        // Memory bound: ~1 h of dense gestures.
        if (_recording.value && recBuf.size < 20_000) {
            recBuf.add(Triple(System.currentTimeMillis() - recStart, lastBase, lastShaft))
        }
    }

    // --- Sharing ---------------------------------------------------------

    fun startSharing() {
        if (_sharing.value) return
        _shareError.value = ShareError.NONE
        // LAN: Wi-Fi/Ethernet only (null on 4G) → the server listens ONLY there + 127.0.0.1.
        val ip = NetworkUtils.lanIpv4()
        if (!server.start(ip)) { _shareError.value = ShareError.BLOCKED; return }
        _pin.value = server.pin
        _sharing.value = true
        _shareUrl.value = ip?.let { "http://$it:${server.port}/s/${server.sessionId}" }
        // Internet tunnel (SSH/localhost.run) → works over 4G. URL ready in a few seconds.
        if (!tunnel.hostKeyMismatch.value) {
            _tunnelPreparing.value = true
            tunnel.start(server.port)
        }
        // Auto-expiry: cuts access after 30 min.
        expiryJob?.cancel()
        expiryJob = scope.launch { delay(30 * 60_000L); stopSharing() }
    }

    /** The host approves controller [id]. */
    fun approveController(id: Int) = server.approve(id)

    /** The host refuses / kicks controller [id] (sharing continues for the others). */
    fun refuseController(id: Int) = server.kick(id)

    /** After a legitimate relay key change, the user re-accepts it. */
    fun trustNewRelayKey() {
        tunnel.trustNewHostKey()
        if (_sharing.value) { _tunnelPreparing.value = true; tunnel.start(server.port) }
    }

    fun stopSharing() {
        expiryJob?.cancel()
        expiryJob = null
        tunnel.stop()
        server.stop()
        _sharing.value = false
        _pin.value = null
        _shareUrl.value = null
        _tunnelUrl.value = null
        _tunnelPreparing.value = false
    }

    fun importFromUrl(url: String) {
        scope.launch {
            importer.fromUrl(url.trim())?.let { p -> _importedPatterns.update { it + p } }
        }
    }

    fun importFromText(content: String) {
        importer.fromText(content.trim())?.let { p -> _importedPatterns.update { it + p } }
    }

    /**
     * Command from an APPROVED remote controller (the server filters the others).
     * Shared 0..20 scale → fraction, applied to each toy's own range.
     */
    private fun applyRemote(cmd: RemoteCommand) {
        val list = toys.value
        val addr: String? = cmd.target?.let { i -> list.getOrNull(i)?.address ?: return }
        player.cancel()
        when (cmd) {
            is RemoteCommand.SetMotor -> {
                val f = cmd.level / RemoteCommand.MAX_LEVEL.toFloat()
                if (addr == null) toyManager.setMotionAll(cmd.index - 1, f)
                else toyManager.setMotion(addr, cmd.index - 1, f)
            }
            is RemoteCommand.SetBoth -> {
                val f = cmd.level / RemoteCommand.MAX_LEVEL.toFloat()
                if (addr == null) toyManager.setAllFraction(f) else toyManager.setAllMotion(addr, f)
            }
            is RemoteCommand.Stop -> if (addr == null) toyManager.stopAll() else toyManager.stop(addr)
        }
    }

    companion object {
        @Volatile
        private var instance: RemoteEngine? = null

        /** Process singleton: created once, outlives Activities/ViewModels. */
        fun get(context: Context): RemoteEngine =
            instance ?: synchronized(this) {
                instance ?: RemoteEngine(context).also { instance = it }
            }
    }
}
