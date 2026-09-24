package com.edge2.remote.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.edge2.remote.R
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Gestion BLE **multi-jouets / multi-marques** : scan, puis une [ToyConnection]
 * par jouet choisi. Tous les jouets connectés sont pilotables indépendamment
 * (par adresse) ou ensemble (patterns, partage, STOP).
 *
 * Le contexte appelant doit détenir BLUETOOTH_SCAN + BLUETOOTH_CONNECT.
 */
@SuppressLint("MissingPermission")
class ToyManager(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun str(resId: Int, vararg args: Any): String = appContext.getString(resId, *args)

    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager.adapter

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    private val _discovered = MutableStateFlow<List<DiscoveredToy>>(emptyList())
    val discovered: StateFlow<List<DiscoveredToy>> = _discovered.asStateFlow()

    /** Jouets gérés (connectés ou en cours), dans l'ordre d'ajout. */
    private val _toys = MutableStateFlow<List<ToyStatus>>(emptyList())
    val toys: StateFlow<List<ToyStatus>> = _toys.asStateFlow()

    private val connections = ConcurrentHashMap<String, ToyConnection>()
    private val order = mutableListOf<String>()
    private var aggregator: Job? = null

    /** Identifications faites au scan (adresse → driver à instancier). */
    private val matches = ConcurrentHashMap<String, ToyProtocols.Match>()

    // ====================================================================
    // Scan
    // ====================================================================

    fun startDiscovery() {
        if (!hasPermissions()) { _scanState.value = ScanState.Error(str(R.string.err_perms)); return }
        val a = adapter
        if (a == null || !a.isEnabled) { _scanState.value = ScanState.Error(str(R.string.err_bt_off)); return }
        val scanner = a.bluetoothLeScanner ?: run {
            _scanState.value = ScanState.Error(str(R.string.err_scanner)); return
        }
        runCatching { scanner.stopScan(scanCallback) }
        _discovered.value = emptyList()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        // Pas de filtre matériel (noms non filtrables par préfixe, marques variées) :
        // on identifie dans onScanResult via ToyProtocols.
        scanner.startScan(null, settings, scanCallback)
        _scanState.value = ScanState.Scanning
    }

    fun stopDiscovery() {
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        if (_scanState.value is ScanState.Scanning) _scanState.value = ScanState.Idle
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: result.scanRecord?.deviceName
            val uuids = result.scanRecord?.serviceUuids?.map { it.uuid }.orEmpty()
            val match = ToyProtocols.identify(name, uuids) ?: return
            val address = result.device.address ?: return
            if (connections.containsKey(address)) return // déjà géré
            matches[address] = match
            val toy = DiscoveredToy(
                address = address, bleName = name.orEmpty(), rssi = result.rssi,
                brand = match.guess.brand, displayName = match.guess.displayName,
            )
            _discovered.update { list ->
                (list.filterNot { it.address == address } + toy).sortedByDescending { it.rssi }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            _scanState.value = ScanState.Error(str(R.string.err_scan_failed, errorCode))
        }
    }

    // ====================================================================
    // Connexions
    // ====================================================================

    /** Ajoute le jouet choisi (plusieurs jouets peuvent être connectés en même temps). */
    fun connectTo(found: DiscoveredToy) {
        val a = adapter ?: return
        if (connections.containsKey(found.address)) return
        if (connections.size >= MAX_TOYS) return
        val match = matches[found.address] ?: ToyProtocols.identify(found.bleName) ?: return
        val device = runCatching { a.getRemoteDevice(found.address) }.getOrNull() ?: run {
            _scanState.value = ScanState.Error(str(R.string.err_device_gone)); return
        }
        stopDiscovery()
        val conn = ToyConnection(appContext, device, match.newDriver())
        synchronized(order) {
            connections[found.address] = conn
            order += found.address
        }
        _discovered.update { l -> l.filterNot { it.address == found.address } }
        rebuildAggregator()
        conn.connect()
    }

    /** Retire un jouet (arrêt moteurs puis déconnexion, sans reconnexion auto). */
    fun disconnect(address: String) {
        val conn = synchronized(order) {
            order.remove(address)
            connections.remove(address)
        } ?: return
        rebuildAggregator()
        conn.close()
    }

    fun disconnectAll() {
        stopDiscovery()
        synchronized(order) { order.toList() }.forEach(::disconnect)
        _discovered.value = emptyList()
    }

    /** Recombine les flows de statut de toutes les connexions en une liste. */
    private fun rebuildAggregator() {
        aggregator?.cancel()
        val conns = synchronized(order) { order.mapNotNull { connections[it] } }
        if (conns.isEmpty()) { _toys.value = emptyList(); return }
        val flows = conns.map { it.status }
        aggregator = scope.launch {
            combine(flows) { it.toList() }.collect { _toys.value = it }
        }
    }

    // ====================================================================
    // Pilotage
    // ====================================================================

    private fun conn(address: String) = connections[address]
    private fun all() = synchronized(order) { order.mapNotNull { connections[it] } }

    fun setLevel(address: String, index: Int, level: Int) { conn(address)?.setLevel(index, level) }
    fun setFraction(address: String, index: Int, fraction: Float) { conn(address)?.setFraction(index, fraction) }
    fun reverse(address: String, index: Int) { conn(address)?.reverse(index) }
    fun stop(address: String) { conn(address)?.stop() }

    /** Actionneur [index] de TOUS les jouets (ignoré par ceux qui n'en ont pas autant). */
    fun setFractionAll(index: Int, fraction: Float) = all().forEach { it.setFraction(index, fraction) }

    /** Tous les actionneurs de tous les jouets à la même fraction. */
    fun setAllFraction(fraction: Float) = all().forEach { c ->
        c.status.value.toy.actuators.indices.forEach { c.setFraction(it, fraction) }
    }

    fun stopAll() = all().forEach { it.stop() }

    private fun hasPermissions(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return needed.all { ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED }
    }

    companion object {
        /** Android gère ~7 liens BLE simultanés ; on reste prudent. */
        const val MAX_TOYS = 5
    }
}
