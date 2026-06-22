package com.edge2.remote.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.edge2.remote.R
import java.util.concurrent.atomic.AtomicIntegerArray
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Couche BLE pour le Lovense Edge 2.
 *
 * Responsabilités :
 *  - scan d'un toy `LVS-…` puis connexion GATT,
 *  - cycle de vie GATT (MTU, découverte services, activation notifications,
 *    handshake `DeviceType;`),
 *  - API haut niveau : [setMotor] / [stopAll],
 *  - reconnexion automatique avec back-off,
 *  - états exposés en [StateFlow] pour Compose.
 *
 * Toutes les écritures passent par une file sérialisée + coalescée : si le
 * slider bouge 50×/s, seule la dernière valeur par moteur est réellement
 * envoyée → latence minimale, pas d'accumulation.
 *
 * Le contexte appelant doit détenir BLUETOOTH_SCAN + BLUETOOTH_CONNECT.
 */
/** Nombre max d'actionneurs gérés (le toy le plus complexe en a ~3). */
private const val MAX_ACTUATORS = 4

@SuppressLint("MissingPermission")
class Edge2BleManager(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Chaîne localisée (les messages d'erreur sont affichés à l'écran). */
    private fun str(resId: Int, vararg args: Any): String = appContext.getString(resId, *args)

    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager.adapter

    // --- État exposé ------------------------------------------------------

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /** Niveau courant de chaque actionneur du toy connecté (feedback UI). */
    private val _actuatorLevels = MutableStateFlow<List<Int>>(emptyList())
    val actuatorLevels: StateFlow<List<Int>> = _actuatorLevels.asStateFlow()

    /** Toys Lovense visibles pendant le scan (pour la sélection à la connexion). */
    private val _discovered = MutableStateFlow<List<DiscoveredToy>>(emptyList())
    val discovered: StateFlow<List<DiscoveredToy>> = _discovered.asStateFlow()

    // --- État interne GATT ------------------------------------------------

    private var gatt: BluetoothGatt? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var rx: BluetoothGattCharacteristic? = null
    /** Modèle détecté (→ actionneurs). Deviné au nom BLE puis affiné via `DeviceType;`. */
    private var toy: ToyType = ToyRegistry.generic
    /** Toy choisi par l'utilisateur (pour reconnexion auto sur le bon appareil). */
    private var chosenDevice: BluetoothDevice? = null

    /** True si l'utilisateur a explicitement coupé → on n'auto-reconnecte pas. */
    @Volatile private var userDisconnect = false
    private var reconnectAttempts = 0

    // Deferreds pour séquencer les callbacks GATT.
    private var mtuDeferred: CompletableDeferred<Unit>? = null
    private var servicesDeferred: CompletableDeferred<Boolean>? = null
    private var descriptorDeferred: CompletableDeferred<Boolean>? = null

    // File d'écriture : intensité désirée par actionneur (index = position dans
    // toy.actuators). Taille max fixe ; seuls les premiers `toy.actuators.size`
    // indices sont actifs.
    private val desired = AtomicIntegerArray(MAX_ACTUATORS)
    private val lastSent = IntArray(MAX_ACTUATORS) { -1 }
    private val writeSignal = Channel<Unit>(Channel.CONFLATED)
    private val writeMutex = Mutex()
    private var writeAck: CompletableDeferred<Boolean>? = null
    private var writerJob: Job? = null
    private var scanTimeoutJob: Job? = null

    // ====================================================================
    // API publique
    // ====================================================================

    /** Démarre la découverte : scanne et publie les toys visibles dans [discovered]. */
    fun startDiscovery() {
        if (!hasPermissions()) {
            _connectionState.value = ConnectionState.Error(str(R.string.err_perms))
            return
        }
        val a = adapter
        if (a == null || !a.isEnabled) {
            _connectionState.value = ConnectionState.Error(str(R.string.err_bt_off))
            return
        }
        if (_connectionState.value is ConnectionState.Connected ||
            _connectionState.value is ConnectionState.Connecting
        ) return

        userDisconnect = false
        _discovered.value = emptyList()
        startScan()
    }

    /** Connexion au toy choisi dans la liste des toys découverts. */
    fun connectTo(toy: DiscoveredToy) {
        val a = adapter ?: return
        userDisconnect = false
        reconnectAttempts = 0
        // Devine le modèle au nom BLE ; affiné via DeviceType après connexion.
        this.toy = ToyRegistry.byBleName(toy.bleName)
        stopScan()
        val device = runCatching { a.getRemoteDevice(toy.address) }.getOrNull() ?: run {
            _connectionState.value = ConnectionState.Error(str(R.string.err_device_gone))
            return
        }
        chosenDevice = device
        connectGatt(device)
    }

    /** Coupe la connexion à la demande de l'utilisateur (pas de reconnexion auto). */
    fun disconnect() {
        userDisconnect = true
        scope.launch {
            stopScan()
            teardownGatt()
            chosenDevice = null
            _connectionState.value = ConnectionState.Disconnected
            _actuatorLevels.value = emptyList()
            _discovered.value = emptyList()
        }
    }

    /** Règle l'actionneur [index] (position dans `toy.actuators`) à un niveau brut. */
    fun setActuator(index: Int, level: Int) {
        val act = toy.actuators.getOrNull(index) ?: return
        desired.set(index, level.coerceIn(0, act.max))
        writeSignal.trySend(Unit)
    }

    /** Règle l'actionneur [index] à partir d'une fraction UI [0f..1f]. */
    fun setActuatorFraction(index: Int, fraction: Float) {
        val act = toy.actuators.getOrNull(index) ?: return
        setActuator(index, LovenseProtocol.fractionToLevel(fraction, act.max))
    }

    /** Règle TOUS les actionneurs à la même fraction (presets / mode Link). */
    fun setAllFraction(fraction: Float) {
        toy.actuators.indices.forEach { setActuatorFraction(it, fraction) }
    }

    /** Inverse le sens d'un actionneur rotatif (Nora) — `RotateChange;`. */
    fun reverse(index: Int) {
        if (toy.actuators.getOrNull(index)?.reversible != true) return
        scope.launch { writeCommand(LovenseProtocol.rotateChange()) }
    }

    /** Arrête tous les actionneurs. */
    fun stopAll() {
        toy.actuators.indices.forEach { desired.set(it, 0) }
        writeSignal.trySend(Unit)
    }

    /** Actionneurs du toy connecté (pour l'UI adaptative). */
    val actuators: List<Actuator> get() = toy.actuators

    /** À appeler quand le manager n'est plus utilisé (ex. onDestroy). */
    fun release() {
        disconnect()
    }

    // ====================================================================
    // Scan
    // ====================================================================

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: result.scanRecord?.deviceName ?: return
            if (!name.startsWith(LovenseProtocol.BLE_NAME_PREFIX)) return
            val address = result.device.address ?: return
            // On accumule les toys visibles (dédupliqués, triés par signal) sans
            // connecter : l'utilisateur choisit dans la liste.
            val toy = DiscoveredToy(address = address, bleName = name, rssi = result.rssi)
            _discovered.update { list ->
                (list.filterNot { it.address == address } + toy).sortedByDescending { it.rssi }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            _connectionState.value = ConnectionState.Error(str(R.string.err_scan_failed, errorCode))
        }
    }

    private fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: run {
            _connectionState.value = ConnectionState.Error(str(R.string.err_scanner))
            return
        }
        _connectionState.value = ConnectionState.Scanning
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        // Pas de filtre matériel (le nom n'est pas filtrable par préfixe) :
        // on filtre dans onScanResult sur le préfixe `LVS-`.
        // On scanne en continu : la liste [discovered] se remplit au fil de l'eau.
        // Pas de timeout d'erreur — l'utilisateur voit juste « rien pour l'instant ».
        scanner.startScan(null, settings, scanCallback)
    }

    private fun stopScan() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    // ====================================================================
    // Connexion / cycle de vie GATT
    // ====================================================================

    private fun connectGatt(device: BluetoothDevice) {
        _connectionState.value = ConnectionState.Connecting
        gatt = device.connectGatt(appContext, /* autoConnect = */ false, gattCallback)
    }

    private val gattCallback = object : android.bluetooth.BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    reconnectAttempts = 0
                    scope.launch { runHandshake(g) }
                }
                BluetoothGatt.STATE_DISCONNECTED -> {
                    scope.launch { onDisconnected() }
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            mtuDeferred?.complete(Unit)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            servicesDeferred?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            descriptorDeferred?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            writeAck?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        // Notifications — deux signatures selon le niveau d'API.
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            handleNotification(ch.value ?: return)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            handleNotification(value)
        }
    }

    /** Séquence post-connexion : MTU → services → notif → handshake. */
    private suspend fun runHandshake(g: BluetoothGatt) {
        // 1. MTU (commandes minuscules, mais on demande un peu de marge).
        mtuDeferred = CompletableDeferred()
        g.requestMtu(64)
        withTimeoutOrNull(3_000) { mtuDeferred?.await() }

        // 2. Découverte des services.
        servicesDeferred = CompletableDeferred()
        g.discoverServices()
        val ok = withTimeoutOrNull(8_000) { servicesDeferred?.await() } ?: false
        if (!ok) { fail("Découverte des services échouée"); return }

        // 3. Détection TX/RX.
        val endpoints = LovenseProtocol.findEndpoints(g.services)
        if (endpoints == null) { fail("Caractéristiques Lovense introuvables"); return }
        tx = endpoints.tx
        rx = endpoints.rx

        // 4. Activation des notifications (CCCD).
        if (!enableNotifications(g, endpoints.rx)) { fail("Activation notifications échouée"); return }

        // 5. Connecté : on peut piloter. Démarre la boucle d'écriture.
        _connectionState.value = ConnectionState.Connected(toy)
        resetWriteState()
        startWriterLoop()

        // 6. Handshake + batterie (réponses via notifications).
        writeCommand(LovenseProtocol.deviceType())
        writeCommand(LovenseProtocol.battery())
    }

    private suspend fun enableNotifications(g: BluetoothGatt, ch: BluetoothGattCharacteristic): Boolean {
        g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(LovenseProtocol.CCCD_UUID) ?: return false
        descriptorDeferred = CompletableDeferred()
        val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, enable)
        } else {
            @Suppress("DEPRECATION")
            run { cccd.value = enable; g.writeDescriptor(cccd) }
        }
        return withTimeoutOrNull(3_000) { descriptorDeferred?.await() } ?: false
    }

    private fun handleNotification(bytes: ByteArray) {
        when (val reply = LovenseProtocol.parseReply(bytes)) {
            is LovenseProtocol.Reply.Battery -> updateBattery(reply.percent)
            is LovenseProtocol.Reply.DeviceType ->
                // Code type fiable → on affine le modèle (et donc les actionneurs).
                ToyRegistry.byDeviceCode(reply.model)?.let { applyToy(it) }
            else -> { /* OK; / inconnu : ignoré */ }
        }
    }

    /** Applique un modèle détecté : ré-init des actionneurs + maj de l'état. */
    private fun applyToy(t: ToyType) {
        toy = t
        resetWriteState()
        _connectionState.update { st -> if (st is ConnectionState.Connected) st.copy(toy = t) else st }
    }

    private fun updateBattery(percent: Int) {
        _connectionState.update { st ->
            if (st is ConnectionState.Connected) st.copy(battery = percent) else st
        }
    }

    private suspend fun onDisconnected() {
        teardownGatt()
        if (userDisconnect) {
            _connectionState.value = ConnectionState.Disconnected
            return
        }
        // Reconnexion auto au MÊME toy avec back-off : 1, 2, 4, 8 s (plafonné).
        val dev = chosenDevice
        if (dev == null) { _connectionState.value = ConnectionState.Disconnected; return }
        reconnectAttempts++
        val backoff = (1_000L shl (reconnectAttempts - 1).coerceAtMost(3))
        _connectionState.value = ConnectionState.Connecting
        delay(backoff)
        if (!userDisconnect) connectGatt(dev)
    }

    private suspend fun teardownGatt() {
        writerJob?.cancelAndJoin()
        writerJob = null
        runCatching { gatt?.close() }
        gatt = null
        tx = null
        rx = null
    }

    private fun fail(reason: String) {
        _connectionState.value = ConnectionState.Error(reason)
        runCatching { gatt?.disconnect() }
    }

    // ====================================================================
    // File d'écriture sérialisée + coalescée
    // ====================================================================

    private fun resetWriteState() {
        for (i in 0 until desired.length()) desired.set(i, 0)
        lastSent.fill(-1)
        _actuatorLevels.value = List(toy.actuators.size) { 0 }
    }

    private fun startWriterLoop() {
        writerJob?.cancel()
        writerJob = scope.launch {
            for (signal in writeSignal) {
                // Envoie les diffs jusqu'à convergence (la cible a pu changer
                // pendant l'écriture précédente → on reboucle).
                var progressed = true
                while (progressed) {
                    progressed = false
                    for (i in toy.actuators.indices) {
                        val target = desired.get(i)
                        if (target != lastSent[i]) {
                            if (writeCommand(LovenseProtocol.actuatorCommand(toy.actuators[i].kind, target))) {
                                lastSent[i] = target
                                progressed = true
                                publishLevels()
                            } else {
                                // Échec d'écriture : on stoppe ce tour, prochain signal réessaiera.
                                progressed = false
                                break
                            }
                        }
                    }
                }
            }
        }
    }

    private fun publishLevels() {
        _actuatorLevels.value = toy.actuators.indices.map { lastSent[it].coerceAtLeast(0) }
    }

    /** Écrit une commande sur TX, sérialisée, et attend l'ACK GATT. */
    private suspend fun writeCommand(bytes: ByteArray): Boolean = writeMutex.withLock {
        val g = gatt ?: return false
        val ch = tx ?: return false
        val ack = CompletableDeferred<Boolean>()
        writeAck = ack
        val started = issueWrite(g, ch, bytes)
        if (!started) { writeAck = null; return false }
        // ACK rapide attendu ; timeout court pour ne pas bloquer le pilotage.
        val result = withTimeoutOrNull(1_000) { ack.await() } ?: false
        writeAck = null
        result
    }

    private fun issueWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, bytes: ByteArray): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(
                ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                ch.value = bytes
                g.writeCharacteristic(ch)
            }
        }
    }

    // ====================================================================
    // Permissions
    // ====================================================================

    private fun hasPermissions(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return needed.all {
            ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
