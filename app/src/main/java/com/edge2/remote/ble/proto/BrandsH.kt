package com.edge2.remote.ble.proto

import com.edge2.remote.ble.db.DeviceDefinition
import com.edge2.remote.ble.db.OutputType
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// Ports of Buttplug protocols H (BSD-3-Clause, see THIRD_PARTY_NOTICES.md).

private suspend fun readModelHex(io: DeviceIo): String? =
    io.read(Ep.RX_BLE_MODEL)?.joinToString("") { "%02x".format(it) }

/** Hismith (legacy models); newer models are redirected to [HismithMini]. */
class Hismith(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun oscillate(index: Int, speed: Int): List<Write> {
        val s = speed.u8()
        return listOf(w(Ep.TX, 0xAA, 0x04, s, (s + 0x04).u8()))
    }

    override fun vibrate(index: Int, speed: Int): List<Write> {
        val idx = if (index == 0) 0x04 else 0x06
        val s = if (index != 0 && speed == 0) 0xf0 else speed.u8()
        return listOf(w(Ep.TX, 0xAA, idx, s, (s + idx).u8()))
    }

    companion object {
        private val LEGACY = setOf("1001", "1002", "1003", "3001", "2001", "1006")

        val spec = object : ProtocolSpec {
            override val id = "hismith"
            override suspend fun identify(io: DeviceIo): Identified {
                val model = readModelHex(io)
                return if (model != null && model !in LEGACY) Identified(model, "hismith-mini") else Identified(model)
            }
            override suspend fun create(def: DeviceDefinition, io: DeviceIo, identifier: String?) = Hismith(def)
        }
    }
}

class HismithMini(def: DeviceDefinition) : ProtocolHandler(def) {
    private val dualVibe = def.count(OutputType.VIBRATE) >= 2
    private val secondConstrict = def.features.indexOfFirst { f -> f.output(OutputType.CONSTRICT) != null } == 1

    private fun cmd(idx: Int, v: Int) = w(Ep.TX, 0xCC, idx, v.u8(), (v + idx).u8())

    override fun oscillate(index: Int, speed: Int) = listOf(cmd(0x03, speed))
    override fun vibrate(index: Int, speed: Int) = listOf(cmd(if (!dualVibe || index == 1) 0x05 else 0x03, speed))
    override fun constrict(index: Int, level: Int) = listOf(cmd(if (secondConstrict) 0x05 else 0x03, level))
    override fun rotate(index: Int, speed: Int): List<Write> {
        val s = kotlin.math.abs(speed)
        return listOf(
            w(Ep.TX, 0xCC, 0x03, s.u8(), (s + 3).u8()),
            w(Ep.TX, 0xCC, 0x01, if (speed >= 0) 0xc0 else 0xc1, if (speed >= 0) 0xc1 else 0xc2),
        )
    }
    override fun spray(index: Int, level: Int) =
        if (level > 0) listOf(w(Ep.TX, 0xcc, 0x0b, 0x01, 0x0c)) else emptyList()

    companion object {
        val spec = object : ProtocolSpec {
            override val id = "hismith-mini"
            override suspend fun identify(io: DeviceIo) = Identified(readModelHex(io))
            override suspend fun create(def: DeviceDefinition, io: DeviceIo, identifier: String?) = HismithMini(def)
        }
    }
}

/** HoneyPlayBox: framed packets, MD5-signed with a key from the handshake. */
class HoneyPlayBox(def: DeviceDefinition, private val randomKey: ByteArray, startCounter: Int) : ProtocolHandler(def) {
    private val last = IntArray(def.outputFeatureCount.coerceAtLeast(1))
    @Volatile private var packetId = startCounter
    @Volatile private var lastSend = System.currentTimeMillis()

    private fun packet(): Write {
        packetId = (packetId + 1) and 0xff
        val payload = buildVibrateData(randomKey, last)
        return Write(Ep.TX, buildFrame(0xB1, 0x03, payload, packetId), true)
    }

    private fun send(index: Int, v: Int): List<Write> {
        lastSend = System.currentTimeMillis()
        last.setSafe(index, v.u8())
        return listOf(packet())
    }

