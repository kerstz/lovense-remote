package com.edge2.remote.remote

/**
 * Adresse du relais internet (Phase 4B). Le relais est **auto-hébergé** : tu le
 * déploies toi-même (cf. relay/README.md), donc toi seul vois passer le trafic.
 *
 * Remplace [BASE_URL] par l'URL de TON relais après déploiement. Laisse `""` pour
 * désactiver le tunnel (l'app retombe sur le partage LAN seul).
 */
object RelayConfig {

    // ↓↓↓ À PERSONNALISER : l'URL https de ton relais fly.io / VPS.
    const val BASE_URL: String = "https://edge2-relay.fly.dev"

    val enabled: Boolean get() = BASE_URL.isNotBlank()

    /** `wss://<relais>/host?id=…` — connexion **sortante** du téléphone (NAT-friendly). */
    fun hostWsUrl(id: String): String = wsBase() + "/host?id=" + id

    /** `https://<relais>/s/<id>` — lien à partager (ouvre la page de contrôle). */
    fun shareUrl(id: String): String = BASE_URL.trimEnd('/') + "/s/" + id

    private fun wsBase(): String = BASE_URL.trimEnd('/')
        .replaceFirst("https://", "wss://")
        .replaceFirst("http://", "ws://")
}
