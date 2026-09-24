package com.edge2.remote.ble.proto

import com.edge2.remote.ble.db.DeviceDefinition
import kotlin.math.abs
import kotlin.math.pow

// Ports of Buttplug's Kiiroo / Fleshlight Launch protocols (BSD-3-Clause, see THIRD_PARTY_NOTICES.md).

/** Fleshlight Launch speed model: speed (0..1) needed to travel [distance] (0..1) in [durationMs]. */
fun launchSpeed(distance: Double, durationMs: Int): Double {
    if (distance < 0) return 0.0
    val d = distance.coerceAtMost(1.0)
    val scalar = ((durationMs * 90.0) / (d * 100.0)).pow(-1.05)
    return 250.0 * scalar
}

/** Rust `f64 as u8`: saturating, NaN → 0. */
internal fun Double.satU8(): Int = if (isNaN()) 0 else toInt().coerceIn(0, 255)

private fun launchStep(previous: Int, position: Int, durationMs: Int): Int {
    val distance = abs(previous.toDouble() - position) / 99.0
    return (launchSpeed(distance, durationMs) * 99.0).satU8()
}

/** Kiiroo v2 (Launch-era strokers): [position, speed]. */
class KiirooV2(def: DeviceDefinition) : ProtocolHandler(def) {
    private var previous = 0
    override fun hwPosition(index: Int, position: Int, durationMs: Int): List<Write> {
        val speed = launchStep(previous, position, durationMs)
        previous = position.u8()
        return listOf(w(Ep.TX, position.u8(), speed))
    }

    companion object {
        val spec = specInit("kiiroo-v2") { def, io ->
            io.write(w(Ep.FIRMWARE, 0x00, withResponse = true))
            KiirooV2(def)
        }
    }
}

class KiirooV2Vibrator(def: DeviceDefinition) : ProtocolHandler(def) {
    private val speeds = IntArray(3)
    override fun vibrate(index: Int, speed: Int): List<Write> {
        speeds.setSafe(index, speed.u8())
        return listOf(w(Ep.TX, speeds[0], speeds[1], speeds[2]))
    }
}

/** Kiiroo v2.1 (Onyx+, Pearl 2, Titan…) and v3. */
open class KiirooV21(def: DeviceDefinition) : ProtocolHandler(def) {
    private var previous = 0
    override fun vibrate(index: Int, speed: Int) = listOf(w(Ep.TX, 0x01, speed.u8()))
    override fun hwPosition(index: Int, position: Int, durationMs: Int): List<Write> {
        val speed = launchStep(previous, position, durationMs)
        previous = position.u8()
        return listOf(w(Ep.TX, 0x03, 0x00, speed, position.u8()))
    }

    override suspend fun battery(io: DeviceIo): Int? {
        if (!io.hasEndpoint(Ep.WHITELIST)) return super.battery(io)
        val data = io.read(Ep.WHITELIST) ?: return null
        return if (data.size == 20) data[5].toInt() and 0xff else null
    }
}

/** Onyx+ style: needs an init sequence and a 2 s keepalive. */
class KiirooV21Initialized(def: DeviceDefinition) : ProtocolHandler(def) {
    private var previous = 0
    override val keepaliveMs: Long = 2_000
    override fun vibrate(index: Int, speed: Int) = listOf(w(Ep.TX, 0x01, speed.u8()))
    override fun hwPosition(index: Int, position: Int, durationMs: Int): List<Write> {
        val speed = launchStep(previous, position, durationMs)
        previous = position.u8()
        return listOf(w(Ep.TX, 0x03, 0x00, speed, position.u8()))
    }

    companion object {
        val spec = specInit("kiiroo-v21-initialized") { def, io ->
            io.write(w(Ep.TX, 0x03, 0x00, 0x64, 0x19, withResponse = true))
            io.write(w(Ep.TX, 0x03, 0x00, 0x64, 0x00, withResponse = true))
            KiirooV21Initialized(def)
        }
    }
}
