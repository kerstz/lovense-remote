package com.edge2.remote.pattern

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import java.net.URI

/**
 * Télécharge un pattern Lovense `.ta` depuis le CDN public (sans auth) et le
 * décode en [Pattern] via [LovenseTa].
 *
 * Sécurité : HTTPS uniquement, pas de redirection, délais courts, taille bornée
 * (un serveur hostile ne peut ni épuiser la mémoire ni faire traîner l'app).
 *
 * NB : seul le *fichier* `.ta` est public. Parcourir la bibliothèque
 * communautaire (`/wear/pattern/v4/find`) exige un `gtoken` de compte Lovense
 * connecté (cf. PATTERNS_API.md) — non implémenté ici.
 */
class LovenseImporter {

    private val client = HttpClient(CIO) {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 20_000
            socketTimeoutMillis = 10_000
        }
    }

    /** Importe depuis une URL `.ta` publique (https). Renvoie null si échec. */
    suspend fun fromUrl(url: String): Pattern? = runCatching {
        val uri = URI(url)
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) return null
        val text = client.prepareGet(url).execute { resp ->
            if (!resp.status.isSuccess()) return@execute null
            readCapped(resp.bodyAsChannel())
        } ?: return null
        LovenseTa.parse(text, fallbackName = uri.path.substringAfterLast('/').removeSuffix(".ta").take(40))
    }.getOrNull()

    /** Importe depuis un contenu `.ta` collé. */
    fun fromText(content: String, name: String = "Importé"): Pattern? =
        if (content.length > MAX_BYTES) null else LovenseTa.parse(content, fallbackName = name)

    private suspend fun readCapped(ch: io.ktor.utils.io.ByteReadChannel): String? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        while (true) {
            val n = ch.readAvailable(buf, 0, buf.size)
            if (n == -1) break
            out.write(buf, 0, n)
            if (out.size() > MAX_BYTES) return null
        }
        return out.toString(Charsets.UTF_8.name())
    }

    fun close() = client.close()

    private companion object {
        /** Un `.ta` fait quelques Ko ; 512 Ko = ~14 h de pattern à 100 ms/pas. */
        const val MAX_BYTES = 512 * 1024
    }
}
