package com.edge2.remote.ble.proto

import com.edge2.remote.ble.db.DeviceDefinition
import com.edge2.remote.ble.db.OutputType

// Ports of Buttplug's Sexverse protocols (BSD-3-Clause, see THIRD_PARTY_NOTICES.md).

class SexverseLG389(def: DeviceDefinition) : ProtocolHandler(def) {
    private var vibe = 0
    private var osc = 0
    private fun cmd(): List<Write> {
        val on = if (osc == 0) 0 else 1
        return listOf(w(Ep.TX, 0xaa, 0x05, vibe, 0x14, on, 0x00, if (osc == 0) 0 else 4, 0x00, osc, 0x00, withResponse = true))
    }
    override fun vibrate(index: Int, speed: Int): List<Write> { vibe = speed.u8(); return cmd() }
    override fun oscillate(index: Int, speed: Int): List<Write> { osc = speed.u8(); return cmd() }
}

/** Sexverse v1: every output (in feature order) in one XOR-checked packet. */
class SexverseV1(def: DeviceDefinition) : ProtocolHandler(def) {
    private val types = def.features.flatMap { f -> f.outputs.map { it.type } }
    private val values = IntArray(types.size)

    private fun cmd(index: Int, v: Int): List<Write> {
        values.setSafe(index, v.u8())
        val data = mutableListOf(0x23, 0x07, (types.size * 3).u8())
        types.forEachIndexed { i, t ->
            data += (0x80 or (i + 1)).u8()
            data += when (t) {
                OutputType.ROTATE -> 0x06
                OutputType.CONSTRICT, OutputType.OSCILLATE -> 0x04
                else -> 0x03
            }
            data += values[i]
        }
        data += data.fold(0) { c, b -> c xor b }
        return listOf(w(Ep.TX, data))
    }

    override fun vibrate(index: Int, speed: Int) = cmd(index, speed)
    override fun oscillate(index: Int, speed: Int) = cmd(index, speed)
    override fun rotate(index: Int, speed: Int) = cmd(index, speed)
    override fun constrict(index: Int, level: Int) = cmd(index, level)
}

class SexverseV2(def: DeviceDefinition) : ProtocolHandler(def) {
    private fun cmd(index: Int, v: Int) = listOf(w(Ep.TX, 0xaa, 0x03, 0x01, index + 1, 0x64, v.u8(), withResponse = true))
    override fun vibrate(index: Int, speed: Int) = cmd(index, speed)
    override fun oscillate(index: Int, speed: Int) = cmd(index, speed)

    companion object {
        val spec = specInit("sexverse-v2") { def, io ->
            io.write(w(Ep.TX, 0xaa, 0x04, withResponse = true))
            SexverseV2(def)
        }
    }
}

class SexverseV3(def: DeviceDefinition) : ProtocolHandler(def) {
    override val keepaliveMs: Long = 100
    private fun cmd(index: Int, v: Int) = listOf(w(Ep.TX, 0xa1, 0x04, v.u8(), index + 1, withResponse = true))
    override fun vibrate(index: Int, speed: Int) = cmd(index, speed)
    override fun rotate(index: Int, speed: Int) = cmd(index, speed)
}

class SexverseV4(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int) = listOf(w(Ep.TX, 0xbb, 0x01, speed.u8(), 0x66, withResponse = true))
}

class SexverseV5(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int) = listOf(w(Ep.TX, 0xaa, 0x03, 0x03, speed.u8(), 0, 0, 0))
}

class SexverseV6(def: DeviceDefinition) : ProtocolHandler(def) {
    private var vibe = 0
    private var osc = 0
    private var suck = 0
    private fun cmd() = listOf(w(Ep.TX, 0xaa, 0x03, 0x03, vibe, osc, suck))
    override fun vibrate(index: Int, speed: Int): List<Write> { vibe = speed.u8(); return cmd() }
    override fun oscillate(index: Int, speed: Int): List<Write> { osc = speed.u8(); return cmd() }
    override fun constrict(index: Int, level: Int): List<Write> { suck = level.u8(); return cmd() }
}
