package com.edge2.remote.ble.proto

import com.edge2.remote.ble.db.DeviceDefinition
import kotlin.math.abs

// Ports of Buttplug protocols F, Y–Z (BSD-3-Clause, see THIRD_PARTY_NOTICES.md).

/** Yiciyuan: stroke / vibration / 3rd axis, each rescaled from 0..100 to 0..20. */
class Yiciyuan(def: DeviceDefinition) : ProtocolHandler(def) {
    private val axes = IntArray(3)
    private fun cmd(index: Int, value: Int): List<Write> {
        if (index !in 0..2) return emptyList()
        axes[index] = (value.coerceIn(0, 100) * 0x14 + 50) / 100
        return listOf(w(Ep.TX, listOf(0x35, 0x12, axes[0], axes[1], axes[2]) + List(11) { 0 }))
    }
    override fun oscillate(index: Int, speed: Int) = cmd(index, speed)
    override fun vibrate(index: Int, speed: Int) = cmd(index, speed)

    override suspend fun battery(io: DeviceIo): Int? {
        if (!io.hasEndpoint(Ep.RX_BLE_BATTERY)) return null
        io.subscribe(Ep.RX_BLE_BATTERY)
        val d = io.awaitNotification(Ep.RX_BLE_BATTERY, 2_000) ?: return null
        return if (d.size >= 4 && d[0].toInt() == 0x35 && d[1].toInt() == 0x13) d[3].toInt() and 0xff else null
    }
}

class Youcups(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int) = listOf(ascii(Ep.TX, "\$SYS,${speed.u8()}?"))
}

class Youou(def: DeviceDefinition) : ProtocolHandler(def) {
    private var packetId = 0
    override fun vibrate(index: Int, speed: Int): List<Write> {
        val data = mutableListOf(0xaa, 0x55, packetId, 0x02, 0x03, 0x01, speed.u8(), if (speed > 0) 1 else 0)
        packetId = (packetId + 1) and 0xff
        val crc = data.fold(0) { c, b -> c xor b }
        return listOf(w(Ep.TX, data + listOf(crc, 0xff) + List(7) { 0 }))
    }

    companion object {
        val spec = object : ProtocolSpec {
            override val id = "youou"
            override suspend fun identify(io: DeviceIo) = Identified("VX001_")
            override suspend fun create(def: DeviceDefinition, io: DeviceIo, identifier: String?) = Youou(def)
        }
    }
}

class Zalo(def: DeviceDefinition) : ProtocolHandler(def) {
    private val speeds = IntArray(2)
    override fun vibrate(index: Int, speed: Int): List<Write> {
        speeds.setSafe(index, speed.u8())
        val (a, b) = speeds[0] to speeds[1]
        return listOf(w(Ep.TX, if (a == 0 && b == 0) 0x02 else 0x01, if (a == 0) 1 else a, if (b == 0) 1 else b, withResponse = true))
    }
}

/** Fredorch (F21S…): Modbus-style framed position program. */
class Fredorch(def: DeviceDefinition) : ProtocolHandler(def) {
    private var previous = 0

    private fun frame(speed: Int, from: Int, to: Int): Write {
        val data = listOf(0x01, 0x10, 0x00, 0x6B, 0x00, 0x05, 0x0a, 0x00, speed, 0x00, speed, 0x00, from, 0x00, to, 0x00, 0x01)
        return withCrc(data)
    }

    override fun hwPosition(index: Int, position: Int, durationMs: Int): List<Write> {
        val distance = abs(previous - position)
        val pos = (position * 10).u8()
        val speed = speedFor(distance, durationMs)
        previous = position.u8()
        return listOf(frame(speed, pos, pos))
    }

    override fun oscillate(index: Int, speed: Int): List<Write> =
        listOf(frame(speed.u8(), 0, if (speed == 0) 0 else 15 * 15))

