package com.edge2.remote.ble.proto

import com.edge2.remote.ble.db.DeviceDefinition
import com.edge2.remote.ble.db.OutputType
import kotlin.math.abs

// Ports of Buttplug's Svakom protocols (BSD-3-Clause, see THIRD_PARTY_NOTICES.md).

private fun on(v: Int) = if (v == 0) 0 else 1

class SvakomV1(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int) = listOf(w(Ep.TX, 0x55, 0x04, 0x03, 0x00, on(speed), speed.u8()))
}

class SvakomV2(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int) = listOf(
        if (index == 1) w(Ep.TX, 0x55, 0x06, 0x01, 0x00, speed.u8(), speed.u8(), withResponse = true)
        else w(Ep.TX, 0x55, 0x03, 0x03, 0x00, on(speed), speed.u8(), withResponse = true),
    )
}

class SvakomV3(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int) = listOf(
        w(Ep.TX, 0x55, if (index == 0) 0x03 else 0x09, if (index == 0) 0x03 else 0x00, 0x00, on(speed), speed.u8()),
    )
    override fun rotate(index: Int, speed: Int) = listOf(w(Ep.TX, 0x55, 0x08, 0x00, 0x00, speed.u8(), 0xff))
}

/** Shared "dual vibrator → mode byte + max speed" packet of Svakom v4/v5/v6/Barney. */
private fun svakomDual(v1: Int, v2: Int, single: Boolean, onByte: Int, trailing: Boolean): Write {
    val mode = if (single || (v1 > 0 && v2 > 0) || v1 == v2) 0 else if (v1 > 0) 1 else 2
    val onOff = if (v1 == v2 && v1 == 0) 0 else onByte
    val base = listOf(0x55, 0x03, mode, 0x00, onOff, maxOf(v1, v2))
    return w(Ep.TX, if (trailing) base + 0x00 else base)
}

class SvakomV4(def: DeviceDefinition) : ProtocolHandler(def) {
    private val n = def.count(OutputType.VIBRATE)
    private val s = IntArray(3)
    override fun vibrate(index: Int, speed: Int): List<Write> {
        s.setSafe(index, speed.u8())
        return listOf(svakomDual(s[0], s[1], n == 1, 1, trailing = true))
    }
}

class SvakomV5(def: DeviceDefinition) : ProtocolHandler(def) {
    private val s = IntArray(2)
    override fun vibrate(index: Int, speed: Int): List<Write> {
        s.setSafe(index, speed.u8())
        return listOf(svakomDual(s[0], s[1], false, 1, trailing = false))
    }
    override fun oscillate(index: Int, speed: Int) = listOf(w(Ep.TX, 0x55, 0x09, 0x00, 0x00, speed.u8(), 0x00))
}

class SvakomV6(def: DeviceDefinition) : ProtocolHandler(def) {
    private val n = def.count(OutputType.VIBRATE)
    private val s = IntArray(3)
    override fun vibrate(index: Int, speed: Int): List<Write> {
        s.setSafe(index, speed.u8())
        return if (index < 2) listOf(svakomDual(s[0], s[1], n == 1, 1, trailing = true))
        else listOf(w(Ep.TX, 0x55, 0x07, 0x00, 0x00, on(s[2]), s[2], 0x00))
    }
    override fun constrict(index: Int, level: Int) = listOf(w(Ep.TX, 0x55, 0x09, 0x00, 0x00, level.u8(), 0x00, 0x00))
    override fun rotate(index: Int, speed: Int): List<Write> {
        val sp = abs(speed).u8()
        return listOf(w(Ep.TX, 0x55, 0x14, 0x00, 0x00, on(sp), sp, 0x00))
    }
}

class SvakomBarney(def: DeviceDefinition) : ProtocolHandler(def) {
    private val n = def.count(OutputType.VIBRATE)
    private val s = IntArray(3)
    override fun vibrate(index: Int, speed: Int): List<Write> {
        s.setSafe(index, speed.u8())
        return listOf(svakomDual(s[0], s[1], n == 1, 3, trailing = true))
    }
}

class SvakomAlex(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int) = listOf(w(Ep.TX, 18, 1, 3, 0, if (speed == 0) 0xFF else speed.u8(), 0))
}

class SvakomAlexV2(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int) = listOf(w(Ep.TX, 0x55, 3, 3, 0, speed.u8(), (speed + 5).u8()))
}

class SvakomAvaNeo(def: DeviceDefinition) : ProtocolHandler(def) {
    private fun main(speed: Int) = listOf(w(Ep.TX, 0x55, 0x03, 0x00, 0x00, on(speed), speed.u8()))
    override fun vibrate(index: Int, speed: Int) =
        if (index == 0) main(speed) else listOf(w(Ep.TX, 0x55, 0x09, 0x00, 0x00, speed.u8(), 0xff))
    override fun oscillate(index: Int, speed: Int) =
        if (index == 0) main(speed) else listOf(w(Ep.TX, 0x55, 0x08, 0x00, 0x00, speed.u8(), 0xff))
}

