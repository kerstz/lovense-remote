package com.edge2.remote.ble.proto

import com.edge2.remote.ble.db.DeviceDefinition

// Ports of Buttplug protocols M–O (BSD-3-Clause, see THIRD_PARTY_NOTICES.md).

class Motorbunny(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int): List<Write> {
        if (speed == 0) return listOf(w(Ep.TX, 0xf0, 0, 0, 0, 0, 0xec))
        val body = List(7) { listOf(speed.u8(), 0x14) }.flatten()
        return listOf(w(Ep.TX, listOf(0xff) + body + listOf(body.sum() and 0xff, 0xec)))
    }

    override fun rotate(index: Int, speed: Int): List<Write> {
        if (speed == 0) return listOf(w(Ep.TX, 0xa0, 0, 0, 0, 0, 0xec))
        val body = List(7) { listOf(if (speed >= 0) 0x2a else 0x29, kotlin.math.abs(speed).u8()) }.flatten()
        return listOf(w(Ep.TX, listOf(0xaf) + body + listOf(body.sum() and 0xff, 0xec)))
    }
}

class MyMuseLinkPlus(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int) = listOf(
        if (speed == 0) w(Ep.TX, 0xAA, 0x55, 0x06, 0xAA, 0, 0, 0, 0)
        else w(Ep.TX, 0xAA, 0x55, 0x06, 0x01, 0x01, 0x01, speed.u8(), 0xFF),
    )
}

/** MysteryVibe (Crescendo, Tenuto…): all motors in one packet, 93 ms keepalive. */
class MysteryVibe(def: DeviceDefinition) : ProtocolHandler(def) {
    private val speeds = IntArray(def.outputFeatureCount.coerceAtLeast(1))
    override val keepaliveMs: Long = 93
    override fun vibrate(index: Int, speed: Int): List<Write> {
        speeds.setSafe(index, speed.u8())
        return listOf(w(Ep.TX_VIBRATE, speeds.toList()))
    }

    companion object {
        val spec = specInit("mysteryvibe") { def, io ->
            io.write(w(Ep.TX_MODE, 0x43, 0x02, 0x00, withResponse = true))
            MysteryVibe(def)
        }
        val specV2 = specInit("mysteryvibe-v2") { def, io ->
            io.write(w(Ep.TX_MODE, 0x03, 0x02, 0x40, withResponse = true))
            MysteryVibe(def)
        }
    }
}

class NexusRevo(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int) = listOf(w(Ep.TX, 0xaa, 0x01, 0x01, 0x00, 0x01, speed.u8(), withResponse = true))
    override fun rotate(index: Int, speed: Int) = listOf(
        w(Ep.TX, 0xaa, 0x01, 0x02, 0x00, (kotlin.math.abs(speed) + if (speed > 0) 2 else 0).u8(), 0x00, withResponse = true),
    )
}

class Nobra(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int) = listOf(w(Ep.TX, (if (speed == 0) 0x70 else 0x60 + speed).u8()))

    companion object {
        val spec = specInit("nobra") { def, io ->
            io.write(w(Ep.TX, 0x70))
            Nobra(def)
        }
    }
}

class Omobo(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int) = listOf(w(Ep.TX, 0xa1, 0x04, 0x04, 0x01, speed.u8(), 0xff, 0x55, withResponse = true))
}
