package com.edge2.remote.ble.proto

import com.edge2.remote.ble.db.DeviceDefinition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// Ports of Buttplug protocols O–S (BSD-3-Clause, see THIRD_PARTY_NOTICES.md).

/** OSSM (open-source stroking machine): text commands; stroke-engine or streaming mode. */
class Ossm(def: DeviceDefinition) : ProtocolHandler(def) {
    private var mode = NONE

    override fun start(io: DeviceIo, scope: CoroutineScope) {
        // Follows the machine's state and re-applies full depth/stroke when it changes mode.
        scope.launch {
            var last = "unknown"
            while (isActive) {
                val payload = io.awaitNotification(Ep.RX, 60_000) ?: continue
                val state = runCatching {
                    Json.parseToJsonElement(String(payload, Charsets.UTF_8)).jsonObject["state"]?.jsonPrimitive?.content
                }.getOrNull() ?: continue
                val st = state.substringBefore('.')
                if (st == last) continue
                last = st
                if (st == "streaming" || st == "strokeEngine") {
                    io.write(ascii(Ep.TX_MODE, "false", true))
                    io.write(ascii(Ep.TX, "set:depth:100", true))
                    io.write(ascii(Ep.TX, "set:stroke:100", true))
                    if (st == "streaming") io.write(ascii(Ep.TX, "set:speed:100", true))
                }
            }
        }
    }

    private fun enter(target: Int, menu: String): List<Write> {
        if (mode == target) return emptyList()
        mode = target
        return listOf(ascii(Ep.TX, "go:menu", true), ascii(Ep.TX, menu, true))
    }

    override fun oscillate(index: Int, speed: Int): List<Write> {
        if (index != 0) return emptyList()
        return enter(OSCILLATE, "go:strokeEngine") + ascii(Ep.TX, "set:speed:$speed", true)
    }

    override fun hwPosition(index: Int, position: Int, durationMs: Int) =
        enter(POSITION, "go:streaming") + ascii(Ep.TX, "stream:$position:$durationMs", true)

    companion object {
        private const val NONE = 0
        private const val OSCILLATE = 1
        private const val POSITION = 2

        val spec = specInit("ossm") { def, io ->
            io.subscribe(Ep.RX)
            Ossm(def)
        }
    }
}

class Patoo(def: DeviceDefinition) : ProtocolHandler(def) {
    private val speeds = IntArray(2)
    override fun vibrate(index: Int, speed: Int): List<Write> {
        speeds.setSafe(index, speed.u8())
        var mode = 4
        var s = speeds[0]
        if (s == 0) {
            mode = 0
            if (speeds[1] != 0) { s = speeds[1]; mode = mode or 0x80 }
        }
        return listOf(w(Ep.TX, s, withResponse = true), w(Ep.TX_MODE, mode, withResponse = true))
    }

    companion object {
        /** Identifier = the BLE name up to its first digit. */
        val spec = object : ProtocolSpec {
            override val id = "patoo"
            override suspend fun identify(io: DeviceIo) = Identified(io.name.takeWhile { !it.isDigit() })
            override suspend fun create(def: DeviceDefinition, io: DeviceIo, identifier: String?) = Patoo(def)
        }
    }
}

class Picobong(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int) = listOf(w(Ep.TX, 0x01, if (speed == 0) 0xff else 0x01, speed.u8()))
}

class PinkPunch(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int) = listOf(w(Ep.TX, 0x09, speed.u8(), withResponse = true))
}

class PrettyLove(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int) = listOf(w(Ep.TX, 0x00, speed.u8(), withResponse = true))

    companion object {
        val spec = object : ProtocolSpec {
            override val id = "prettylove"
            override suspend fun identify(io: DeviceIo) = Identified("Aogu BLE")
            override suspend fun create(def: DeviceDefinition, io: DeviceIo, identifier: String?) = PrettyLove(def)
        }
    }
}

class Realov(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int) = listOf(w(Ep.TX, 0xc5, 0x55, speed.u8(), 0xaa))
}

class Sakuraneko(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int) =
        listOf(w(Ep.TX, 0xa1, 0x08, 0x01, 0, 0, 0, 0x64, speed.u8(), 0x00, 0x64, 0xdf, 0x55))
    override fun rotate(index: Int, speed: Int) =
        listOf(w(Ep.TX, 0xa2, 0x08, 0x01, 0, 0, 0, 0x64, speed.u8(), 0x00, 0x32, 0xdf, 0x55))
}