class SvakomBarnard(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int) = listOf(w(Ep.TX, 0x55, 0x03, 0x00, 0x00, speed.u8(), on(speed)))
    override fun oscillate(index: Int, speed: Int) =
        listOf(w(Ep.TX, 0x55, 0x08, 0x00, 0x00, speed.u8(), if (speed == 0) 0 else 0xff))
}

class SvakomDice(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int) = listOf(w(Ep.TX, 0x55, 0x04, 0x00, 0x00, 1, speed.u8(), 0xaa))
}

class SvakomDT250A(def: DeviceDefinition) : ProtocolHandler(def) {
    private fun cmd(mode: Int, speed: Int) =
        listOf(w(Ep.TX, 0x55, mode, 0x00, 0x00, speed.u8(), if (speed == 0 || mode == 0x09) 0 else 1))
    override fun vibrate(index: Int, speed: Int) = cmd(if (index == 0) 0x03 else 0x08, speed)
    override fun constrict(index: Int, level: Int) = cmd(0x09, level)
}

/** Fatima: vibration, suction, thrust and a heater. */
class SvakomFatima(def: DeviceDefinition) : ProtocolHandler(def) {
    private fun steady(func: Int, speed: Int) =
        if (speed == 0) w(Ep.TX, 0x55, func, 0, 0, 0, 0) else w(Ep.TX, 0x55, func, 0, 0, 0x01, speed.u8())
    override fun vibrate(index: Int, speed: Int) = listOf(steady(0x03, speed))
    override fun constrict(index: Int, level: Int) = listOf(steady(0x09, level))
    override fun oscillate(index: Int, speed: Int) = listOf(
        if (speed == 0) w(Ep.TX, 0x55, 0x08, 0, 0, 0, 0) else w(Ep.TX, 0x55, 0x08, 0, 0, speed.u8(), 0xff),
    )
    override fun temperature(index: Int, level: Int) = listOf(
        if (level == 0) w(Ep.TX, 0x55, 0x05, 0x00, 0x00, 0x02, 0x00, 0x00)
        else w(Ep.TX, 0x55, 0x05, 0x01, 0x37, 0x02, 0x00, 0x00),
    )
}

class SvakomIker(def: DeviceDefinition) : ProtocolHandler(def) {
    private val s = IntArray(2)
    override fun vibrate(index: Int, speed: Int): List<Write> {
        s.setSafe(index, speed.u8())
        if (s[0] == 0 && s[1] == 0) return listOf(w(Ep.TX, 0x55, 0x07, 0, 0, 0, 0))
        return buildList {
            add(w(Ep.TX, 0x55, 0x03, 0x03, 0x00, 0x01, s[0]))
            if (s[1] > 0) add(w(Ep.TX, 0x55, 0x07, 0x00, 0x00, s[1], 0x00))
        }
    }
}

class SvakomJordan(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int) = listOf(w(Ep.TX, 0x55, 0x03, 0x00, 0x00, on(speed), speed.u8(), 0x00))
    override fun oscillate(index: Int, speed: Int) = listOf(w(Ep.TX, 0x55, 0x08, 0x00, 0x00, on(speed), speed.u8(), 0x00))
}

class SvakomPulse(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int) = listOf(w(Ep.TX, 0x55, 0x03, 0x03, 0x00, on(speed), (speed + 1).u8()))
}

class SvakomSam(def: DeviceDefinition, private val gen2: Boolean) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int) = listOf(
        if (gen2) w(Ep.TX, 18, 1, 3, 0, if (speed == 0) 0 else 0x04, speed.u8())
        else w(Ep.TX, 18, 1, 3, 0, 5, speed.u8()),
    )
    override fun constrict(index: Int, level: Int) = listOf(w(Ep.TX, 18, 6, 1, level.u8()))

    companion object {
        val spec = specInit("svakom-sam") { def, io ->
            io.subscribe(Ep.RX)
            SvakomSam(def, io.hasEndpoint(Ep.TX_MODE) || io.hasEndpoint(Ep.FIRMWARE))
        }
    }
}

class SvakomSam2(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int) =
        listOf(w(Ep.TX, 0x55, 0x03, 0x00, 0x00, if (speed == 0) 0 else 0x05, speed.u8(), 0x00, withResponse = true))
    override fun constrict(index: Int, level: Int) =
        listOf(w(Ep.TX, 0x55, 0x09, 0x00, 0x00, on(level), level.u8(), 0x00, withResponse = true))
}

class SvakomSuitcase(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int): List<Write> {
        if (index != 0) return listOf(w(Ep.TX, 0x55, 0x09, 0x00, 0x00, speed.u8(), 0x00))
        var sp = speed % 10
        var intensity = if (speed == 0) 0 else speed / 10 + 1
        if (sp == 0 && intensity != 0) { sp = 10; intensity -= 1 }
        return listOf(w(Ep.TX, 0x55, 0x03, 0x00, 0x00, intensity.u8(), sp.u8()))
    }
}

class SvakomTaraX(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int) = listOf(
        if (index == 0) w(Ep.TX, 0x55, 0x03, 0x00, 0x00, if (speed == 0) 1 else speed.u8(), if (speed == 0) 1 else 2)
        else w(Ep.TX, 0x55, 0x09, 0x00, 0x00, speed.u8(), 0x00),
    )
}
