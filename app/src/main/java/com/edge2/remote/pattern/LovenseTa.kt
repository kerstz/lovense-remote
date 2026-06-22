package com.edge2.remote.pattern

/**
 * Décodeur du format de pattern Lovense `.ta` (reverse-engineered, cf.
 * PATTERNS_API.md). Les fichiers `.ta` sont publics sur le CDN Lovense.
 *
 * Format :
 * ```
 * V:1;T:Ambi;F:v;S:100;M:<md5>;#
 * 0;3;8;9;9;7;11;13;...        ← intensités séparées par ';' (0..20)
 * ```
 * Header (avant `#`) : V=version, T=type de toy, F=feature, S=scale, M=md5.
 * Corps (après `#`) : une intensité par tick. Lovense joue **1 point / 100 ms**.
 */
object LovenseTa {

    /** Intervalle de lecture Lovense confirmé (PatternPlayManagerImpl). */
    const val INTERVAL_MS = 100L

    fun parse(content: String, fallbackName: String = "Lovense"): Pattern? {
        val hashIdx = content.indexOf('#')
        val header = if (hashIdx >= 0) content.substring(0, hashIdx) else ""
        val body = if (hashIdx >= 0) content.substring(hashIdx + 1) else content

        val fields = header.split(';').mapNotNull {
            val kv = it.split(':', limit = 2)
            if (kv.size == 2) kv[0].trim() to kv[1].trim() else null
        }.toMap()
        val name = fields["T"]?.takeIf { it.isNotBlank() } ?: fallbackName

        // Les valeurs peuvent être séparées par ';' ou ',' selon la version.
        val strengths = body.trim()
            .split(Regex("[;,\\s]+"))
            .mapNotNull { it.toIntOrNull() }
        if (strengths.isEmpty()) return null

        val steps = strengths.map {
            val v = it.coerceIn(0, LovenseProtocol_MAX)
            PatternStep(m1 = v, m2 = v, durationMs = INTERVAL_MS)
        }
        return Pattern(name = name, steps = steps, loop = true)
    }

    // Intensité max Lovense (0..20). Dupliqué ici pour éviter une dépendance ble→pattern.
    private const val LovenseProtocol_MAX = 20
}
