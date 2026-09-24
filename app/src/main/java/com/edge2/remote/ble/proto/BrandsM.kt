package com.edge2.remote.ble.proto

import com.edge2.remote.ble.db.DeviceDefinition

// Ports of Buttplug protocols L–M (BSD-3-Clause, see THIRD_PARTY_NOTICES.md).

class LoveDistance(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int) = listOf(w(Ep.TX, 0xf3, 0x00, speed.u8()))

    companion object {
        val spec = specInit("lovedistance") { def, io ->
            io.write(w(Ep.TX, 0xf3, 0, 0))
            io.write(w(Ep.TX, 0xf4, 1))
            LoveDistance(def)
        }
    }
}

class LovehoneyDesire(def: DeviceDefinition) : ProtocolHandler(def) {
    private val cur = IntArray(def.outputFeatureCount.coerceAtLeast(1))
    override fun vibrate(index: Int, speed: Int): List<Write> {
        if (cur.size == 1) return listOf(w(Ep.TX, 0xF3, 0, speed.u8(), withResponse = true))
        cur.setSafe(index, speed.u8())
        return if (cur[0] == cur[1]) listOf(w(Ep.TX, 0xF3, 0, cur[0], withResponse = true))
        else listOf(w(Ep.TX, 0xF3, 1, cur[0], withResponse = true), w(Ep.TX, 0xF3, 2, cur[1], withResponse = true))
    }
}

class LoveNuts(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int): List<Write> {
        val s = speed.u8()
        val b = (s or (s shl 4)).u8()
        return listOf(w(Ep.TX, listOf(0x45, 0x56, 0x4f, 0x4c) + List(10) { b } + listOf(0x00, 0xff)))
    }
}

class Luvmazer(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int) =
        if (index == 2) listOf(w(Ep.TX, 0xa0, 0x0c, 0x00, 0x00, 0x64, speed.u8()))
        else listOf(w(Ep.TX, 0xa0, 0x01, 0x00, index, 0x64, speed.u8()))
    override fun rotate(index: Int, speed: Int) = listOf(w(Ep.TX, 0xa0, 0x0f, 0x00, 0x00, 0x64, speed.u8()))
    override fun oscillate(index: Int, speed: Int) = listOf(w(Ep.TX, 0xa0, 0x06, 0x01, 0x00, 0x64, speed.u8()))
    override fun constrict(index: Int, level: Int) =
        listOf(w(Ep.TX, 0xa0, 0x0d, 0x00, 0x00, if (level == 0) 0 else 0x14, level.u8()))
}

class MagicMotionV1(def: DeviceDefinition) : ProtocolHandler(def) {
    private fun frame(s: Int) = listOf(w(Ep.TX, 0x0b, 0xff, 0x04, 0x0a, 0x32, 0x32, 0x00, 0x04, 0x08, s.u8(), 0x64, 0x00))
    override fun vibrate(index: Int, speed: Int) = frame(speed)
    override fun oscillate(index: Int, speed: Int) = frame(speed)
}

class MagicMotionV2(def: DeviceDefinition) : ProtocolHandler(def) {
    private val speeds = IntArray(2)
    override fun vibrate(index: Int, speed: Int): List<Write> {
        speeds.setSafe(index, speed.u8())
        return listOf(w(Ep.TX, 0x10, 0xff, 0x04, 0x0a, 0x32, 0x0a, 0x00, 0x04, 0x08, speeds[0], 0x64, 0x00, 0x04, 0x08, speeds[1], 0x64, 0x01))
    }
}

class MagicMotionV3(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int) =
        listOf(w(Ep.TX, 0x0b, 0xff, 0x04, 0x0a, 0x46, 0x46, 0x00, 0x04, 0x08, speed.u8(), 0x64, 0x00))
}

class MagicMotionV4(def: DeviceDefinition) : ProtocolHandler(def) {
    private val cur = IntArray(def.outputFeatureCount.coerceAtLeast(1))
    override fun vibrate(index: Int, speed: Int): List<Write> {
        val (s0, s1) = if (cur.size == 1) speed.u8() to speed.u8() else {
            cur.setSafe(index, speed.u8()); cur[0] to cur[1]
        }
        return listOf(w(Ep.TX, 0x10, 0xff, 0x04, 0x0a, 0x32, 0x32, 0x00, 0x04, 0x08, s0, 0x64, 0x00, 0x04, 0x08, s1, 0x64, 0x01, withResponse = true))
    }
}
