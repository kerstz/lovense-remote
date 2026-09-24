package com.edge2.remote.ble.proto

import com.edge2.remote.ble.db.DeviceDefinition

// Ports of Buttplug protocols I–K (BSD-3-Clause, see THIRD_PARTY_NOTICES.md).

class IToys(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int) = listOf(w(Ep.TX, 0xa0, 0x01, 0x00, 0x00, speed.u8(), 0xff))
    override fun oscillate(index: Int, speed: Int) = listOf(
        w(Ep.TX, 0xa0, 0x06, if (speed == 0) 0 else 1, 0x00, if (speed == 0) 0 else 1, speed.u8()),
    )
    override fun rotate(index: Int, speed: Int) = listOf(
        w(Ep.TX, 0xa0, 0x08, if (speed == 0) 0 else 1, 0x00, kotlin.math.abs(speed).u8(), if (speed == 0) 0 else 0xff),
    )
    override fun constrict(index: Int, level: Int) =
        listOf(w(Ep.TX, 0xa0, 0x0d, 0x00, 0x00, level.u8(), if (level == 0) 0 else 0x64))
}

class JeJoue(def: DeviceDefinition) : ProtocolHandler(def) {
    private val speeds = IntArray(2)
    override fun vibrate(index: Int, speed: Int): List<Write> {
        speeds.setSafe(index, speed.u8())
        var pattern = 1
        var s = speeds[0]
        var vibe2 = false
        if (s == 0) {
            s = speeds[1]
            if (s != 0) { vibe2 = true; pattern = 3 }
        }
        if (pattern == 1 && s != 0 && !vibe2) pattern = 2
        return listOf(w(Ep.TX, pattern, s))
    }
}

/** JoyHub: up to 4 motors in one packet, plus suction, heating, LED and lube pump. */
class JoyHub(def: DeviceDefinition) : ProtocolHandler(def) {
    private val last = IntArray(4)
    private fun motors(i: Int, v: Int): List<Write> {
        last.setSafe(i, v.u8())
        return listOf(w(Ep.TX, 0xa0, 0x03, last[0], last[1], last[2], last[3], 0xaa))
    }
    private fun onOff(cmd: Int, on: Boolean) =
        listOf(if (on) w(Ep.TX, 0xa0, cmd, 0x01, 0x00, 0x01, 0xff) else w(Ep.TX, 0xa0, cmd, 0, 0, 0, 0))

    override fun vibrate(index: Int, speed: Int) = motors(index, speed)
    override fun rotate(index: Int, speed: Int) = motors(index, kotlin.math.abs(speed))
    override fun oscillate(index: Int, speed: Int) = motors(index, speed)
    override fun constrict(index: Int, level: Int) =
        if (index == 4) listOf(w(Ep.TX, 0xa0, 0x07, if (level == 0) 0 else 1, 0x00, level.u8(), 0xff))
        else listOf(w(Ep.TX, 0xa0, 0x0d, 0x00, 0x00, level.u8(), 0xff))
    override fun temperature(index: Int, level: Int) = onOff(0x04, level != 0)
    override fun led(index: Int, level: Int) = onOff(0x14, level != 0)
    override fun spray(index: Int, level: Int): List<Write> {
        // The pump is stopped again after one second, whatever happens.
        later(1_000, w(Ep.TX, 0xa0, 0x24, 0, 0, 0, 0))
        return onOff(0x24, level != 0)
    }
}

class KiirooPowerShot(def: DeviceDefinition) : ProtocolHandler(def) {
    private val last = IntArray(2)
    override fun vibrate(index: Int, speed: Int): List<Write> {
        last.setSafe(index, speed.u8())
        return listOf(w(Ep.TX, 0x01, 0x00, 0x00, last[0], last[1], 0x00, withResponse = true))
    }
}

class KiirooProWand(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int) =
        listOf(w(Ep.TX, 0x00, 0x00, 0x64, if (speed == 0) 0 else 0xff, speed.u8(), speed.u8()))
}

class KiirooSpot(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int) = listOf(w(Ep.TX, 0x00, 0xff, 0x00, 0x00, 0x00, speed.u8()))
}

class KiirooSpotV2(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int) =
        listOf(w(Ep.TX, 0x01, speed.u8(), speed.u8(), 0x03, 0xe8, 0xff, withResponse = true))
}
