package com.edge2.remote.ble.proto

import com.edge2.remote.ble.db.DeviceDefinition
import com.edge2.remote.ble.db.OutputType
import kotlin.math.abs
import kotlin.math.ceil

// Ports of Buttplug protocols S (BSD-3-Clause, see THIRD_PARTY_NOTICES.md).

class Sensee(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int) =
        listOf(w(Ep.TX, 0x55, 0xaa, 0xf0, 0x01, 0x01, 0x0b, 0x65, 0xf7, 0x01, 0x01, speed.u8()))
}

class SenseeCapsule(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int) =
        listOf(w(Ep.TX, 0x55, 0xaa, 0xf0, 0x01, 0x00, 0x12, 0x66, 0xf9, (0xf0 or speed).u8()))
    override fun constrict(index: Int, level: Int) =
        listOf(w(Ep.TX, 0x55, 0xaa, 0xf0, 0x01, 0x00, 0x11, 0x66, 0xf2, (0xf0 or level).u8(), 0x00, 0x00))
}

/** Sensee v2: vibration / thrust / suction groups in one framed packet. */
class SenseeV2(def: DeviceDefinition, private val deviceType: Int) : ProtocolHandler(def) {
    private fun map(t: OutputType) = def.features.filter { f -> f.output(t) != null }.map { it.index }
    private val vibe = map(OutputType.VIBRATE).associateWith { 0 }.toMutableMap()
    private val thrust = map(OutputType.OSCILLATE).associateWith { 0 }.toMutableMap()
    private val suck = map(OutputType.CONSTRICT).associateWith { 0 }.toMutableMap()

    private fun compile(): List<Write> {
        val data = mutableListOf(listOf(vibe, thrust, suck).count { it.isNotEmpty() })
        listOf(vibe, thrust, suck).forEachIndexed { group, m ->
            if (m.isEmpty()) return@forEachIndexed
            data += group
            data += m.size
            m.toSortedMap().values.forEachIndexed { i, v -> data += i + 1; data += v }
        }
        return listOf(w(Ep.TX, makeCmd(deviceType, 0xf1, data)))
    }

    override fun vibrate(index: Int, speed: Int): List<Write> { if (index in vibe) vibe[index] = speed.u8(); return compile() }
    override fun oscillate(index: Int, speed: Int): List<Write> { if (index in thrust) thrust[index] = speed.u8(); return compile() }
    override fun constrict(index: Int, level: Int): List<Write> { if (index in suck) suck[index] = level.u8(); return compile() }

    companion object {
        fun makeCmd(type: Int, func: Int, cmd: List<Int>) =
            listOf(0x55, 0xAA, 0xF0, 0x02, 0x00, 0x04 + cmd.size, type, func) + cmd + listOf(0, 0)

        val spec = specInit("sensee-v2") { def, io ->
            val res = io.read(Ep.TX)
            val type = if (res != null && res.size > 6 && res[6].toInt() != 0) res[6].toInt() and 0xff else 0x65
            SenseeV2(def, type)
        }
    }
}

class ServeU(def: DeviceDefinition) : ProtocolHandler(def) {
    private var last = 0
    override fun hwPosition(index: Int, position: Int, durationMs: Int): List<Write> {
        val goal = position.u8()
        val threshold = ceil(abs(goal.toByte() - last.toByte()) / (durationMs / 1000.0))
        last = goal
        val speed = when {
            threshold <= 0.00001 || threshold.isNaN() -> 0
            threshold <= 50.0 -> ceil(threshold / 2.0).satU8()
            threshold <= 750.0 -> (ceil((threshold - 50.0) / 4.0).satU8() + 25).u8()
            threshold <= 2000.0 -> (ceil((threshold - 750.0) / 25.0).satU8() + 200).u8()
            else -> 0xFA
        }
        return listOf(w(Ep.TX, 0x01, goal, speed))
    }
}
