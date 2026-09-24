package com.edge2.remote.ble.proto

import com.edge2.remote.ble.db.DeviceDefinition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Protocol layer, modelled on Buttplug's `ProtocolHandler` so that protocols can
 * be ported one-to-one (see docs/research/other-brands.md and
 * THIRD_PARTY_NOTICES.md).
 *
 * A handler never touches GATT directly: it turns output commands into [Write]s
 * addressed to named endpoints ("tx", "rx", "txmode", "whitelist"…), which the
 * connection maps to characteristics using the device database.
 */

/** Endpoint names used by the device database. */
object Ep {
    const val TX = "tx"
    const val RX = "rx"
    const val TX_MODE = "txmode"
    const val TX_VIBRATE = "txvibrate"
    const val COMMAND = "command"
    const val FIRMWARE = "firmware"
    const val WHITELIST = "whitelist"
    const val GENERIC0 = "generic0"
    const val RX_BLE_BATTERY = "rxblebattery"
    const val RX_BLE_MODEL = "rxblemodel"
}

/** One GATT write. */
class Write(val endpoint: String, val data: ByteArray, val withResponse: Boolean = false) {
    override fun toString() = "$endpoint:" + data.joinToString(" ") { "%02x".format(it) }
}

/** Builds a write from int byte values (convenience for ports). */
fun w(endpoint: String, vararg bytes: Int, withResponse: Boolean = false) =
    Write(endpoint, ByteArray(bytes.size) { bytes[it].toByte() }, withResponse)

fun w(endpoint: String, bytes: List<Int>, withResponse: Boolean = false) =
    Write(endpoint, ByteArray(bytes.size) { bytes[it].toByte() }, withResponse)

fun ascii(endpoint: String, text: String, withResponse: Boolean = false) =
    Write(endpoint, text.toByteArray(Charsets.US_ASCII), withResponse)

/** Something the user must do for the connection to complete (shown in the UI). */
enum class Hint { PRESS_POWER_BUTTON, PAIR_WITH_PIN_6496 }

/** Device I/O available to protocols during identification, init and background tasks. */
interface DeviceIo {
    /** Shows [hint] to the user (null clears it). */
    fun hint(hint: Hint?)

    /** Advertised BLE name. */
    val name: String
    /** Advertised manufacturer data (company id → bytes). */
    val manufacturerData: Map<Int, ByteArray>
    fun hasEndpoint(endpoint: String): Boolean
    suspend fun write(w: Write): Boolean
    suspend fun read(endpoint: String): ByteArray?
    suspend fun subscribe(endpoint: String): Boolean
    suspend fun unsubscribe(endpoint: String)
    /** Next notification (optionally from [endpoint]) within [timeoutMs], or null. */
    suspend fun awaitNotification(endpoint: String?, timeoutMs: Long): ByteArray?
}

/**
 * Turns output commands into writes. Values are already in the feature's own
 * range (from the device database); rotation is signed (sign = direction).
 * Handlers may keep state (e.g. the last value of every motor) because many
 * protocols send all motors in one packet.
 */
abstract class ProtocolHandler(val def: DeviceDefinition) {

    /** Re-send the last packet this often (hardware that stops without traffic). */
    open val keepaliveMs: Long? = null

    /** Device I/O and connection scope, available once [attach]ed. */
    protected var io: DeviceIo? = null
        private set
    protected var scope: CoroutineScope? = null
        private set

    /** Called by the connection once the handler is ready. */
    fun attach(io: DeviceIo, scope: CoroutineScope) {
        this.io = io
        this.scope = scope
        start(io, scope)
    }

    /** Background work once connected (e.g. stroker position loops). */
    open fun start(io: DeviceIo, scope: CoroutineScope) {}

    /** Sends [write] after [delayMs] (e.g. auto-stopping a lube pump). */
    protected fun later(delayMs: Long, write: Write) {
        val s = scope ?: return
        val d = io ?: return
        s.launch { kotlinx.coroutines.delay(delayMs); d.write(write) }
    }

    open fun vibrate(index: Int, speed: Int): List<Write> = unsupported("vibrate")
    open fun rotate(index: Int, speed: Int): List<Write> = unsupported("rotate")
    open fun oscillate(index: Int, speed: Int): List<Write> = unsupported("oscillate")
    open fun constrict(index: Int, level: Int): List<Write> = unsupported("constrict")
    open fun temperature(index: Int, level: Int): List<Write> = unsupported("temperature")
    open fun led(index: Int, level: Int): List<Write> = unsupported("led")
    open fun spray(index: Int, level: Int): List<Write> = unsupported("spray")
    open fun position(index: Int, position: Int): List<Write> = unsupported("position")
    open fun hwPosition(index: Int, position: Int, durationMs: Int): List<Write> = unsupported("hw_position")

    /** Handles a notification; returns a battery level (0..100) if it carried one. */
    open fun onNotification(endpoint: String, data: ByteArray): Int? = null

    /** Reads the battery level; default: standard battery characteristic if mapped. */
    open suspend fun battery(io: DeviceIo): Int? =
        if (io.hasEndpoint(Ep.RX_BLE_BATTERY)) io.read(Ep.RX_BLE_BATTERY)?.firstOrNull()?.toInt()?.and(0xff)
        else null

    protected fun unsupported(what: String): List<Write> = emptyList()
}

/**
 * A protocol: how to identify the exact model once connected (default: the BLE
 * name, like Buttplug's generic identifier) and how to build its handler.
 */
interface ProtocolSpec {
    val id: String

    /**
     * Identifier used to pick the model in the database (null = protocol
     * defaults). A protocol may also redirect to a sibling protocol.
     */
    suspend fun identify(io: DeviceIo): Identified = Identified(io.name)

    /** Builds the handler (for the [identifier] returned by [identify]), running any init sequence first. */
    suspend fun create(def: DeviceDefinition, io: DeviceIo, identifier: String?): ProtocolHandler
}

/** Result of [ProtocolSpec.identify]. */
data class Identified(val identifier: String?, val protocolId: String? = null)

/** Spec with name identification and no init sequence. */
fun spec(id: String, factory: (DeviceDefinition) -> ProtocolHandler): ProtocolSpec = object : ProtocolSpec {
    override val id = id
    override suspend fun create(def: DeviceDefinition, io: DeviceIo, identifier: String?) = factory(def)
}

/** Spec with name identification and an init sequence. */
fun specInit(
    id: String,
    init: suspend (DeviceDefinition, DeviceIo) -> ProtocolHandler,
): ProtocolSpec = object : ProtocolSpec {
    override val id = id
    override suspend fun create(def: DeviceDefinition, io: DeviceIo, identifier: String?) = init(def, io)
}

/** Clamp helpers used by ports (Rust `as u8` truncation semantics where relevant). */
internal fun Int.u8(): Int = this and 0xff
