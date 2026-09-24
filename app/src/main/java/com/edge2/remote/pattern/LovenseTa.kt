package com.edge2.remote.pattern

/**
 * Decoder for the Lovense `.ta` pattern format (reverse-engineered, see
 * PATTERNS_API.md). `.ta` files are public on the Lovense CDN.
 *
 * Format:
 * ```
 * V:1;T:Ambi;F:v;S:100;M:<md5>;#
 * 0;3;8;9;9;7;11;13;...        ← intensities separated by ';' (0..20)
 * ```
 * Header (before `#`): V=version, T=toy type, F=feature, S=scale, M=md5.
 * Body (after `#`): one intensity per tick. Lovense plays **1 point / 100 ms**.
 */
object LovenseTa {

    /** Lovense playback interval, confirmed (PatternPlayManagerImpl). */
    const val INTERVAL_MS = 100L

    fun parse(content: String, fallbackName: String = "Lovense"): Pattern? {
        val hashIdx = content.indexOf('#')
        val header = if (hashIdx >= 0) content.substring(0, hashIdx) else ""
        val body = if (hashIdx >= 0) content.substring(hashIdx + 1) else content

        val fields = header.split(';').mapNotNull {
            val kv = it.split(':', limit = 2)
            if (kv.size == 2) kv[0].trim() to kv[1].trim() else null
        }.toMap()
        // Name bounded and sanitized (shown in the UI).
        val name = (fields["T"]?.takeIf { it.isNotBlank() } ?: fallbackName)
            .filter { !it.isISOControl() }.take(MAX_NAME).ifBlank { "Lovense" }

        // Values may be separated by ';' or ',' depending on the version.
        val strengths = body.trim()
            .split(Regex("[;,\\s]+"))
            .asSequence()
            .mapNotNull { it.toIntOrNull() }
            .take(MAX_STEPS)
            .toList()
        if (strengths.isEmpty()) return null

        val steps = strengths.map {
            val v = it.coerceIn(0, LovenseProtocol_MAX)
            PatternStep(m1 = v, m2 = v, durationMs = INTERVAL_MS)
        }
        return Pattern(name = name, steps = steps, loop = true)
    }

    // Lovense max intensity (0..20). Duplicated here to avoid a ble→pattern dependency.
    private const val LovenseProtocol_MAX = 20

    /** Anti-abuse bounds (hostile file): ~2 h of pattern at 100 ms/step. */
    private const val MAX_STEPS = 72_000
    private const val MAX_NAME = 40
}
