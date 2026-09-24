package com.edge2.remote.ble

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import java.util.UUID

/**
 * Brand-specific BLE encoding. One instance per connection (some drivers keep a
 * little state: rotation direction, model refined after the handshake…).
 *
 * A driver NEVER touches GATT: it only turns actuator levels into bytes and
 * interprets notifications. The connection ([ToyConnection]) handles transport,
 * serialization and ACKs.
 */
interface ToyDriver {
    /** Current model (may be refined by [onNotification]). */
    val toy: ToyType

    /** Finds the TX (write) / RX (notify, optional) characteristics. */
    fun endpoints(services: List<BluetoothGattService>): GattEndpoints?

    /** Commands to send right after connecting (handshake, battery…). */
    fun handshake(): List<ByteArray> = emptyList()

    /**
     * Commands that move from levels [last] (−1 = unknown) to levels [target].
     * Levels are already clamped to each actuator's `0..max`.
     */
    fun encode(target: IntArray, last: IntArray): List<ByteArray>

    /** "Reverse direction" command for actuator [index], or null. */
    fun reverse(index: Int, current: IntArray): ByteArray? = null

    /** Interprets a notification; returns an event or null. */
    fun onNotification(bytes: ByteArray): DriverEvent? = null
}

sealed interface DriverEvent {
    data class Battery(val percent: Int) : DriverEvent
    /** The model was refined (e.g. `DeviceType;` reply) → actuators must be reset. */
    data class ToyChanged(val toy: ToyType) : DriverEvent
}

data class GattEndpoints(
    val tx: BluetoothGattCharacteristic,
    val rx: BluetoothGattCharacteristic?,
)

/** Looks up characteristics by fixed UUIDs (service + tx [+ rx]). */
internal fun fixedEndpoints(
    services: List<BluetoothGattService>,
    service: UUID,
    tx: UUID,
    rx: UUID? = null,
): GattEndpoints? {
    val s = services.firstOrNull { it.uuid == service } ?: return null
    val txCh = s.getCharacteristic(tx) ?: return null
    val rxCh = rx?.let { s.getCharacteristic(it) }
    return GattEndpoints(txCh, rxCh)
}

/** Bytes → single-command list (convenience). */
private fun one(bytes: ByteArray) = listOf(bytes)

// ============================================================================
// Lovense — ASCII `Vibrate:n;` etc. (confirmed on the Edge 2)
// ============================================================================

class LovenseDriver(bleName: String) : ToyDriver {
    override var toy: ToyType = ToyRegistry.byBleName(bleName)
        private set

    override fun endpoints(services: List<BluetoothGattService>): GattEndpoints? =
        LovenseProtocol.findEndpoints(services)?.let { GattEndpoints(it.tx, it.rx) }

    override fun handshake() = listOf(LovenseProtocol.deviceType(), LovenseProtocol.battery())

    override fun encode(target: IntArray, last: IntArray): List<ByteArray> =
        toy.actuators.indices.filter { target[it] != last[it] }
            .mapNotNull { LovenseProtocol.actuatorCommand(toy, it, target[it]) }

    override fun reverse(index: Int, current: IntArray): ByteArray? =
        if (toy.actuators.getOrNull(index)?.reversible == true) LovenseProtocol.rotateChange() else null

    override fun onNotification(bytes: ByteArray): DriverEvent? =
        when (val reply = LovenseProtocol.parseReply(bytes)) {
            is LovenseProtocol.Reply.Battery -> DriverEvent.Battery(reply.percent)
            is LovenseProtocol.Reply.DeviceType -> ToyRegistry.byDeviceCode(reply.model)
                ?.takeIf { it != toy }
                ?.let { toy = it; DriverEvent.ToyChanged(it) }
            else -> null
        }
}

// ============================================================================
// We-Vibe (Standard Innovation) — 8-byte binary frame, 2 motors 0..15.
// Community reference: Buttplug ("wevibe" protocol).
// ============================================================================

class WeVibeDriver(override val toy: ToyType) : ToyDriver {
    override fun endpoints(services: List<BluetoothGattService>) =
        fixedEndpoints(services, SERVICE, TX)

