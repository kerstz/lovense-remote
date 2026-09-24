package com.edge2.remote.remote

import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Security primitives for sharing (pure, testable on the JVM). */
object ShareSecurity {

    private val rng = SecureRandom()

    /** Cryptographically random token, unpadded base64url ([bytes] bytes of entropy). */
    fun token(bytes: Int = 16): String {
        val b = ByteArray(bytes).also(rng::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    /** Uniform numeric PIN (SecureRandom), leading zeros kept. */
    fun pin(digits: Int = 6): String = buildString { repeat(digits) { append(rng.nextInt(10)) } }

    /** Constant-time comparison (no timing leak). */
    fun constantTimeEquals(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        return MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    }

    /**
     * Guards against cross-site WebSocket hijacking: browsers always send
     * `Origin`; it must name the same host as `Host` (the page we served).
     * No `Origin` = native client (the app) → accepted, the PIN is still required.
     */
    fun originAllowed(origin: String?, host: String?): Boolean {
        if (origin == null) return true
        if (host.isNullOrBlank()) return false
        val o = runCatching { URI(origin) }.getOrNull() ?: return false
        if (o.scheme !in setOf("http", "https")) return false
        val oh = o.host?.lowercase()?.removeSurrounding("[", "]") ?: return false
        val h = hostOnly(host)
        return oh == h
    }

    /** `Host` → bare hostname (no port, handles bracketed IPv6). */
    internal fun hostOnly(host: String): String {
        val v = host.trim().lowercase()
        if (v.startsWith("[")) return v.substringAfter('[').substringBefore(']')
        return v.substringBefore(':')
    }

    /** Base64 SHA-256 hash of an inline script (for the CSP `script-src`). */
    fun cspHash(script: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(script.toByteArray(Charsets.UTF_8))
        return "'sha256-" + Base64.getEncoder().encodeToString(d) + "'"
    }
}

/**
 * Token-bucket rate limiter (per connection). [take] returns false when the
 * message must be dropped.
 */
class RateLimiter(
    private val perSecond: Double,
    private val burst: Double,
    private val now: () -> Long = System::nanoTime,
) {
    private var tokens = burst
    private var last = now()

    @Synchronized
    fun take(): Boolean {
        val t = now()
        tokens = (tokens + (t - last) / 1e9 * perSecond).coerceAtMost(burst)
        last = t
        if (tokens < 1.0) return false
        tokens -= 1.0
        return true
    }
}
