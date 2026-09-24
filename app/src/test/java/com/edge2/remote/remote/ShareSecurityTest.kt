package com.edge2.remote.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareSecurityTest {

    @Test fun pinIsSixDigits() {
        repeat(200) { assertTrue(Regex("[0-9]{6}").matches(ShareSecurity.pin())) }
    }

    @Test fun tokenHas128BitsAndIsUrlSafe() {
        val seen = HashSet<String>()
        repeat(500) {
            val t = ShareSecurity.token(16)
            assertEquals(22, t.length)
            assertTrue(Regex("[A-Za-z0-9_-]+").matches(t))
            assertTrue(seen.add(t))
        }
    }

    @Test fun constantTimeEquals() {
        assertTrue(ShareSecurity.constantTimeEquals("123456", "123456"))
        assertFalse(ShareSecurity.constantTimeEquals("123456", "123457"))
        assertFalse(ShareSecurity.constantTimeEquals("123456", "12345"))
        assertFalse(ShareSecurity.constantTimeEquals(null, "x"))
    }

    @Test fun originMustMatchHost() {
        assertTrue(ShareSecurity.originAllowed(null, "192.168.1.5:8787")) // client natif
        assertTrue(ShareSecurity.originAllowed("http://192.168.1.5:8787", "192.168.1.5:8787"))
        assertTrue(ShareSecurity.originAllowed("https://abc.lhr.life", "abc.lhr.life"))
        assertTrue(ShareSecurity.originAllowed("https://ABC.lhr.life", "abc.lhr.life:443"))
        assertFalse(ShareSecurity.originAllowed("https://evil.example", "192.168.1.5:8787"))
        assertFalse(ShareSecurity.originAllowed("null", "192.168.1.5:8787"))
        assertFalse(ShareSecurity.originAllowed("file:///x", "192.168.1.5:8787"))
        assertFalse(ShareSecurity.originAllowed("http://192.168.1.5.evil.example", "192.168.1.5:8787"))
        assertFalse(ShareSecurity.originAllowed("http://a.b", null))
    }

    @Test fun rateLimiterCapsBurstThenRefills() {
        var now = 0L
        val rl = RateLimiter(perSecond = 10.0, burst = 5.0) { now }
        repeat(5) { assertTrue(rl.take()) }
        assertFalse(rl.take())
        now += 200_000_000 // 200 ms → 2 jetons
        assertTrue(rl.take()); assertTrue(rl.take()); assertFalse(rl.take())
    }

    @Test fun deepLinkValidation() {
        val id = ShareSecurity.token(16)
        assertNotNull(RemoteController.validateWsUrl("wss://abc.lhr.life/ws/$id"))
        assertNotNull(RemoteController.validateWsUrl("ws://192.168.1.5:8787/ws/$id"))
        assertNull(RemoteController.validateWsUrl("https://abc.lhr.life/ws/$id"))
        assertNull(RemoteController.validateWsUrl("ws://evil/other/$id"))
        assertNull(RemoteController.validateWsUrl("ws://u:p@host/ws/$id"))
        assertNull(RemoteController.validateWsUrl("ws://host/ws/$id?pin=1"))
        assertNull(RemoteController.validateWsUrl("ws://host/ws/short"))
        assertNull(RemoteController.validateWsUrl(null))
        assertEquals("123456", RemoteController.validatePin("123456"))
        assertNull(RemoteController.validatePin("12a456"))
        assertNull(RemoteController.validatePin(""))
    }
}
