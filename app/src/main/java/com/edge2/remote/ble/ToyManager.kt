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
import com.edge2.remote.ble.db.Advertisement
import com.edge2.remote.ble.db.DbProtocol
import com.edge2.remote.ble.db.DeviceDatabase
import com.edge2.remote.ble.proto.Protocols
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
 * **Multi-toy / multi-brand** BLE management: scan, then one [ToyConnection]
 * per chosen toy. Every connected toy can be driven on its own (by address)
 * or together (patterns, sharing, STOP).
 *
 * The calling context must hold BLUETOOTH_SCAN + BLUETOOTH_CONNECT.
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

    /** Managed toys (connected or connecting), in the order they were added. */
    private val _toys = MutableStateFlow<List<ToyStatus>>(emptyList())
    val toys: StateFlow<List<ToyStatus>> = _toys.asStateFlow()

    private val connections = ConcurrentHashMap<String, ToyConnection>()
    private val order = mutableListOf<String>()
    private var aggregator: Job? = null

    /** Device database (assets/devices.json), loaded once. */
    val database: DeviceDatabase by lazy {
        DeviceDatabase.parse(appContext.assets.open("devices.json").bufferedReader().use { it.readText() })
    }

    /** What we saw at scan time, per address (protocol + advertisement). */
    private class Seen(val protocol: DbProtocol, val name: String, val mfr: Map<Int, ByteArray>)
    private val seen = ConcurrentHashMap<String, Seen>()

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
        // No hardware filter (names can't be prefix-filtered, brands vary):
        // devices are identified in onScanResult through the device database.
        scanner.startScan(null, settings, scanCallback)
        _scanState.value = ScanState.Scanning
    }

    fun stopDiscovery() {
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        if (_scanState.value is ScanState.Scanning) _scanState.value = ScanState.Idle
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord
            val name = result.device.name ?: record?.deviceName
            val mfr = HashMap<Int, ByteArray>()
            record?.manufacturerSpecificData?.let { sd ->
                for (i in 0 until sd.size()) mfr[sd.keyAt(i)] = sd.valueAt(i)
            }
            val uuids = record?.serviceUuids?.map { it.uuid }.orEmpty()
            val protocol = database.identify(Advertisement(name, mfr, uuids)) ?: return
            val address = result.device.address ?: return
            if (connections.containsKey(address)) return // already managed
            seen[address] = Seen(protocol, name.orEmpty(), mfr)
            val toy = DiscoveredToy(
                address = address, bleName = name.orEmpty(), rssi = result.rssi,
                protocolId = protocol.id,
                brand = DeviceDatabase.brandOf(protocol),
                displayName = database.guessName(protocol, name),
                supported = Protocols.get(protocol.id) != null,
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
    // Connections
    // ====================================================================

    /** Adds the chosen toy (several toys can be connected at the same time). */
    fun connectTo(found: DiscoveredToy) {
        val a = adapter ?: return
        if (connections.containsKey(found.address)) return
        if (connections.size >= MAX_TOYS) return
        if (!found.supported) return
        val s = seen[found.address] ?: return
        val device = runCatching { a.getRemoteDevice(found.address) }.getOrNull() ?: run {
            _scanState.value = ScanState.Error(str(R.string.err_device_gone)); return
        }
        stopDiscovery()
        val conn = ToyConnection(appContext, device, database, s.protocol, s.name, s.mfr)
        synchronized(order) {
            connections[found.address] = conn
            order += found.address
        }
        _discovered.update { l -> l.filterNot { it.address == found.address } }
        rebuildAggregator()
        conn.connect()
    }

    /** Removes a toy (motors stopped, then disconnected, no auto-reconnect). */
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

    /** Recombines every connection's status flow into a single list. */
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
    // Driving
    // ====================================================================

    private fun conn(address: String) = connections[address]
    private fun all() = synchronized(order) { order.mapNotNull { connections[it] } }

    fun setFraction(address: String, index: Int, fraction: Float) { conn(address)?.setFraction(index, fraction) }
    fun reverse(address: String, index: Int) { conn(address)?.reverse(index) }
    fun stop(address: String) { conn(address)?.stop() }

    /** Motion actuator n° [motionIndex] of one toy (patterns, remote M1/M2). */
    fun setMotion(address: String, motionIndex: Int, fraction: Float) {
        val c = conn(address) ?: return
        c.status.value.toy.motion.getOrNull(motionIndex)?.let { c.setFraction(it, fraction) }
    }

    /** Motion actuator n° [motionIndex] of EVERY toy (ignored by toys that have fewer). */
    fun setMotionAll(motionIndex: Int, fraction: Float) = all().forEach { setMotion(it.address, motionIndex, fraction) }

    /** Every motion actuator of one toy to the same fraction. */
    fun setAllMotion(address: String, fraction: Float) {
        val c = conn(address) ?: return
        c.status.value.toy.motion.forEach { c.setFraction(it, fraction) }
    }

    /** Every motion actuator of every toy to the same fraction. */
    fun setAllFraction(fraction: Float) = all().forEach { setAllMotion(it.address, fraction) }

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
        /** Android handles ~7 simultaneous BLE links; stay on the safe side. */
        const val MAX_TOYS = 5
    }
}
