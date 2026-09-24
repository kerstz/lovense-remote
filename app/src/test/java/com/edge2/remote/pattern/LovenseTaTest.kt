package com.edge2.remote.pattern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LovenseTaTest {

    @Test fun parsesHeaderAndBody() {
        val p = LovenseTa.parse("V:1;T:Ambi;F:v;S:100;M:abc;#0;3;25;-4;9")!!
        assertEquals("Ambi", p.name)
        assertEquals(listOf(0, 3, 20, 0, 9), p.steps.map { it.m1 })
    }

    @Test fun boundsHostileInput() {
        val huge = "T:" + "x".repeat(500) + "\u0007;#" + "1;".repeat(100_000)
        val p = LovenseTa.parse(huge)!!
        assertEquals(40, p.name.length)
        assertEquals(72_000, p.steps.size)
        assertNull(LovenseTa.parse("#;;;"))
    }
}
