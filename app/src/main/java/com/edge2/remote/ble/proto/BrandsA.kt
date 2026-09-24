package com.edge2.remote.ble.proto

import com.edge2.remote.ble.db.DeviceDefinition
import kotlinx.coroutines.delay

// Ports of Buttplug protocols A–D (BSD-3-Clause, see THIRD_PARTY_NOTICES.md).

class ActiveJoy(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int) =
        listOf(w(Ep.TX, 0xb0, 0x01, 0x00, index, if (speed == 0) 0 else 1, speed.u8()))
}

class AdrienLastic(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int) =
        listOf(ascii(Ep.TX, "MotorValue:%02d;".format(speed), withResponse = true))
}

class AmorelieJoy(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int) = listOf(w(Ep.TX, 0x01, 0x01, speed.u8()))

    companion object {
        val spec = specInit("amorelie-joy") { def, io ->
            io.write(w(Ep.TX, 0x03))
            AmorelieJoy(def)
        }
    }
}

class Aneros(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int) = listOf(w(Ep.TX, 0xF1 + index, speed.u8()))
}

class Ankni(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int) =
        listOf(w(Ep.TX, listOf(0x03, 0x12, speed.u8()) + List(17) { 0 }, withResponse = true))

    companion object {
        val spec = specInit("ankni") { def, io ->
            val reading = io.read(Ep.GENERIC0)
            if (reading != null && reading.size <= 6) {
                val addr = byteArrayOf(0x01) + reading
                val check = (crc16(addr) and 0xff00) shr 8
                io.write(w(Ep.TX, List(20) { 0x01 }, withResponse = true))
                io.write(w(Ep.TX, listOf(0x01, 0x02) + List(16) { check } + listOf(0x00, 0x00), withResponse = true))
            }
            Ankni(def)
        }

        /** CRC-16/XMODEM-style (poly 0x1021, init 0). */
        fun crc16(data: ByteArray): Int {
            var remain = 0
            for (b in data) {
                remain = remain xor ((b.toInt() and 0xff) shl 8)
                repeat(8) {
                    remain = if (remain and 0x8000 != 0) ((remain shl 1) xor 0x1021) and 0xffff
                    else (remain shl 1) and 0xffff
                }
            }
            return remain
        }
    }
}

class Bananasome(def: DeviceDefinition) : ProtocolHandler(def) {
    private val cur = IntArray(3)
    private fun cmd(i: Int, v: Int): List<Write> {
        cur.setSafe(i, v.u8())
        return listOf(w(Ep.TX, 0xa0, 0x03, cur[0], cur[1], cur[2]))
    }
    override fun oscillate(index: Int, speed: Int) = cmd(index, speed)
    override fun vibrate(index: Int, speed: Int) = cmd(index, speed)
}

class Cachito(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int) = listOf(w(Ep.TX, 2 + index, 1 + index, speed.u8(), 0))
}

class Cowgirl(def: DeviceDefinition) : ProtocolHandler(def) {
    private val speeds = IntArray(2)
    private fun cmd() = listOf(w(Ep.TX, 0x00, 0x01, speeds[0], speeds[1], withResponse = true))
    override fun vibrate(index: Int, speed: Int): List<Write> { speeds[0] = speed.u8(); return cmd() }
    override fun rotate(index: Int, speed: Int): List<Write> { speeds[1] = speed.u8(); return cmd() }
}

class CowgirlCone(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int) = listOf(w(Ep.TX, 0xf1, 0x01, speed.u8(), 0x00))

    companion object {
        val spec = specInit("cowgirl-cone") { def, io ->
            io.write(w(Ep.TX, 0xaa, 0x56, 0x00, 0x00))
            delay(3000)
            CowgirlCone(def)
        }
    }
}

class Cupido(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int) = listOf(w(Ep.TX, 0xb0, 0x03, 0, 0, 0, speed.u8(), 0xaa))
}

class DeepSire(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int) = listOf(w(Ep.TX, 0x55, 0x04, 0x01, 0x00, 0x00, speed.u8(), 0xAA))
}
