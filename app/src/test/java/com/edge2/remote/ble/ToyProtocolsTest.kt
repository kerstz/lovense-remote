package com.edge2.remote.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToyProtocolsTest {

    private fun ascii(b: List<ByteArray>) = b.map { String(it, Charsets.US_ASCII) }

    @Test fun identifiesBrands() {
        assertEquals(Brand.LOVENSE, ToyProtocols.identify("LVS-Edge2-3A9F")!!.guess.brand)
        assertEquals("Edge", ToyProtocols.identify("LVS-Edge2-3A9F")!!.guess.displayName)
        assertEquals(Brand.WEVIBE, ToyProtocols.identify("Sync")!!.guess.brand)
        assertEquals(2, ToyProtocols.identify("Sync")!!.guess.actuators.size)
        assertEquals(1, ToyProtocols.identify("Wish")!!.guess.actuators.size)
        assertEquals(Brand.VORZE, ToyProtocols.identify("CycSA")!!.guess.brand)
        assertEquals(ActuatorKind.ROTATE, ToyProtocols.identify("UFOSA")!!.guess.actuators[0].kind)
        assertEquals(Brand.MAGIC_MOTION, ToyProtocols.identify("Flamingo")!!.guess.brand)
        assertEquals(Brand.WEVIBE, ToyProtocols.identify(null, listOf(WeVibeDriver.SERVICE))!!.guess.brand)
        assertNull(ToyProtocols.identify("JBL Flip 5"))
        assertNull(ToyProtocols.identify("sync")) // exact match only
        assertNull(ToyProtocols.identify(null))
    }

    @Test fun lovenseEncodesPerActuator() {
        val d = LovenseDriver("LVS-Edge2-01")
        assertEquals(listOf("Vibrate1:5;", "Vibrate2:20;"), ascii(d.encode(intArrayOf(5, 20), intArrayOf(-1, -1))))
        assertEquals(listOf("Vibrate2:3;"), ascii(d.encode(intArrayOf(5, 3), intArrayOf(5, 20))))
        assertTrue(d.encode(intArrayOf(5, 3), intArrayOf(5, 3)).isEmpty())
        assertEquals(listOf("DeviceType;", "Battery;"), ascii(d.handshake()))
    }

    @Test fun lovenseSingleAndMixedToys() {
        val lush = LovenseDriver("LVS-Lush3-01")
        assertEquals(listOf("Vibrate:9;"), ascii(lush.encode(intArrayOf(9), intArrayOf(0))))
        val nora = ToyRegistry.byDeviceCode("A")!!
        assertEquals("Rotate:7;", String(LovenseProtocol.actuatorCommand(nora, 1, 7)!!, Charsets.US_ASCII))
        val max = ToyRegistry.byDeviceCode("B")!!
        assertEquals("Air:Level:5;", String(LovenseProtocol.actuatorCommand(max, 1, 99)!!, Charsets.US_ASCII))
    }

    @Test fun lovenseRefinesModelFromDeviceType() {
        val d = LovenseDriver("LVS-Z001")
        val ev = d.onNotification("P:02:0082059AD3BD;".toByteArray())
        assertTrue(ev is DriverEvent.ToyChanged)
        assertEquals("Edge", d.toy.displayName)
        assertEquals(DriverEvent.Battery(85), d.onNotification("85;".toByteArray()))
        assertEquals(DriverEvent.Battery(100), d.onNotification("999;".toByteArray()))
    }

    @Test fun weVibeFrames() {
        assertArrayEquals(byteArrayOf(0x0f, 0, 0, 0, 0, 0, 0, 0), WeVibeDriver.frame(0, 0))
        assertArrayEquals(byteArrayOf(0x0f, 0x03, 0x00, 0xF3.toByte(), 0x00, 0x03, 0x00, 0x00), WeVibeDriver.frame(15, 3))
        assertArrayEquals(WeVibeDriver.frame(15, 15), WeVibeDriver.frame(99, 99))
        val t = ToyProtocols.identify("Wish")!!.guess
        val d = WeVibeDriver(t)
        assertArrayEquals(WeVibeDriver.frame(7, 7), d.encode(intArrayOf(7), intArrayOf(0)).single())
    }

    @Test fun vorzeRotationAndReverse() {
        val m = ToyProtocols.identify("CycSA")!!
        val d = m.newDriver()
        assertArrayEquals(byteArrayOf(0x01, 0x01, (0x80 or 50).toByte()), d.encode(intArrayOf(50), intArrayOf(-1)).single())
        val rev = d.reverse(0, intArrayOf(50))
        assertNotNull(rev)
        assertArrayEquals(byteArrayOf(0x01, 0x01, 50), rev)
        val bach = ToyProtocols.identify("Bach smart")!!.newDriver()
        assertArrayEquals(byteArrayOf(0x06, 0x03, 99), bach.encode(intArrayOf(200), intArrayOf(0)).single())
        assertNull(bach.reverse(0, intArrayOf(10)))
    }

    @Test fun magicMotionFrame() {
        val f = MagicMotionDriver.frame(42)
        assertEquals(12, f.size)
        assertEquals(42, f[9].toInt())
        assertEquals(100, MagicMotionDriver.frame(1000)[9].toInt())
    }
}
