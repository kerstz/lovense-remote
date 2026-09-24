package com.edge2.remote.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import com.edge2.remote.R
import com.edge2.remote.ble.db.DeviceDatabase
import com.edge2.remote.ble.db.DbProtocol
import com.edge2.remote.ble.proto.DeviceIo
import com.edge2.remote.ble.proto.Ep
import com.edge2.remote.ble.proto.Hint
import com.edge2.remote.ble.proto.ProtocolHandler
import com.edge2.remote.ble.proto.Protocols
import com.edge2.remote.ble.proto.Write
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * BLE link to ONE toy, for any supported brand:
 *  1. GATT connect, MTU, service discovery;
 *  2. endpoint mapping from the device database ("tx", "rx"…);
 *  3. model identification + protocol init ([com.edge2.remote.ble.proto]);
 *  4. a serialized + coalesced write queue (a slider moving 50×/s only sends
 *     the latest value per actuator), keepalive, stroke generator, battery;
 *  5. automatic reconnection with back-off, and a safe stop on close.
 *
 * Several instances coexist (multi-toy), each with its own GATT callback.
 */
@SuppressLint("MissingPermission")
class ToyConnection(
    context: Context,
    private val device: BluetoothDevice,
    private val db: DeviceDatabase,
    private val protocol: DbProtocol,
    private val advertisedName: String,
    private val manufacturerData: Map<Int, ByteArray>,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val address: String = device.address

    private val brand = DeviceDatabase.brandOf(protocol)
    private var toy: ToyType = ToyType.from(
        db.definition(protocol.id, advertisedName) ?: error("unknown protocol"), brand,
    )

    private val _status = MutableStateFlow(ToyStatus(address, toy, LinkState.Connecting))
    val status: StateFlow<ToyStatus> = _status.asStateFlow()

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var handler: ProtocolHandler? = null
    /** Endpoint name → characteristic, for the current GATT session. */
    @Volatile private var endpoints: Map<String, BluetoothGattCharacteristic> = emptyMap()

    /** true = shutdown requested (user or fatal error) → no reconnection. */
    @Volatile private var closed = false
    private var reconnectAttempts = 0
    private var sessionJob: Job? = null

    // GATT callbacks → coroutines.
    private var mtuDeferred: CompletableDeferred<Unit>? = null
    private var servicesDeferred: CompletableDeferred<Boolean>? = null
    @Volatile private var opDeferred: CompletableDeferred<ByteArray?>? = null
    private val gattMutex = Mutex()
    private val notifications = Channel<Pair<String, ByteArray>>(64, BufferOverflow.DROP_OLDEST)

    // Write queue: desired level per actuator (signed for rotation).
    private val desired = HashMap<Int, Int>()
    private val lastSent = HashMap<Int, Int>()
    /** Rotation direction per actuator (+1 / −1). */
    private val direction = HashMap<Int, Int>()
    /** Stroke speed (0..1) per STROKE actuator. */
    private val strokeSpeed = HashMap<Int, Float>()
    private val writeSignal = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var lastWrite: Write? = null
    @Volatile private var lastWriteAt = 0L

    private fun str(resId: Int): String = appContext.getString(resId)

    // ====================================================================
    // API
    // ====================================================================

    fun connect() {
        closed = false
        reconnectAttempts = 0
        connectGatt()
    }

    /** Sets actuator [index] (position in `toy.actuators`) from a 0..1 fraction. */
    fun setFraction(index: Int, fraction: Float) {
        val act = toy.actuators.getOrNull(index) ?: return
        synchronized(desired) {
            if (act.kind == ActuatorKind.STROKE) {
                strokeSpeed[index] = fraction.coerceIn(0f, 1f)
                return
            }
            val level = act.levelFor(fraction) ?: return
            desired[index] = if (act.kind == ActuatorKind.ROTATE && act.min < 0) level * (direction[index] ?: 1) else level
        }
        writeSignal.trySend(Unit)
    }

    /** Flips the rotation direction of actuator [index] (signed-range rotators only). */
    fun reverse(index: Int) {
        val act = toy.actuators.getOrNull(index) ?: return
        if (!act.reversible) return
        synchronized(desired) {
            direction[index] = -(direction[index] ?: 1)
            desired[index]?.let { desired[index] = -it }
        }
        writeSignal.trySend(Unit)
    }

    /** Everything off: motors to 0, heating/LED/pump off, strokes stopped. */
    fun stop() {
        synchronized(desired) {
            toy.actuators.forEachIndexed { i, a ->
                if (a.kind == ActuatorKind.STROKE) strokeSpeed[i] = 0f
                else if (a.min <= 0) desired[i] = 0
            }
        }
        writeSignal.trySend(Unit)
    }

    /**
     * Closes the link: first stops everything (best effort, 800 ms max) so a
     * toy is never left running on its own, then closes GATT.
     */
    fun close(onClosed: () -> Unit = {}) {
        closed = true
        scope.launch {
            stop()
            withTimeoutOrNull(800) { flushStop() }
            teardownGatt()
            onClosed()
            scope.cancel()
        }
    }

    private suspend fun flushStop() {
        val h = handler ?: return
        toy.actuators.forEach { a ->
            if (a.kind != ActuatorKind.STROKE && a.min <= 0) {
                for (w in dispatch(h, a, 0)) io.write(w)
            }
        }
    }

    // ====================================================================
    // Device I/O for protocols
    // ====================================================================

    private val io = object : DeviceIo {
        override val name: String get() = advertisedName
        override val manufacturerData: Map<Int, ByteArray> get() = this@ToyConnection.manufacturerData

        override fun hint(hint: Hint?) { _status.update { it.copy(hint = hint) } }

        override fun hasEndpoint(endpoint: String) = endpoints.containsKey(endpoint)

        override suspend fun write(w: Write): Boolean = gattMutex.withLock {
            val g = gatt ?: return false
            val ch = endpoints[w.endpoint] ?: return false
            val ack = CompletableDeferred<ByteArray?>()
            opDeferred = ack
            val type = if (w.withResponse || ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE == 0)
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(ch, w.data, type) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run { ch.writeType = type; ch.value = w.data; g.writeCharacteristic(ch) }
            }
            if (!started) { opDeferred = null; return false }
            val ok = withTimeoutOrNull(1_000) { ack.await() } != null
            opDeferred = null
            if (ok) { lastWrite = w; lastWriteAt = System.currentTimeMillis() }
            ok
        }

        override suspend fun read(endpoint: String): ByteArray? = gattMutex.withLock {
            val g = gatt ?: return null
            val ch = endpoints[endpoint] ?: return null
            val result = CompletableDeferred<ByteArray?>()
            opDeferred = result
            if (!g.readCharacteristic(ch)) { opDeferred = null; return null }
            val v = withTimeoutOrNull(1_000) { result.await() }
            opDeferred = null
            v?.takeIf { it.isNotEmpty() }
        }

        override suspend fun subscribe(endpoint: String): Boolean = setNotify(endpoint, true)
        override suspend fun unsubscribe(endpoint: String) { setNotify(endpoint, false) }

        override suspend fun awaitNotification(endpoint: String?, timeoutMs: Long): ByteArray? =
            withTimeoutOrNull(timeoutMs.coerceAtLeast(1)) {
                while (true) {
                    val (ep, data) = notifications.receive()
                    if (endpoint == null || ep == endpoint) return@withTimeoutOrNull data
                }
                @Suppress("UNREACHABLE_CODE") null
            }
    }

    private suspend fun setNotify(endpoint: String, enable: Boolean): Boolean = gattMutex.withLock {
        val g = gatt ?: return false
        val ch = endpoints[endpoint] ?: return false
        g.setCharacteristicNotification(ch, enable)
        val cccd = ch.getDescriptor(LovenseProtocol.CCCD_UUID) ?: return true
        val value = when {
            !enable -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        val done = CompletableDeferred<ByteArray?>()
        opDeferred = done
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run { cccd.value = value; g.writeDescriptor(cccd) }
        }
        if (!started) { opDeferred = null; return false }
        val ok = withTimeoutOrNull(2_000) { done.await() } != null
        opDeferred = null
        ok
    }

    // ====================================================================
    // Connection / GATT lifecycle
    // ====================================================================

    private fun connectGatt() {
        _status.update { it.copy(link = if (reconnectAttempts > 0) LinkState.Reconnecting else LinkState.Connecting) }
        gatt = device.connectGatt(appContext, /* autoConnect = */ false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> scope.launch { runSession(g) }
                BluetoothGatt.STATE_DISCONNECTED -> scope.launch { onDisconnected() }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { mtuDeferred?.complete(Unit) }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            servicesDeferred?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            opDeferred?.complete(if (status == BluetoothGatt.GATT_SUCCESS) ByteArray(0) else null)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            opDeferred?.complete(if (status == BluetoothGatt.GATT_SUCCESS) ByteArray(0) else null)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            opDeferred?.complete(if (status == BluetoothGatt.GATT_SUCCESS) (ch.value ?: ByteArray(0)) else null)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            opDeferred?.complete(if (status == BluetoothGatt.GATT_SUCCESS) value else null)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            onNotify(ch, ch.value ?: return)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            onNotify(ch, value)
        }
    }

    private fun onNotify(ch: BluetoothGattCharacteristic, value: ByteArray) {
        // Bounded: a hostile device must not be able to flood us.
        if (value.size > 256) return
        val ep = endpoints.entries.firstOrNull { it.value.uuid == ch.uuid }?.key ?: return
        notifications.trySend(ep to value)
        handler?.onNotification(ep, value)?.let { b -> _status.update { it.copy(battery = b.coerceIn(0, 100)) } }
    }

    /** Post-connection session: MTU → services → endpoints → identify → init → run. */
    private suspend fun runSession(g: BluetoothGatt) {
        sessionJob?.cancel()
        sessionJob = scope.launch {
            mtuDeferred = CompletableDeferred()
            g.requestMtu(128)
            withTimeoutOrNull(3_000) { mtuDeferred?.await() }

            servicesDeferred = CompletableDeferred()
            g.discoverServices()
            if (withTimeoutOrNull(8_000) { servicesDeferred?.await() } != true) { retry(); return@launch }

            endpoints = resolveEndpoints(g, protocol)
            if (!endpoints.containsKey(Ep.TX) && endpoints.keys.none { it.startsWith("tx") || it == Ep.GENERIC0 || it == Ep.COMMAND }) {
                fatal(str(R.string.err_chars)); return@launch
            }
            while (notifications.tryReceive().isSuccess) Unit

            val result = runCatching {
                withTimeout(90_000) {
                    val spec = Protocols.get(protocol.id) ?: error("unsupported protocol ${protocol.id}")
                    val ident = spec.identify(io)
                    val finalSpec = ident.protocolId?.let { Protocols.get(it) } ?: spec
                    val defn = db.definition(finalSpec.id, ident.identifier)
                        ?: db.definition(protocol.id, ident.identifier)
                        ?: error("no definition")
                    val h = finalSpec.create(defn, io, ident.identifier)
                    toy = ToyType.from(defn, brand)
                    h
                }
            }
            val h = result.getOrElse {
                android.util.Log.w("ToyConnection", "init failed: ${it.message}")
                fatal(it.message ?: str(R.string.err_chars)); return@launch
            }
            handler = h
            io.hint(null)
            reconnectAttempts = 0
            synchronized(desired) { desired.clear(); lastSent.clear(); strokeSpeed.clear() }
            _status.update { it.copy(toy = toy, link = LinkState.Connected, levels = List(toy.actuators.size) { 0 }, hint = null) }
            h.attach(io, this)
            launch { writerLoop(h) }
            h.keepaliveMs?.let { ms -> launch { keepaliveLoop(ms) } }
            launch { strokeLoop(h) }
            launch { batteryLoop(h) }
        }
    }

    /** Endpoint name → characteristic, from the protocol's service map (Lovense: heuristic fallback). */
    private fun resolveEndpoints(g: BluetoothGatt, p: DbProtocol): Map<String, BluetoothGattCharacteristic> {
        val out = HashMap<String, BluetoothGattCharacteristic>()
        for ((svc, eps) in p.btle.services) {
            val service = runCatching { g.getService(UUID.fromString(svc)) }.getOrNull() ?: continue
            for ((name, uuid) in eps) {
                val ch = runCatching { service.getCharacteristic(UUID.fromString(uuid)) }.getOrNull() ?: continue
                out.putIfAbsent(name, ch)
            }
        }
        if (p.id == "lovense" && !out.containsKey(Ep.TX)) {
            LovenseProtocol.findEndpoints(g.services)?.let { (tx, rx) -> out[Ep.TX] = tx; out[Ep.RX] = rx }
        }
        return out
    }

    private suspend fun onDisconnected() {
        sessionJob?.cancelAndJoin()
        teardownGatt()
        if (closed) return
        // Auto-reconnect to the SAME toy with back-off: 1, 2, 4, 8 s (capped).
        reconnectAttempts++
        _status.update { it.copy(link = LinkState.Reconnecting) }
        delay(1_000L shl (reconnectAttempts - 1).coerceAtMost(3))
        if (!closed) connectGatt()
    }

    /** Transient failure: disconnect, [onDisconnected] will retry. */
    private fun retry() { runCatching { gatt?.disconnect() } }

    /** Permanent failure: no reconnection. */
    private suspend fun fatal(reason: String) {
        closed = true
        teardownGatt()
        _status.update { it.copy(link = LinkState.Error(reason), hint = null) }
    }

    private suspend fun teardownGatt() {
        handler = null
        runCatching { gatt?.close() }
        gatt = null
        endpoints = emptyMap()
    }

    // ====================================================================
    // Loops: writes, keepalive, strokes, battery
    // ====================================================================

    private fun dispatch(h: ProtocolHandler, a: Actuator, v: Int): List<Write> = when (a.kind) {
        ActuatorKind.VIBRATE -> h.vibrate(a.featureIndex, v)
        ActuatorKind.ROTATE -> h.rotate(a.featureIndex, v)
        ActuatorKind.OSCILLATE -> h.oscillate(a.featureIndex, v)
        ActuatorKind.CONSTRICT -> h.constrict(a.featureIndex, v)
        ActuatorKind.TEMPERATURE -> h.temperature(a.featureIndex, v)
        ActuatorKind.LED -> h.led(a.featureIndex, v)
        ActuatorKind.SPRAY -> h.spray(a.featureIndex, v)
        ActuatorKind.POSITION -> h.position(a.featureIndex, v)
        ActuatorKind.STROKE -> emptyList()
    }

    private suspend fun writerLoop(h: ProtocolHandler) {
        for (signal in writeSignal) {
            while (true) {
                val changes = synchronized(desired) {
                    desired.filter { (i, v) -> lastSent[i] != v }.toList()
                }
                if (changes.isEmpty()) break
                var ok = true
                for ((i, v) in changes) {
                    val a = toy.actuators.getOrNull(i) ?: continue
                    for (w in dispatch(h, a, v)) if (!io.write(w)) { ok = false; break }
                    if (!ok) break
                    synchronized(desired) { lastSent[i] = v }
                    // A pump press is momentary: forget it so the next press is sent again.
                    if (a.kind == ActuatorKind.SPRAY && v != 0) synchronized(desired) { desired[i] = 0; lastSent[i] = 0 }
                }
                publishLevels()
                if (!ok) break // the next signal will retry
            }
        }
    }

    private fun publishLevels() {
        val levels = synchronized(desired) { toy.actuators.indices.map { lastSent[it] ?: 0 } }
        _status.update { it.copy(levels = levels) }
    }

    /** Hardware that stops without traffic: repeat the last packet. */
    private suspend fun keepaliveLoop(ms: Long) {
        while (scope.isActive) {
            delay(ms)
            val w = lastWrite ?: continue
            if (System.currentTimeMillis() - lastWriteAt >= ms) io.write(w)
        }
    }

    /**
     * STROKE actuators take positions + durations: turn a 0..1 "speed" into
     * back-and-forth strokes over the full range (slow = 1.5 s, fast = 0.25 s).
     */
    private suspend fun strokeLoop(h: ProtocolHandler) {
        val strokers = toy.actuators.indices.filter { toy.actuators[it].kind == ActuatorKind.STROKE }
        if (strokers.isEmpty()) return
        var up = true
        while (scope.isActive) {
            var waited = false
            for (i in strokers) {
                val s = synchronized(desired) { strokeSpeed[i] ?: 0f }
                if (s <= 0f) continue
                val a = toy.actuators[i]
                val half = (1500 - (s * 1250)).toInt().coerceAtLeast(150) / 2
                for (w in h.hwPosition(a.featureIndex, if (up) a.max else a.min.coerceAtLeast(0), half)) io.write(w)
                delay(half.toLong())
                waited = true
            }
            up = !up
            if (!waited) delay(100)
        }
    }

    private suspend fun batteryLoop(h: ProtocolHandler) {
        while (scope.isActive) {
            runCatching { h.battery(io) }.getOrNull()?.let { b -> _status.update { it.copy(battery = b.coerceIn(0, 100)) } }
            delay(60_000)
        }
    }
}