    override fun start(io: DeviceIo, scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                if (System.currentTimeMillis() - lastSend >= 500) {
                    lastSend = System.currentTimeMillis()
                    if (!io.write(packet())) return@launch
                }
                delay(100)
            }
        }
    }

    override fun vibrate(index: Int, speed: Int) = send(index, speed)
    override fun oscillate(index: Int, speed: Int) = send(index, speed)
    override fun rotate(index: Int, speed: Int) = send(index, kotlin.math.abs(speed))
    override fun constrict(index: Int, level: Int) = send(index, if (level == 0) 0 else level + 100)

    companion object {
        private val SECRET = bytes(0x8b, 0xe3, 0xfd, 0x04, 0x68, 0x35, 0x09, 0x86, 0x12, 0x1a, 0xbf, 0x03, 0x30, 0xe9, 0xe3, 0xc5)
        private val HEADER = bytes(0x02, 0xA5, 0x5A, 0x55, 0xAA, 0xF0)

        fun crc16Modbus(data: ByteArray): Int {
            var crc = 0xFFFF
            for (b in data) {
                crc = crc xor (b.toInt() and 0xff)
                repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
            }
            return crc
        }

        fun buildFrame(type: Int, cmd: Int, data: ByteArray, counter: Int): ByteArray {
            val len = data.size
            val crc = crc16Modbus(bytes(type, cmd, len shr 8, len and 0xff) + data)
            return HEADER + bytes(counter, type, cmd, len shr 8, len and 0xff) + data +
                bytes(0x03, crc shr 8, crc and 0xff)
        }

        fun buildVibrateData(random: ByteArray, strengths: IntArray): ByteArray {
            val data = strengths.indices.flatMap { i ->
                // work_mode 1, motor i, 6 s (60 × 100 ms), freq 0, strength
                listOf(((i and 0x0F) shl 4) or 1, 0x00, 0x05, 0, 60, 0, 0, strengths[i])
            }.let { l -> ByteArray(l.size) { l[it].toByte() } }
            val dataLen = data.size + 8
            val md5 = MessageDigest.getInstance("MD5").apply {
                update(bytes(0xB1, 0x03, dataLen shr 8, dataLen and 0xff))
                update(data); update(SECRET); update(random)
            }.digest()
            return data + md5.copyOfRange(0, 8)
        }

        /** Splits a notification stream into complete frames. */
        class FrameCollector {
            private var buf = ByteArray(0)
            fun push(data: ByteArray): List<ByteArray> {
                buf += data
                val frames = mutableListOf<ByteArray>()
                while (true) {
                    val start = (0..buf.size - 6).firstOrNull { s -> (0 until 6).all { buf[s + it] == HEADER[it] } }
                    if (start == null) { buf = ByteArray(0); break }
                    if (start > 0) buf = buf.copyOfRange(start, buf.size)
                    if (buf.size < 11) break
                    val dataLen = ((buf[9].toInt() and 0xff) shl 8) or (buf[10].toInt() and 0xff)
                    val frameLen = 14 + dataLen // stx+flag(5)+counter+type+cmd+len(2) + data + etx + crc(2)
                    if (buf.size < frameLen) break
                    if (buf[11 + dataLen] != 0x03.toByte()) { buf = buf.copyOfRange(1, buf.size); continue }
                    frames += buf.copyOfRange(0, frameLen)
                    buf = buf.copyOfRange(frameLen, buf.size)
                    if (buf.size < 11) break
                }
                return frames
            }
        }

        /** (response code, random key) from a handshake response frame. */
        fun parseHandshake(frame: ByteArray): Pair<Int?, ByteArray?> {
            if (frame.size < 15) return null to null
            val len = ((frame[9].toInt() and 0xff) shl 8) or (frame[10].toInt() and 0xff)
            val end = 11 + len
            if (frame.size < end + 3) return null to null
            val code = if (len >= 2) ((frame[11].toInt() and 0xff) shl 8) or (frame[12].toInt() and 0xff) else null
            val random = if (len >= 18 && 13 + 16 <= end) frame.copyOfRange(13, 29) else null
            return code to random
        }

        val spec = specInit("honeyplaybox") { def, io ->
            io.subscribe(Ep.RX)
            val collector = FrameCollector()
            var counter = 0
            repeat(4) { attempt ->
                io.write(Write(Ep.TX, buildFrame(0xB1, 0x01, bytes(0x00, 0x10), counter), true))
                counter = (counter + 1) and 0xff
                val deadline = System.currentTimeMillis() + (attempt + 1) * 1000L
                while (System.currentTimeMillis() < deadline) {
                    val n = io.awaitNotification(Ep.RX, deadline - System.currentTimeMillis()) ?: break
                    for (frame in collector.push(n)) {
                        val (code, random) = parseHandshake(frame)
                        if (code == 0x9000 && random != null) return@specInit HoneyPlayBox(def, random, counter)
                        if (code != null && code != 0x9000) error("HoneyPlayBox handshake failed, code=%04X".format(code))
                    }
                }
            }
            error("HoneyPlayBox handshake timed out")
        }
    }
}

class HtkBm(def: DeviceDefinition) : ProtocolHandler(def) {
    private val speeds = IntArray(2)
    override fun vibrate(index: Int, speed: Int): List<Write> {
        speeds.setSafe(index, speed.u8())
        val data = when {
            speeds[0] != 0 && speeds[1] != 0 -> 11
            speeds[0] != 0 -> 12
            speeds[1] != 0 -> 13
            else -> 15
        }
        return listOf(w(Ep.TX, data))
    }
}
