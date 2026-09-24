package com.edge2.remote.remote

import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Primitives de sécurité du partage (pures, testables sur JVM). */
object ShareSecurity {

    private val rng = SecureRandom()

    /** Jeton aléatoire cryptographique, base64url sans padding ([bytes] octets d'entropie). */
    fun token(bytes: Int = 16): String {
        val b = ByteArray(bytes).also(rng::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    /** Code PIN numérique uniforme (SecureRandom), zéros de tête conservés. */
    fun pin(digits: Int = 6): String = buildString { repeat(digits) { append(rng.nextInt(10)) } }

    /** Comparaison en temps constant (pas de fuite par chronométrage). */
    fun constantTimeEquals(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        return MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    }

    /**
     * Anti « cross-site WebSocket hijacking » : un navigateur envoie toujours
     * `Origin` ; on exige qu'il désigne le même hôte que `Host` (la page servie
     * par nous). Pas d'`Origin` = client natif (l'app) → accepté, le PIN reste exigé.
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

    /** `Host` → nom d'hôte seul (sans port, gère IPv6 entre crochets). */
    internal fun hostOnly(host: String): String {
        val v = host.trim().lowercase()
        if (v.startsWith("[")) return v.substringAfter('[').substringBefore(']')
        return v.substringBefore(':')
    }

    /** Empreinte SHA-256 base64 d'un script inline (pour la CSP `script-src`). */
    fun cspHash(script: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(script.toByteArray(Charsets.UTF_8))
        return "'sha256-" + Base64.getEncoder().encodeToString(d) + "'"
    }
}

/**
 * Limiteur de débit « seau à jetons » (par connexion). [take] renvoie false si
 * le message doit être ignoré.
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
