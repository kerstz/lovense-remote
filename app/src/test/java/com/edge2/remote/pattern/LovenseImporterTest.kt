package com.edge2.remote.pattern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LovenseImporterTest {

    private val importer = LovenseImporter()

    @Test fun fromTextParsesValidTa() {
        val p = importer.fromText("V:1;T:Ambi;F:v;S:100;M:abc;#0;3;25;-4;9")
        assertNotNull(p)
        assertEquals("Ambi", p!!.name)
        // 25 is clamped to 20, -4 to 0.
        assertEquals(listOf(0, 3, 20, 0, 9), p.steps.map { it.m1 })
        assertEquals(p.steps.map { it.m1 }, p.steps.map { it.m2 })
    }

    @Test fun fromTextUsesFallbackNameWhenHeaderHasNoName() {
        val p = importer.fromText("#1;2;3", name = "Fallback")
        assertEquals("Fallback", p!!.name)
    }

    @Test fun fromTextReturnsNullForEmptyBody() {
        assertNull(importer.fromText("V:1;T:Ambi;#"))
    }

    @Test fun fromTextAcceptsContentAtSizeLimit() {
        // 512 KiB exactly (ASCII, so chars == bytes).
        val limit = 512 * 1024
        val content = "#" + "0;".repeat((limit - 2) / 2) + "0"
        assertEquals(limit, content.length)
        assertNotNull(importer.fromText(content))
    }

    @Test fun fromTextReturnsNullAboveSizeLimit() {
        val limit = 512 * 1024
        val content = "#" + "0;".repeat((limit - 1) / 2) + "0;"
        assertEquals(limit + 1, content.length)
        assertNull(importer.fromText(content))
    }
}
