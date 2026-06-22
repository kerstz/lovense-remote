package com.edge2.remote.pattern

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText

/**
 * Télécharge un pattern Lovense `.ta` depuis le CDN public (sans auth) et le
 * décode en [Pattern] via [LovenseTa].
 *
 * NB : seul le *fichier* `.ta` est public. Parcourir la bibliothèque
 * communautaire (`/wear/pattern/v4/find`) exige un `gtoken` de compte Lovense
 * connecté (cf. PATTERNS_API.md) — non implémenté ici.
 */
class LovenseImporter {

    private val client = HttpClient(CIO)

    /** Importe depuis une URL `.ta` publique. Renvoie null si échec. */
    suspend fun fromUrl(url: String): Pattern? = runCatching {
        val text = client.get(url).bodyAsText()
        LovenseTa.parse(text, fallbackName = url.substringAfterLast('/').removeSuffix(".ta"))
    }.getOrNull()

    /** Importe depuis un contenu `.ta` collé. */
    fun fromText(content: String, name: String = "Importé"): Pattern? =
        LovenseTa.parse(content, fallbackName = name)

    fun close() = client.close()
}