    override fun encode(target: IntArray, last: IntArray): List<ByteArray> {
        if (target.contentEquals(last)) return emptyList()
        return one(frame(target[0], if (toy.actuators.size > 1) target[1] else target[0]))
    }

    companion object {
        val SERVICE: UUID = UUID.fromString("f000bb03-0451-4000-b000-000000000000")
        val TX: UUID = UUID.fromString("f000c000-0451-4000-b000-000000000000")
        const val MAX = 15

        /** Both motors at 0 → stop frame; otherwise both levels packed in one byte. */
        fun frame(m1: Int, m2: Int): ByteArray {
            val a = m1.coerceIn(0, MAX)
            val b = m2.coerceIn(0, MAX)
            if (a == 0 && b == 0) return byteArrayOf(0x0f, 0, 0, 0, 0, 0, 0, 0)
            return byteArrayOf(0x0f, 0x03, 0x00, ((a shl 4) or b).toByte(), 0x00, 0x03, 0x00, 0x00)
        }
    }
}

// ============================================================================
// Vorze (A10 Cyclone SA, UFO SA, Bach) — 3 bytes [type, action, value].
// Community reference: Buttplug ("vorze-sa" protocol).
// ============================================================================

class VorzeDriver(override val toy: ToyType, private val deviceType: Int) : ToyDriver {
    /** Current rotation direction (bit 7 of the speed byte). */
    private var clockwise = true

    override fun endpoints(services: List<BluetoothGattService>) =
        fixedEndpoints(services, SERVICE, TX)

    override fun encode(target: IntArray, last: IntArray): List<ByteArray> {
        if (target[0] == last[0]) return emptyList()
        return one(command(target[0]))
    }

    override fun reverse(index: Int, current: IntArray): ByteArray? {
        if (toy.actuators.getOrNull(index)?.reversible != true) return null
        clockwise = !clockwise
        return command(current[0].coerceAtLeast(0))
    }

    private fun command(level: Int): ByteArray {
        val n = level.coerceIn(0, MAX)
        return if (toy.actuators[0].kind == ActuatorKind.ROTATE) {
            byteArrayOf(deviceType.toByte(), ACTION_ROTATE, ((if (clockwise) 0x80 else 0) or n).toByte())
        } else {
            byteArrayOf(deviceType.toByte(), ACTION_VIBRATE, n.toByte())
        }
    }

    companion object {
        val SERVICE: UUID = UUID.fromString("40ee1111-63ec-4b7f-8ce7-712efd55b90e")
        val TX: UUID = UUID.fromString("40ee2222-63ec-4b7f-8ce7-712efd55b90e")
        const val MAX = 99
        const val TYPE_CYCLONE = 0x01
        const val TYPE_UFO = 0x02
        const val TYPE_BACH = 0x06
        private const val ACTION_ROTATE: Byte = 0x01
        private const val ACTION_VIBRATE: Byte = 0x03
    }
}

// ============================================================================
// Magic Motion (v1: Smart Mini Vibe, Flamingo, Magic Cell…) — 12-byte frame.
// Community reference: Buttplug ("magic-motion-1" protocol).
// ============================================================================

class MagicMotionDriver(override val toy: ToyType) : ToyDriver {
    override fun endpoints(services: List<BluetoothGattService>) =
        fixedEndpoints(services, SERVICE, TX)

    override fun encode(target: IntArray, last: IntArray): List<ByteArray> {
        if (target[0] == last[0]) return emptyList()
        return one(frame(target[0]))
    }

    companion object {
        val SERVICE: UUID = UUID.fromString("78667579-7b48-43db-b8c5-7928a6b0a335")
        val TX: UUID = UUID.fromString("78667579-a914-49a4-8333-aa3c0cd8fedc")
        const val MAX = 100

        fun frame(level: Int): ByteArray = byteArrayOf(
            0x0b, 0xff.toByte(), 0x04, 0x0a, 0x32, 0x32, 0x00, 0x04, 0x08,
            level.coerceIn(0, MAX).toByte(), 0x64, 0x00,
        )
    }
}

/** Helper: picks the write type supported by the characteristic. */
internal fun BluetoothGattCharacteristic.preferredWriteType(): Int =
    if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
    else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
