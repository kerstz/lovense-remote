package com.edge2.remote

import android.content.Context
import com.edge2.remote.ble.ConnectionState
import com.edge2.remote.ble.DiscoveredToy
import com.edge2.remote.ble.Edge2BleManager
import com.edge2.remote.pattern.LovenseImporter
import com.edge2.remote.pattern.Pattern
import com.edge2.remote.pattern.PatternPlayer
import com.edge2.remote.pattern.PatternStep
import kotlin.math.roundToInt
import com.edge2.remote.remote.NetworkUtils
import com.edge2.remote.remote.RemoteCommand
import com.edge2.remote.remote.RemoteServer
import com.edge2.remote.remote.SshTunnel
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
    private val tunnel = SshTunnel(appContext, scope)

    // --- État exposé -----------------------------------------------------
    val connectionState = ble.connectionState
    val actuatorLevels = ble.actuatorLevels
    val discovered = ble.discovered
    val playing: StateFlow<String?> = player.playing

    /** Nombre de contrôleurs distants connectés (>0 = « on te contrôle »). */
    val controllers: StateFlow<Int> = server.controllers

    private val _linkMode = MutableStateFlow(false)
    val linkMode: StateFlow<Boolean> = _linkMode.asStateFlow()

    /** Partage actif (serveur embarqué démarré) — pilote le service premier-plan. */
    private val _sharing = MutableStateFlow(false)
    val sharing: StateFlow<Boolean> = _sharing.asStateFlow()

    /** Code PIN du partage en cours (à communiquer au contrôleur), ou null. */
    private val _pin = MutableStateFlow<String?>(null)
    val pin: StateFlow<String?> = _pin.asStateFlow()

    /** L'hôte a-t-il accepté que le contrôleur prenne la main ? (gate des commandes) */
    private val _approved = MutableStateFlow(false)
    val approved: StateFlow<Boolean> = _approved.asStateFlow()

    private var expiryJob: kotlinx.coroutines.Job? = null

    private val _shareUrl = MutableStateFlow<String?>(null)
    val shareUrl: StateFlow<String?> = _shareUrl.asStateFlow()

    /** URL internet (trycloudflare) prête, ou null. */
    private val _tunnelUrl = MutableStateFlow<String?>(null)
    val tunnelUrl: StateFlow<String?> = _tunnelUrl.asStateFlow()

    /** true = lien internet prêt ; false pendant la préparation cloudflared. */
    private val _tunnelConnected = MutableStateFlow(false)
    val tunnelConnected: StateFlow<Boolean> = _tunnelConnected.asStateFlow()

    /** true tant que le tunnel internet se prépare (URL pas encore prête). */
    private val _tunnelPreparing = MutableStateFlow(false)
    val tunnelPreparing: StateFlow<Boolean> = _tunnelPreparing.asStateFlow()

    /** true si le partage a échoué (réseau bloqué pour l'app). */
    private val _shareError = MutableStateFlow(false)
    val shareError: StateFlow<Boolean> = _shareError.asStateFlow()

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
        // Bouton « Couper » de la notification.
        AppActions.onStop = { stopSharing(); disconnect() }
        // Service premier-plan actif tant qu'on est connecté OU en partage :
        // garde le processus (BLE + serveur + tunnel) vivant en arrière-plan
        // et même après fermeture de l'app.
        scope.launch {
            combine(connectionState, _sharing) { st, sharing ->
                st is ConnectionState.Connected || sharing
            }.collect { active ->
                if (active) RemoteForegroundService.start(appContext, currentName())
                else RemoteForegroundService.stop(appContext)
            }
        }
        // Quand le tunnel publie l'URL publique → lien internet prêt.
        scope.launch {
            tunnel.publicUrl.collect { url ->
                if (url != null && _sharing.value) {
                    _tunnelUrl.value = "$url/s/${server.sessionId}"
                    _tunnelConnected.value = true
                    _tunnelPreparing.value = false
                } else {
                    _tunnelUrl.value = null
                    _tunnelConnected.value = false
                }
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
    fun playTease() = player.playTease()
    fun stopAll() = player.stop()

    fun setActuator(index: Int, fraction: Float) {
        player.cancel()
        ble.setActuatorFraction(index, fraction)
        val lvl = (fraction.coerceIn(0f, 1f) * 20).roundToInt()
        if (index == 0) capture(base = lvl) else if (index == 1) capture(tige = lvl)
    }

    fun setBoth(fraction: Float) {
        player.cancel()
        ble.setAllFraction(fraction)
        val lvl = (fraction.coerceIn(0f, 1f) * 20).roundToInt()
        capture(base = lvl, tige = lvl)
    }

    fun setXY(base: Float, tige: Float) {
        player.cancel()
        ble.setActuatorFraction(0, base)
        ble.setActuatorFraction(1, tige)
        capture((base.coerceIn(0f, 1f) * 20).roundToInt(), (tige.coerceIn(0f, 1f) * 20).roundToInt())
    }

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
        if (_recording.value) recBuf.add(Triple(System.currentTimeMillis() - recStart, lastBase, lastTige))
    }

    fun startSharing() {
        // Code PIN obligatoire (4 chiffres) — à dire au contrôleur, hors du lien.
        val code = "%04d".format(kotlin.random.Random.nextInt(10000))
        server.pin = code
        // Réseau bloqué (pare-feu / autorisation coupée) → on n'amorce rien (pas de crash).
        if (!server.start()) { _shareError.value = true; return }
        _shareError.value = false
        _pin.value = code
        _approved.value = false
        _sharing.value = true
        // LAN (Wi-Fi/Ethernet seulement ; null en 4G).
        val ip = NetworkUtils.lanIpv4()
        _shareUrl.value = ip?.let { "http://$it:${server.port}/s/${server.sessionId}" }
        // Tunnel internet (SSH/localhost.run) → marche en 4G. URL prête en quelques s.
        if (tunnel.available) {
            _tunnelPreparing.value = true
            tunnel.start(server.port)
        }
        // Expiration auto : coupe l'accès après 30 min.
        expiryJob?.cancel()
        expiryJob = scope.launch { kotlinx.coroutines.delay(30 * 60_000L); stopSharing() }
    }

    /** L'hôte accepte que le contrôleur prenne la main. */
    fun approveControl() { _approved.value = true }

    /** L'hôte refuse → coupe l'accès (arrête le partage). */
    fun refuseControl() = stopSharing()

    fun stopSharing() {
        expiryJob?.cancel()
        expiryJob = null
        tunnel.stop()
        server.stop()
        server.pin = null
        _sharing.value = false
        _pin.value = null
        _approved.value = false
        _shareUrl.value = null
        _tunnelUrl.value = null
        _tunnelConnected.value = false
        _tunnelPreparing.value = false
        _shareError.value = false
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
        // Gate : tant que l'hôte n'a pas accepté, on ignore les commandes distantes.
        if (!_approved.value) return
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
