package com.edge2.remote.ble.proto

import com.edge2.remote.ble.db.DeviceDefinition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// Ports of Buttplug protocols F–H (BSD-3-Clause, see THIRD_PARTY_NOTICES.md).

/** Fredorch rotary machines: speed only moves by ±1 steps, tracked internally. */
class FredorchRotary(def: DeviceDefinition) : ProtocolHandler(def) {
    @Volatile private var target = 0
    @Volatile private var current = 0

    override fun start(io: DeviceIo, scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                val ts = target
                val cs = current
                if (ts != cs) {
                    val cmd = if (ts == 0) 0x24 else if (ts > cs) 0x01 else 0x02
                    if (!io.write(w(Ep.TX, 0x55, 0x03, cmd, cmd + 3, 0xaa))) return@launch
                    current = maxOf(if (ts == 0) 0 else if (ts > cs) cs + 1 else cs - 1, 0)
                    continue
                }
                delay(100)
            }
        }
    }

    override fun oscillate(index: Int, speed: Int): List<Write> {
        target = speed.u8()
        if (speed == 0) {
            current = 0
            return listOf(w(Ep.TX, 0x55, 0x03, 0x24, 0x27, 0xaa))
        }
        return emptyList()
    }

    companion object {
        val spec = specInit("fredorch-rotary") { def, io ->
            io.subscribe(Ep.RX)
            listOf(
                bytes(0x55, 0x03, 0x99, 0x9c, 0xaa), // start the handshake
                bytes(0x55, 0x09, 0x21, 0, 0, 0, 0, 0, 0, 0x2a, 0xaa), // password
                bytes(0x55, 0x03, 0x1f, 0x22, 0xaa), // power up
                bytes(0x55, 0x03, 0x24, 0x27, 0xaa), // stop
            ).forEach {
                io.write(Write(Ep.TX, it))
                io.awaitNotification(Ep.RX, 100)
            }
            FredorchRotary(def)
        }
    }
}

/** Galaku: table-keyed obfuscation of every packet. */
class Galaku(def: DeviceDefinition, private val caipingPump: Boolean) : ProtocolHandler(def) {
    private val speeds = IntArray(def.outputFeatureCount.coerceAtLeast(1))

    private fun cmd(index: Int, speed: Int): List<Write> {
        if (speeds.size == 1) {
            return if (caipingPump) {
                listOf(w(Ep.TX, listOf(0xAA, 1, 10, 3, speed.u8(), if (speed == 0) 0 else 1) + List(10) { 0 }))
            } else {
                listOf(Write(Ep.TX, sendBytes(intArrayOf(90, 0, 0, 1, 49, speed, 0, 0, 0, 0))))
            }
        }
        speeds.setSafe(index, speed.u8())
        return listOf(Write(Ep.TX, sendBytes(intArrayOf(90, 0, 0, 1, 64, 3, speeds[0], speeds.getOrElse(1) { 0 }, 0, 0))))
    }

    override fun oscillate(index: Int, speed: Int) = cmd(index, speed)
    override fun vibrate(index: Int, speed: Int) = cmd(index, speed)
    override fun constrict(index: Int, level: Int) = cmd(index, level)

    override suspend fun battery(io: DeviceIo): Int? {
        if (!io.hasEndpoint(Ep.RX_BLE_BATTERY)) return null
        io.subscribe(Ep.RX_BLE_BATTERY)
        io.write(Write(Ep.TX, sendBytes(intArrayOf(90, 0, 0, 1, 19, 0, 0, 0, 0, 0)), true))
        return io.awaitNotification(Ep.RX_BLE_BATTERY, 1_000)?.firstOrNull()?.toInt()?.and(0xff)
    }

    companion object {
        private val KEY_TAB = arrayOf(
            intArrayOf(0, 24, 152, 247, 165, 61, 13, 41, 37, 80, 68, 70),
            intArrayOf(0, 69, 110, 106, 111, 120, 32, 83, 45, 49, 46, 55),
            intArrayOf(0, 101, 120, 32, 84, 111, 121, 115, 10, 142, 157, 163),
            intArrayOf(0, 197, 214, 231, 248, 10, 50, 32, 111, 98, 13, 10),
        )

        fun encrypt(data: LongArray): LongArray {
            val out = LongArray(data.size)
            out[0] = data[0]
            for (i in 1 until data.size) {
                val a = KEY_TAB[(out[i - 1] and 3).toInt()][i].toLong()
                out[i] = (a xor data[0] xor data[i]) + a
            }
            return out
        }

        fun sendBytes(data: IntArray): ByteArray {
            val d = LongArray(data.size + 2)
            d[0] = 35
            data.forEachIndexed { i, v -> d[i + 1] = v.toLong() }
            d[d.size - 1] = d.sum()
            return encrypt(d).let { e -> ByteArray(e.size) { e[it].toByte() } }
        }

        val spec = specInit("galaku") { def, io -> Galaku(def, caipingPump = io.name == "AC695X_1(BLE)") }
    }
}

class GalakuPump(def: DeviceDefinition) : ProtocolHandler(def) {
    private val speeds = IntArray(2)

    private fun cmd(): List<Write> {
        val data = mutableListOf(0x23, 0x5a, 0, 0, 0x01, 0x60, 0x03, speeds[0], speeds[1], 0, 0)
        data += data.sum() and 0xff
        val out = mutableListOf(0x23)
        for (i in 1 until data.size) {
            val k = KEY_TAB[out[i - 1] and 3][i]
            out += (((k xor 0x23) xor data[i]) + k) and 0xff
        }
        return listOf(w(Ep.TX, out, withResponse = true))
    }

    override fun oscillate(index: Int, speed: Int): List<Write> { speeds[0] = speed.u8(); return cmd() }
    override fun vibrate(index: Int, speed: Int): List<Write> { speeds[1] = speed.u8(); return cmd() }

    private companion object {
        val KEY_TAB = arrayOf(
            intArrayOf(0, 24, 0x98, 0xf7, 0xa5, 61, 13, 41, 37, 80, 68, 70),
            intArrayOf(0, 69, 110, 106, 111, 120, 32, 83, 45, 49, 46, 55),
            intArrayOf(0, 101, 120, 32, 84, 111, 121, 115, 10, 0x8e, 0x9d, 0xa3),
            intArrayOf(0, 0xc5, 0xd6, 0xe7, 0xf8, 10, 50, 32, 111, 98, 13, 10),
        )
    }
}

/** Hgod: needs the speed re-sent every 100 ms while running. */
class Hgod(def: DeviceDefinition) : ProtocolHandler(def) {
    @Volatile private var speed = 0

    override fun start(io: DeviceIo, scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                val s = speed
                if (s > 0 && !io.write(w(Ep.TX, 0x55, 0x04, 0, 0, 0, s))) return@launch
                delay(100)
            }
        }
    }

    override fun vibrate(index: Int, speed: Int): List<Write> { this.speed = speed.u8(); return emptyList() }
}
