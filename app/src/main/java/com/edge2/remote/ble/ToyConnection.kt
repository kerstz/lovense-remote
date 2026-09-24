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
import java.util.concurrent.atomic.AtomicIntegerArray
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

/** Nombre max d'actionneurs gérés par jouet (le plus complexe en a 2-3). */
internal const val MAX_ACTUATORS = 4

/**
 * Lien BLE vers UN jouet : cycle de vie GATT (MTU, services, notifications,
 * handshake du [driver]), file d'écriture sérialisée + coalescée, reconnexion
 * automatique avec back-off. Plusieurs instances coexistent (multi-jouets),
 * chacune avec son propre callback GATT.
 *
 * Toutes les écritures passent par une file coalescée : si le slider bouge
 * 50×/s, seule la dernière valeur par actionneur est réellement envoyée.
 */
@SuppressLint("MissingPermission")
class ToyConnection(
    context: Context,
    private val device: BluetoothDevice,
    private val driver: ToyDriver,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val address: String = device.address

    private val _status = MutableStateFlow(ToyStatus(address, driver.toy, LinkState.Connecting))
    val status: StateFlow<ToyStatus> = _status.asStateFlow()

    private val toy: ToyType get() = driver.toy

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var tx: BluetoothGattCharacteristic? = null

    /** true = arrêt demandé (utilisateur ou erreur fatale) → pas de reconnexion. */
    @Volatile private var closed = false
    private var reconnectAttempts = 0

    private var mtuDeferred: CompletableDeferred<Unit>? = null
    private var servicesDeferred: CompletableDeferred<Boolean>? = null
    private var descriptorDeferred: CompletableDeferred<Boolean>? = null

    private val desired = AtomicIntegerArray(MAX_ACTUATORS)
    private val lastSent = IntArray(MAX_ACTUATORS) { -1 }
    private val writeSignal = Channel<Unit>(Channel.CONFLATED)
    private val writeMutex = Mutex()
    @Volatile private var writeAck: CompletableDeferred<Boolean>? = null
    private var writerJob: Job? = null

    private fun str(resId: Int): String = appContext.getString(resId)

    // ====================================================================
    // API
    // ====================================================================

    fun connect() {
        closed = false
        reconnectAttempts = 0
        connectGatt()
    }

    /** Règle l'actionneur [index] à un niveau brut (borné à sa plage). */
    fun setLevel(index: Int, level: Int) {
        val act = toy.actuators.getOrNull(index) ?: return
        desired.set(index, level.coerceIn(0, act.max))
        writeSignal.trySend(Unit)
    }

    /** Règle l'actionneur [index] à partir d'une fraction [0f..1f]. */
    fun setFraction(index: Int, fraction: Float) {
        val act = toy.actuators.getOrNull(index) ?: return
        setLevel(index, LovenseProtocol.fractionToLevel(fraction, act.max))
    }

    fun stop() {
        toy.actuators.indices.forEach { desired.set(it, 0) }
        writeSignal.trySend(Unit)
    }

    fun reverse(index: Int) {
        scope.launch {
            val cmd = driver.reverse(index, lastSent.copyOf(toy.actuators.size)) ?: return@launch
            writeCommand(cmd)
        }
    }

    /**
     * Coupe le lien : envoie d'abord un arrêt des moteurs (au mieux, 600 ms max)
     * pour ne jamais laisser un jouet vibrer seul, puis ferme le GATT.
     */
    fun close(onClosed: () -> Unit = {}) {
        closed = true
        scope.launch {
            stop()
            withTimeoutOrNull(600) { flushStop() }
            teardownGatt()
            onClosed()
            scope.cancel()
        }
    }

    private suspend fun flushStop() {
        val n = toy.actuators.size
        val zero = IntArray(n)
        for (cmd in driver.encode(zero, IntArray(n) { -1 })) writeCommand(cmd)
    }

    // ====================================================================
    // Connexion / cycle de vie GATT
    // ====================================================================

    private fun connectGatt() {
        _status.update { it.copy(link = if (reconnectAttempts > 0) LinkState.Reconnecting else LinkState.Connecting) }
        gatt = device.connectGatt(appContext, /* autoConnect = */ false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> scope.launch { runHandshake(g) }
                BluetoothGatt.STATE_DISCONNECTED -> scope.launch { onDisconnected() }
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

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            handleNotification(ch.value ?: return)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            handleNotification(value)
        }
    }

    /** Séquence post-connexion : MTU → services → endpoints → notif → handshake. */
    private suspend fun runHandshake(g: BluetoothGatt) {
        mtuDeferred = CompletableDeferred()
        g.requestMtu(64)
        withTimeoutOrNull(3_000) { mtuDeferred?.await() }

        servicesDeferred = CompletableDeferred()
        g.discoverServices()
        val ok = withTimeoutOrNull(8_000) { servicesDeferred?.await() } ?: false
        if (!ok) { retry(); return }

        // Service attendu absent = mauvais appareil/marque → erreur définitive.
        val endpoints = driver.endpoints(g.services)
        if (endpoints == null) { fatal(str(R.string.err_chars)); return }
        tx = endpoints.tx

        endpoints.rx?.let { rx ->
            if (!enableNotifications(g, rx)) { retry(); return }
        }

        reconnectAttempts = 0
        resetWriteState()
        _status.update { it.copy(toy = toy, link = LinkState.Connected, levels = List(toy.actuators.size) { 0 }) }
        startWriterLoop()
        for (cmd in driver.handshake()) writeCommand(cmd)
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
        // Notification bornée : un appareil hostile ne doit pas pouvoir nous noyer.
        if (bytes.size > 64) return
        when (val ev = driver.onNotification(bytes)) {
            is DriverEvent.Battery -> _status.update { it.copy(battery = ev.percent) }
            is DriverEvent.ToyChanged -> {
                resetWriteState()
                _status.update { it.copy(toy = ev.toy, levels = List(ev.toy.actuators.size) { 0 }) }
            }
            null -> Unit
        }
    }

    private suspend fun onDisconnected() {
        teardownGatt()
        if (closed) return
        // Reconnexion auto au MÊME jouet avec back-off : 1, 2, 4, 8 s (plafonné).
        reconnectAttempts++
        _status.update { it.copy(link = LinkState.Reconnecting) }
        delay(1_000L shl (reconnectAttempts - 1).coerceAtMost(3))
        if (!closed) connectGatt()
    }

    /** Échec transitoire : on coupe, [onDisconnected] relancera. */
    private fun retry() {
        runCatching { gatt?.disconnect() }
    }

    /** Échec définitif : pas de reconnexion. */
    private suspend fun fatal(reason: String) {
        closed = true
        teardownGatt()
        _status.update { it.copy(link = LinkState.Error(reason)) }
    }

    private suspend fun teardownGatt() {
        writerJob?.cancelAndJoin()
        writerJob = null
        runCatching { gatt?.close() }
        gatt = null
        tx = null
    }

    // ====================================================================
    // File d'écriture sérialisée + coalescée
    // ====================================================================

    private fun resetWriteState() {
        for (i in 0 until desired.length()) desired.set(i, 0)
        lastSent.fill(-1)
    }

    private fun startWriterLoop() {
        writerJob?.cancel()
        writerJob = scope.launch {
            for (signal in writeSignal) {
                // Reboucle jusqu'à convergence (la cible a pu changer pendant l'écriture).
                while (true) {
                    val n = toy.actuators.size
                    val target = IntArray(n) { desired.get(it) }
                    val last = lastSent.copyOf(n)
                    if (target.contentEquals(last)) break
                    val cmds = driver.encode(target, last)
                    var ok = true
                    for (cmd in cmds) if (!writeCommand(cmd)) { ok = false; break }
                    if (!ok) break // prochain signal réessaiera
                    target.copyInto(lastSent)
                    _status.update { it.copy(levels = target.toList()) }
                }
            }
        }
    }

    /** Écrit une commande sur TX, sérialisée, et attend l'ACK GATT. */
    private suspend fun writeCommand(bytes: ByteArray): Boolean = writeMutex.withLock {
        val g = gatt ?: return false
        val ch = tx ?: return false
        val ack = CompletableDeferred<Boolean>()
        writeAck = ack
        if (!issueWrite(g, ch, bytes)) { writeAck = null; return false }
        val result = withTimeoutOrNull(1_000) { ack.await() } ?: false
        writeAck = null
        result
    }

    private fun issueWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, bytes: ByteArray): Boolean {
        val type = ch.preferredWriteType()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, bytes, type) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                ch.writeType = type
                ch.value = bytes
                g.writeCharacteristic(ch)
            }
        }
    }
}
