package com.edge2.remote.ble

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Lovense BLE protocol — confirmed on the Edge 2 (see PROTOCOL.md). Used by
 * [LovenseDriver].
 *
 * Summary:
 *  - Commands = ASCII strings terminated by `;`, written with WriteNoResponse
 *    to the TX characteristic (UUID ending in `…0002`).
 *  - Replies = ASCII notifications on the RX characteristic (`…0003`).
 *  - Two motors: `Vibrate1:n;` / `Vibrate2:n;`, n ∈ [0..20].
 *  - Handshake: `DeviceType;` → reply `P:02:MAC;` (P = Edge). No auth is
 *    required to drive the toy.
 *
 * The exact service UUID is not hard-coded (it varies with the firmware
 * revision: `…0023…` vs `…0024…`). Instead TX/RX are detected by their GATT
 * properties at discovery time (see [findEndpoints]).
 */
object LovenseProtocol {

    /** Maximum intensity accepted by the toy. */
    const val INTENSITY_MAX = 20

    /** BLE name prefix advertised by all recent Lovense toys. */
    const val BLE_NAME_PREFIX = "LVS-"

    /** "LVS-Edge2-3A9F" → "Edge 2". A space is inserted between letters and digits. */
    fun prettyModelName(bleName: String): String {
        val token = bleName.removePrefix(BLE_NAME_PREFIX).substringBefore('-')
        if (token.isBlank()) return "Lovense"
        return token.replace(Regex("(?<=[A-Za-z])(?=[0-9])"), " ")
    }

    /** CCCD standard (Client Characteristic Configuration Descriptor). */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // --- Command building (ASCII bytes) ------------------------------------

    /** `DeviceType;` — identifies the model (handshake). */
    fun deviceType(): ByteArray = "DeviceType;".toByteArray(Charsets.US_ASCII)

    /** `Battery;` — queries the battery level. */
    fun battery(): ByteArray = "Battery;".toByteArray(Charsets.US_ASCII)

    /** `PowerOff;` — turns the toy off. */
    fun powerOff(): ByteArray = "PowerOff;".toByteArray(Charsets.US_ASCII)

    /**
     * Command for actuator [index] of [toy], clamped to its own range.
     * Two vibrators → `Vibrate1:`/`Vibrate2:`; a single one → `Vibrate:`.
     */
    fun actuatorCommand(toy: ToyType, index: Int, level: Int): ByteArray? {
        val act = toy.actuators.getOrNull(index) ?: return null
        val n = level.coerceIn(0, act.max)
        val vibrators = toy.actuators.count { it.kind == ActuatorKind.VIBRATE }
        val s = when (act.kind) {
            ActuatorKind.VIBRATE ->
                if (vibrators > 1) "Vibrate${toy.actuators.take(index + 1).count { it.kind == ActuatorKind.VIBRATE }}:$n;"
                else "Vibrate:$n;"
            ActuatorKind.ROTATE -> "Rotate:$n;"
            ActuatorKind.SUCTION -> "Air:Level:$n;"
        }
        return s.toByteArray(Charsets.US_ASCII)
    }

    /** `RotateChange;` — reverses the rotation direction (ROTATE actuators). */
    fun rotateChange(): ByteArray = "RotateChange;".toByteArray(Charsets.US_ASCII)

    // --- Conversions / parsing ---------------------------------------------

    /** Clamps an intensity to [0..20]. */
    fun clamp(intensity: Int): Int = intensity.coerceIn(0, INTENSITY_MAX)

    /** Converts a UI fraction [0f..1f] into an actuator level [0..max]. */
    fun fractionToLevel(fraction: Float, max: Int = INTENSITY_MAX): Int =
        (fraction.coerceIn(0f, 1f) * max).roundToInt()

    /**
     * Parses a toy notification into a typed [Reply].
     * Examples: `P:02:0082059AD3BD;`, `85;`, `OK;`.
     */
    fun parseReply(raw: ByteArray): Reply {
        val s = raw.toString(Charsets.US_ASCII).trim().trimEnd(';')
        return when {
            s.isEmpty() -> Reply.Unknown("")
            s == "OK" -> Reply.Ok
            // DeviceType: "P:02:MAC" (model:firmware:MAC)
            s.contains(':') && s.substringBefore(':').length == 1 && s.substringBefore(':')[0].isLetter() -> {
                val parts = s.split(':')
                Reply.DeviceType(
                    model = parts.getOrElse(0) { "?" },
                    firmware = parts.getOrElse(1) { "?" },
                    mac = parts.getOrElse(2) { "?" },
                )
            }
            // Battery: a plain number, e.g. "85" (clamped: a hostile or buggy
            // device must not be able to inject an absurd value).
            s.toIntOrNull() != null -> Reply.Battery(s.toInt().coerceIn(0, 100))
            else -> Reply.Unknown(s)
        }
    }

    sealed interface Reply {
        data object Ok : Reply
        data class DeviceType(val model: String, val firmware: String, val mac: String) : Reply
        data class Battery(val percent: Int) : Reply
        data class Unknown(val raw: String) : Reply
    }

    // --- Endpoint detection at service discovery ---------------------------

    /**
     * Walks the GATT services and returns the toy's (TX write, RX notify).
     *
     * Robust heuristic (independent of the UUID revision): find the service
     * holding both a NOTIFY characteristic (RX) and a writable one (TX). That
     * is the "serial port" RX/TX layout of every Lovense toy.
     */
    fun findEndpoints(services: List<BluetoothGattService>): Endpoints? {
        for (service in services) {
            val notify = service.characteristics.firstOrNull {
                it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
            } ?: continue
            val write = service.characteristics.firstOrNull {
                val p = it.properties
                (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 ||
                    p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) &&
                    it.uuid != notify.uuid
            } ?: continue
            return Endpoints(tx = write, rx = notify)
        }
        return null
    }

    data class Endpoints(
        val tx: BluetoothGattCharacteristic,
        val rx: BluetoothGattCharacteristic,
    )
}
