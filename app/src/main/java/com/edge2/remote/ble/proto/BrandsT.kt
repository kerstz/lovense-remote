package com.edge2.remote.ble.proto

import com.edge2.remote.ble.db.DeviceDefinition
import kotlin.math.abs

// Ports of Buttplug protocols S–U (BSD-3-Clause, see THIRD_PARTY_NOTICES.md).

class Synchro(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun rotate(index: Int, speed: Int) =
        listOf(w(Ep.TX, 0xa1, 0x01, (abs(speed).u8() or if (speed >= 0) 0 else 0x80), 0x77, 0x55))
}

/** TryFun checksum: 0xff minus each byte (from index 1) plus a running counter. */
internal fun tryFunSum(data: List<Int>, countStart: Int): Int {
    var sum = 0xff
    var count = countStart
    for (item in data.drop(1)) {
        sum = (sum - item) and 0xff
        count += 1
        sum = (sum + count) and 0xff
    }
    return sum
}

class TryFun(def: DeviceDefinition) : ProtocolHandler(def) {
    private fun cmd(func: Int, v: Int): List<Write> {
        val data = listOf(0xAA, 0x02, func, v.u8())
        return listOf(w(Ep.TX, data + tryFunSum(data, 0), withResponse = true))
    }
    override fun oscillate(index: Int, speed: Int) = cmd(0x07, speed)
    override fun rotate(index: Int, speed: Int) = cmd(0x08, speed)
    override fun vibrate(index: Int, speed: Int) = listOf(
        w(
            Ep.TX, 0x00, 0x02, 0x00, 0x05, if (speed == 0) 1 else 2, if (speed == 0) 2 else speed.u8(), 0x01,
            if (speed == 0) 1 else 0, (0xfd - maxOf(speed.u8(), 1)).u8(), withResponse = true,
        ),
    )
}

class TryFunBlackHole(def: DeviceDefinition) : ProtocolHandler(def) {
    private var packetId = 0
    private fun cmd(func: Int, v: Int): List<Write> {
        val data = listOf(packetId, 0x02, 0x00, 0x03, func, v.u8())
        packetId = (packetId + 1) and 0xff
        return listOf(w(Ep.TX, data + tryFunSum(data, 1)))
    }
    override fun oscillate(index: Int, speed: Int) = cmd(0x0c, speed)
    override fun vibrate(index: Int, speed: Int) = cmd(0x09, speed)
}

class TryFunMeta2(def: DeviceDefinition) : ProtocolHandler(def) {
    private var packetId = 0
    private fun cmd(func: Int, v: Int): List<Write> {
        val data = listOf(packetId, 0x02, 0x00, 0x05, 0x21, 0x05, func, v.u8())
        packetId = (packetId + 1) and 0xff
        return listOf(w(Ep.TX, data + tryFunSum(data, 1)))
    }
    override fun oscillate(index: Int, speed: Int) = cmd(0x0b, speed)
    override fun vibrate(index: Int, speed: Int) = cmd(0x08, speed)
    override fun rotate(index: Int, speed: Int): List<Write> {
        var s = speed.toByte().toInt()
        if (s >= 0) s += 1
        s = -s
        return cmd(0x0e, s)
    }
}

class Utimi(def: DeviceDefinition) : ProtocolHandler(def) {
    private val last = IntArray(5)
    private fun cmd(i: Int, v: Int): List<Write> {
        last.setSafe(i, v.u8())
        return listOf(w(Ep.TX, 0xa0, 0x03, last[0], last[1], last[2], last[3], last[4], 0xaa))
    }
    override fun vibrate(index: Int, speed: Int) = cmd(index, speed)
    override fun oscillate(index: Int, speed: Int) = cmd(index, speed)
}
