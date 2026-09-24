package com.edge2.remote.ble.proto

import com.edge2.remote.ble.db.DeviceDefinition
import kotlin.math.abs
import kotlin.math.pow

// Ports of Buttplug's Vorze SA and Vibratissimo protocols (BSD-3-Clause, see THIRD_PARTY_NOTICES.md).

private object VorzeDevice {
    const val CYCLONE = 1
    const val UFO = 2
    const val PISTON = 3
    const val UFO_TW = 5
    const val BACH = 6
    const val ROCKET = 7
    const val OMORFI = 9
}

/** Direction bit (clockwise = 1) + speed, as used by every Vorze rotator. */
private fun vorzeRotation(speed: Int) = ((if (speed >= 0) 1 else 0) shl 7) or abs(speed).u8()

class VorzeSingleRotator(def: DeviceDefinition, private val type: Int) : ProtocolHandler(def) {
    override fun rotate(index: Int, speed: Int) = listOf(w(Ep.TX, type, 0x01, vorzeRotation(speed).u8(), withResponse = true))
}

class VorzeDualRotator(def: DeviceDefinition) : ProtocolHandler(def) {
    private val speeds = IntArray(2)
    override fun rotate(index: Int, speed: Int): List<Write> {
        speeds.setSafe(index, speed.toByte().toInt())
        return listOf(w(Ep.TX, VorzeDevice.UFO_TW, vorzeRotation(speeds[0]).u8(), vorzeRotation(speeds[1]).u8(), withResponse = true))
    }
}

class VorzeSingleVibrator(def: DeviceDefinition, private val type: Int) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int) = listOf(w(Ep.TX, type, 0x03, speed.u8(), withResponse = true))
}

class VorzeDualVibrator(def: DeviceDefinition) : ProtocolHandler(def) {
    private val speeds = IntArray(2)
    override fun vibrate(index: Int, speed: Int): List<Write> {
        speeds.setSafe(index, speed.u8())
        return listOf(w(Ep.TX, VorzeDevice.OMORFI, speeds[0], speeds[1], withResponse = true))
    }
}

/** Vorze Piston: [type, position, speed] with a duration → speed curve. */
class VorzePiston(def: DeviceDefinition) : ProtocolHandler(def) {
    private var previous = 0
    override fun hwPosition(index: Int, position: Int, durationMs: Int): List<Write> {
        val pos = position.u8()
        val speed = pistonSpeed(abs(previous - pos).toDouble(), durationMs.toDouble())
        previous = pos
        return listOf(w(Ep.TX, VorzeDevice.PISTON, pos, speed, withResponse = true))
    }

    companion object {
        fun pistonSpeed(distance: Double, duration: Double): Int {
            if (distance <= 0) return 100
            val d = distance.coerceAtMost(200.0)
            val scaled = 200.0 * duration / d
            return (scaled / 6658.0).pow(-1.21).coerceIn(0.0, 100.0).satU8()
        }
    }
}

object VorzeSpec : ProtocolSpec {
    override val id = "vorze-sa"
    override suspend fun create(def: DeviceDefinition, io: DeviceIo, identifier: String?): ProtocolHandler {
        val n = io.name.lowercase()
        return when (def.variant) {
            "vorze-sa-single-rotator" -> when {
                "cycsa" in n -> VorzeSingleRotator(def, VorzeDevice.CYCLONE)
                "ufo" in n -> VorzeSingleRotator(def, VorzeDevice.UFO)
                else -> null
            }
            "vorze-sa-dual-rotator" -> VorzeDualRotator(def)
            "vorze-sa-single-vibrator" -> when {
                "bach" in n -> VorzeSingleVibrator(def, VorzeDevice.BACH)
                "rocket" in n -> VorzeSingleVibrator(def, VorzeDevice.ROCKET)
                else -> null
            }
            "vorze-sa-dual-vibrator" -> if ("omor" in n) VorzeDualVibrator(def) else null
            "vorze-sa-piston" -> VorzePiston(def)
            else -> null
        } ?: error("No protocol implementation for Vorze device ${io.name}")
    }
}

class Vibratissimo(def: DeviceDefinition) : ProtocolHandler(def) {
    private val speeds = IntArray(def.count(com.edge2.remote.ble.db.OutputType.VIBRATE).coerceAtLeast(1))
    override fun vibrate(index: Int, speed: Int): List<Write> {
        speeds.setSafe(index, speed.u8())
        val data = speeds.toMutableList()
        if (data.size == 1) data += 0x00
        return listOf(w(Ep.TX_MODE, 0x03, 0xff), w(Ep.TX_VIBRATE, data))
    }

    companion object {
        val spec = object : ProtocolSpec {
            override val id = "vibratissimo"
            override suspend fun identify(io: DeviceIo) =
                Identified(io.read(Ep.RX_BLE_MODEL)?.let { runCatching { String(it, Charsets.UTF_8) }.getOrNull() } ?: io.name)
            override suspend fun create(def: DeviceDefinition, io: DeviceIo, identifier: String?) = Vibratissimo(def)
        }
    }
}
