package com.edge2.remote.ble.proto

import com.edge2.remote.ble.db.DeviceDefinition

// Ports of Buttplug protocols M (BSD-3-Clause, see THIRD_PARTY_NOTICES.md).

class ManNuo(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int): List<Write> {
        val data = mutableListOf(0xAA, 0x55, 0x06, 0x01, 0x01, 0x01, speed.u8(), 0xFA)
        data += data.fold(0) { c, b -> c xor b }
        return listOf(w(Ep.TX, data, withResponse = true))
    }
}

class Maxpro(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int): List<Write> {
        val data = mutableListOf(0x55, 0x04, 0x07, 0xff, 0xff, 0x3f, speed.u8(), 0x5f, speed.u8(), 0x00)
        data[9] = data.sum() and 0xff
        return listOf(w(Ep.TX, data))
    }
}

class Meese(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int) = listOf(w(Ep.TX, 0x01, 0x80, 0x01 + index, speed.u8(), withResponse = true))
}

class MizzZee(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int) =
        listOf(w(Ep.TX, 0x69, 0x96, 0x03, 0x01, if (speed == 0) 0 else 1, speed.u8()))
}

class MizzZeeV2(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int) = listOf(w(Ep.TX, 0x69, 0x96, 0x04, 0x02, speed.u8(), 0x2c, speed.u8()))
}

/** MizzZee v3: 0..1000 scale packed into a 10-bit field; needs a 200 ms keepalive. */
class MizzZeeV3(def: DeviceDefinition) : ProtocolHandler(def) {
    override val keepaliveMs: Long = 200
    override fun vibrate(index: Int, speed: Int) = listOf(Write(Ep.TX, vector(speed), true))

    companion object {
        fun vector(scalar: Int): ByteArray {
            if (scalar == 0) return bytes(0x03, 0x12, *IntArray(18))
            val s = scalar / 1000f
            val scale = (if (s == 0f) 0f else s * 0.7f + 0.3f) * 1023f
            val modded = ((scale.toInt() and 0xffff) shl 6 or 60) and 0xffff
            val le = bytes(modded and 0xff, modded shr 8)
            val fill = bytes(0x00, 0xfc, 0x00, 0xfe, 0x40, 0x01)
            return bytes(0x03, 0x12, 0xf3) + fill + le + fill + le + bytes(0x00)
        }
    }
}

/** MonsterPub: XOR challenge on connect; motors on txvibrate / tx / generic0. */
class MonsterPub(def: DeviceDefinition, private val tx: String) : ProtocolHandler(def) {
    private val speeds = IntArray(def.outputFeatureCount.coerceAtLeast(1))

    private fun cmd(): List<Write> {
        val data = mutableListOf<Int>()
        if (tx == Ep.GENERIC0) data += 3
        data += speeds.toList()
        val stop = speeds.all { it == 0 }
        val ep = if (tx == Ep.TX && stop) Ep.TX_MODE else tx
        return listOf(w(ep, data, withResponse = ep == Ep.TX_MODE))
    }

    override fun vibrate(index: Int, speed: Int): List<Write> { speeds.setSafe(index, speed.u8()); return cmd() }
    override fun oscillate(index: Int, speed: Int): List<Write> { speeds.setSafe(index, speed.u8()); return cmd() }

    companion object {
        private val KEYS = listOf("2IPO", "LSBB", "SIS6", "TALK").map { k ->
            (k.repeat(4).take(15)).toByteArray(Charsets.US_ASCII)
        }

        val spec = object : ProtocolSpec {
            override val id = "monsterpub"
            override suspend fun identify(io: DeviceIo) = Identified(
                io.read(Ep.RX_BLE_MODEL)?.let { String(it, Charsets.UTF_8).replace("\u0000", "") } ?: "Unknown",
            )

            override suspend fun create(def: DeviceDefinition, io: DeviceIo, identifier: String?): ProtocolHandler {
                if (io.hasEndpoint(Ep.RX)) {
                    val value = io.read(Ep.RX) ?: error("MonsterPub authentication packet is empty")
                    require(value.size >= 16) { "MonsterPub authentication packet is too short" }
                    val key = KEYS.getOrNull(value[0].toInt() and 0xff) ?: error("MonsterPub invalid key index")
                    val auth = ByteArray(15) { (value[1 + it].toInt() xor key[it].toInt()).toByte() }
                    io.write(Write(Ep.RX, auth, true))
                }
                val tx = when {
                    io.hasEndpoint(Ep.TX_VIBRATE) -> Ep.TX_VIBRATE
                    io.hasEndpoint(Ep.TX) -> Ep.TX
                    else -> Ep.GENERIC0
                }
                return MonsterPub(def, tx)
            }
        }
    }
}
