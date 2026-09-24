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
 * Downloads a Lovense `.ta` pattern from the public CDN (no auth) and decodes
 * it into a [Pattern] via [LovenseTa].
 *
 * Security: HTTPS only, no redirects, short timeouts, bounded size (a hostile
 * server can neither exhaust memory nor stall the app).
 *
 * Note: only the `.ta` *file* is public. Browsing the community library
 * (`/wear/pattern/v4/find`) requires a logged-in Lovense account `gtoken`
 * (see PATTERNS_API.md) — not implemented here.
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

    /** Imports from a public `.ta` URL (https). Returns null on failure. */
    suspend fun fromUrl(url: String): Pattern? = runCatching {
        val uri = URI(url)
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) return null
        val text = client.prepareGet(url).execute { resp ->
            if (!resp.status.isSuccess()) return@execute null
            readCapped(resp.bodyAsChannel())
        } ?: return null
        LovenseTa.parse(text, fallbackName = uri.path.substringAfterLast('/').removeSuffix(".ta").take(40))
    }.getOrNull()

    /** Imports from pasted `.ta` content. */
    fun fromText(content: String, name: String = "Imported"): Pattern? =
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
        /** A `.ta` is a few KB; 512 KB ≈ 14 h of pattern at 100 ms/step. */
        const val MAX_BYTES = 512 * 1024
    }
}