    companion object {
        /** Same bytes as Buttplug's table-driven CRC (= CRC-16/MODBUS, low byte first). */
        internal fun withCrc(data: List<Int>): Write {
            val crc = HoneyPlayBox.crc16Modbus(ByteArray(data.size) { data[it].toByte() })
            return w(Ep.TX, data + listOf(crc and 0xff, crc shr 8))
        }

        private val SPEED_MATRIX = arrayOf(
            intArrayOf(1000, 800, 400, 235, 200, 172, 155, 92, 60, 45, 38, 34, 32, 28, 27, 26, 25, 24, 23, 22),
            intArrayOf(1500, 1000, 800, 680, 600, 515, 425, 265, 165, 115, 80, 70, 50, 48, 45, 35, 34, 33, 32, 30),
            intArrayOf(2500, 2310, 1135, 925, 792, 695, 565, 380, 218, 155, 105, 82, 70, 68, 65, 60, 48, 45, 43, 40),
            intArrayOf(3000, 2800, 1500, 1155, 965, 810, 690, 465, 260, 195, 140, 110, 85, 75, 74, 73, 70, 65, 60, 55),
            intArrayOf(3400, 3232, 2305, 1380, 1200, 1165, 972, 565, 328, 235, 162, 132, 98, 78, 75, 74, 73, 72, 71, 70),
            intArrayOf(3500, 3350, 2500, 1640, 1250, 1210, 1010, 645, 385, 275, 175, 160, 115, 95, 91, 90, 85, 80, 77, 75),
            intArrayOf(3600, 3472, 2980, 2060, 1560, 1275, 1132, 738, 430, 310, 230, 170, 128, 122, 110, 108, 105, 103, 101, 100),
            intArrayOf(3800, 3500, 3055, 2105, 1740, 1370, 1290, 830, 490, 355, 235, 195, 150, 140, 135, 132, 130, 125, 120, 119),
            intArrayOf(3900, 3518, 3190, 2315, 2045, 1510, 1442, 1045, 552, 392, 280, 225, 172, 145, 140, 138, 135, 134, 132, 130),
            intArrayOf(6000, 5755, 3240, 2530, 2135, 1605, 1500, 1200, 595, 425, 285, 245, 175, 170, 160, 155, 150, 145, 142, 140),
            intArrayOf(6428, 5872, 3335, 2780, 2270, 1782, 1590, 1310, 648, 470, 315, 255, 182, 180, 175, 172, 170, 162, 160, 155),
            intArrayOf(6730, 5950, 3490, 2995, 2395, 1890, 1650, 1350, 700, 500, 350, 290, 220, 190, 185, 180, 175, 170, 165, 160),
            intArrayOf(6962, 6122, 3880, 3205, 2465, 1900, 1700, 1400, 835, 545, 375, 310, 228, 195, 190, 185, 182, 181, 180, 175),
            intArrayOf(7945, 6365, 4130, 3470, 2505, 1910, 1755, 1510, 855, 580, 400, 330, 235, 210, 205, 200, 195, 190, 185, 180),
            intArrayOf(8048, 7068, 4442, 3708, 2668, 1930, 1800, 1520, 878, 618, 428, 365, 260, 255, 250, 240, 230, 220, 210, 200),
        )

        fun speedFor(distance: Int, durationMs: Int): Int {
            val d = distance.coerceIn(0, 15)
            if (d == 0) return 0
            var speed = 1
            while (speed < 20) {
                if (SPEED_MATRIX[d - 1][speed - 1] < durationMs) return speed
                speed++
            }
            return speed
        }

        val spec = specInit("fredorch") { def, io ->
            io.subscribe(Ep.RX)
            io.awaitNotification(Ep.RX, 500)
            listOf(
                listOf(0x01, 0x06, 0x00, 0x64, 0x00, 0x01), // program mode
                listOf(0x01, 0x06, 0x00, 0x69, 0x00, 0x00), // record
                listOf(0x01, 0x10, 0x00, 0x6b, 0x00, 0x05, 0x0a, 0x00, 0x05, 0x00, 0x05, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01), // go to 0
                listOf(0x01, 0x06, 0x00, 0x69, 0x00, 0x01), // run
                listOf(0x01, 0x06, 0x00, 0x6a, 0x00, 0x01), // repeat
            ).forEach {
                io.write(withCrc(it))
                io.awaitNotification(Ep.RX, 500) ?: error("Fredorch timed out while initialising")
            }
            Fredorch(def)
        }
    }
}
