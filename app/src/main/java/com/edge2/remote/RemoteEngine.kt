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

/** Raison d'un échec de partage (affichée dans la popup). */
enum class ShareError { NONE, BLOCKED, LOCKED }

/**
 * Cœur de l'app au **scope processus** (pas lié à l'Activity) : détient les
 * jouets (multi-marques, plusieurs à la fois), le lecteur de patterns, le serveur
 * de partage et le tunnel, plus l'état exposé à l'UI. Maintenu vivant par
 * [RemoteForegroundService] tant qu'une session est active → le contrôle survit
 * à la fermeture de l'app.
 *
 * [RemoteViewModel] n'est qu'un adaptateur mince au-dessus de ce singleton.
 */
class RemoteEngine private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val toyManager = ToyManager(appContext)

    /** Jouet ciblé par les patterns / presets globaux : adresse, ou null = tous. */
    private val _target = MutableStateFlow<String?>(null)
    val target: StateFlow<String?> = _target.asStateFlow()

    private val player = PatternPlayer(object : PatternSink {
        override fun apply(m1: Int, m2: Int) {
            val f1 = m1 / 20f
            val f2 = m2 / 20f
            val t = _target.value
            if (t == null) { toyManager.setFractionAll(0, f1); toyManager.setFractionAll(1, f2) }
            else { toyManager.setFraction(t, 0, f1); toyManager.setFraction(t, 1, f2) }
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

    // --- État exposé -----------------------------------------------------
    val toys: StateFlow<List<ToyStatus>> = toyManager.toys
    val discovered = toyManager.discovered
    val scanState = toyManager.scanState
    val playing: StateFlow<String?> = player.playing

    /** Contrôleurs distants authentifiés (acceptés ou en attente d'accord). */
    val controllers: StateFlow<List<ControllerInfo>> = server.controllers

    private val _linkMode = MutableStateFlow(false)
    val linkMode: StateFlow<Boolean> = _linkMode.asStateFlow()

    /** Partage actif (serveur embarqué démarré) — pilote le service premier-plan. */
    private val _sharing = MutableStateFlow(false)
    val sharing: StateFlow<Boolean> = _sharing.asStateFlow()

    /** Code PIN du partage en cours (à communiquer au contrôleur, hors du lien), ou null. */
    private val _pin = MutableStateFlow<String?>(null)
    val pin: StateFlow<String?> = _pin.asStateFlow()

    private var expiryJob: Job? = null

    private val _shareUrl = MutableStateFlow<String?>(null)
    val shareUrl: StateFlow<String?> = _shareUrl.asStateFlow()

    /** URL internet (localhost.run) prête, ou null. */
    private val _tunnelUrl = MutableStateFlow<String?>(null)
    val tunnelUrl: StateFlow<String?> = _tunnelUrl.asStateFlow()

    /** true tant que le tunnel internet se prépare (URL pas encore prête). */
    private val _tunnelPreparing = MutableStateFlow(false)
    val tunnelPreparing: StateFlow<Boolean> = _tunnelPreparing.asStateFlow()

    /** La clé d'hôte du relais a changé → tunnel refusé (interception possible). */
    val tunnelHostKeyMismatch: StateFlow<Boolean> = tunnel.hostKeyMismatch

    private val _shareError = MutableStateFlow(ShareError.NONE)
    val shareError: StateFlow<ShareError> = _shareError.asStateFlow()

    private val _importedPatterns = MutableStateFlow<List<Pattern>>(emptyList())
    val importedPatterns: StateFlow<List<Pattern>> = _importedPatterns.asStateFlow()

    // --- Enregistrement de pattern (perform → save) ----------------------
    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()
    private var recStart = 0L
    private val recBuf = mutableListOf<Triple<Long, Int, Int>>()
    private var lastBase = 0
    private var lastTige = 0

    init {
        // Bouton « Couper » de la notification : coupe partage + tous les jouets.
        AppActions.onStop = { stopSharing(); disconnectAll() }
        // Service premier-plan actif tant qu'un jouet est géré OU en partage.
        scope.launch {
            combine(toys, _sharing) { list, sharing ->
                if (list.isEmpty() && !sharing) null else list.joinToString(" · ") { it.displayName }.ifEmpty { "Remote" }
            }.distinctUntilChanged().collect { name ->
                if (name != null) RemoteForegroundService.start(appContext, name)
                else RemoteForegroundService.stop(appContext)
            }
        }
        // Tunnel prêt → lien internet.
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
        // Liste des jouets poussée aux contrôleurs (sélecteur côté page web).
        scope.launch {
            toys.map { list -> list.map { it.displayName to it.toy.actuators.size } }
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
        // Cible disparue (jouet retiré) → retour à « tous ».
        scope.launch {
            toys.collect { list -> _target.update { t -> t?.takeIf { a -> list.any { it.address == a } } } }
        }
    }

    // --- Jouets ----------------------------------------------------------

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

    /** STOP global : annule tout pattern et coupe TOUS les jouets. */
    fun stopAll() { player.cancel(); toyManager.stopAll() }

    fun setActuator(address: String, index: Int, fraction: Float) {
        player.cancel()
        toyManager.setFraction(address, index, fraction)
        val lvl = (fraction.coerceIn(0f, 1f) * 20).roundToInt()
        if (index == 0) capture(base = lvl) else if (index == 1) capture(tige = lvl)
    }

    fun setXY(address: String, base: Float, tige: Float) {
        player.cancel()
        toyManager.setFraction(address, 0, base)
        toyManager.setFraction(address, 1, tige)
        capture((base.coerceIn(0f, 1f) * 20).roundToInt(), (tige.coerceIn(0f, 1f) * 20).roundToInt())
    }

    /** Tous les actionneurs de [address] (null = de tous les jouets) à [fraction]. */
    fun setAll(address: String?, fraction: Float) {
        player.cancel()
        if (address == null) toyManager.setAllFraction(fraction)
        else toys.value.firstOrNull { it.address == address }?.toy?.actuators?.indices?.forEach {
            toyManager.setFraction(address, it, fraction)
        }
        val lvl = (fraction.coerceIn(0f, 1f) * 20).roundToInt()
        capture(base = lvl, tige = lvl)
    }

    // --- Enregistrement --------------------------------------------------

    /** Démarre l'enregistrement : tes gestes deviennent un pattern. */
    fun startRecording() {
        recBuf.clear()
        recStart = System.currentTimeMillis()
        _recording.value = true
        capture()
    }

    /** Arrête l'enregistrement et sauve le pattern (≥ 2 points). */
    fun stopRecording() {
        _recording.value = false
        if (recBuf.size < 2) return
        val steps = recBuf.zipWithNext { a, b ->
            PatternStep(m1 = a.second, m2 = a.third, durationMs = (b.first - a.first).coerceIn(50, 5000))
        }
        if (steps.isEmpty()) return
        val n = _importedPatterns.value.count { it.name.startsWith("Perso") } + 1
        _importedPatterns.update { it + Pattern(name = "Perso $n", steps = steps, loop = true) }
        recBuf.clear()
    }

    private fun capture(base: Int? = null, tige: Int? = null) {
        if (base != null) lastBase = base
        if (tige != null) lastTige = tige
        // Borne mémoire : ~1 h de gestes denses.
        if (_recording.value && recBuf.size < 20_000) {
            recBuf.add(Triple(System.currentTimeMillis() - recStart, lastBase, lastTige))
        }
    }

    // --- Partage ---------------------------------------------------------

    fun startSharing() {
        if (_sharing.value) return
        _shareError.value = ShareError.NONE
        // LAN : Wi-Fi/Ethernet seulement (null en 4G) → le serveur n'écoute QUE là + 127.0.0.1.
        val ip = NetworkUtils.lanIpv4()
        if (!server.start(ip)) { _shareError.value = ShareError.BLOCKED; return }
        _pin.value = server.pin
        _sharing.value = true
        _shareUrl.value = ip?.let { "http://$it:${server.port}/s/${server.sessionId}" }
        // Tunnel internet (SSH/localhost.run) → marche en 4G. URL prête en quelques s.
        if (!tunnel.hostKeyMismatch.value) {
            _tunnelPreparing.value = true
            tunnel.start(server.port)
        }
        // Expiration auto : coupe l'accès après 30 min.
        expiryJob?.cancel()
        expiryJob = scope.launch { delay(30 * 60_000L); stopSharing() }
    }

    /** L'hôte accepte le contrôleur [id]. */
    fun approveController(id: Int) = server.approve(id)

    /** L'hôte refuse / éjecte le contrôleur [id] (le partage continue pour les autres). */
    fun refuseController(id: Int) = server.kick(id)

    /** Après un changement légitime de clé du relais, l'utilisateur la réaccepte. */
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
     * Commande d'un contrôleur distant ACCEPTÉ (le serveur filtre les autres).
     * Échelle commune 0..20 → fraction, appliquée à la plage propre de chaque jouet.
     */
    private fun applyRemote(cmd: RemoteCommand) {
        val list = toys.value
        val addr: String? = cmd.target?.let { i -> list.getOrNull(i)?.address ?: return }
        player.cancel()
        when (cmd) {
            is RemoteCommand.SetMotor -> {
                val f = cmd.level / RemoteCommand.MAX_LEVEL.toFloat()
                if (addr == null) toyManager.setFractionAll(cmd.index - 1, f)
                else toyManager.setFraction(addr, cmd.index - 1, f)
            }
            is RemoteCommand.SetBoth -> {
                val f = cmd.level / RemoteCommand.MAX_LEVEL.toFloat()
                if (addr == null) toyManager.setAllFraction(f)
                else list.first { it.address == addr }.toy.actuators.indices.forEach { toyManager.setFraction(addr, it, f) }
            }
            is RemoteCommand.Stop -> if (addr == null) toyManager.stopAll() else toyManager.stop(addr)
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
