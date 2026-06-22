package com.edge2.remote.remote

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Tunnel internet « quick » via cloudflared : expose le serveur embarqué
 * (`localhost:port`) sur une URL publique `https://…trycloudflare.com` → le
 * partage marche **en 4G** (le tél sort vers Cloudflare), sans compte ni relais
 * à héberger.
 *
 * Le binaire est livré en `jniLibs/<abi>/libcloudflared.so`, extrait sur disque
 * à l'install (useLegacyPackaging) et exécuté depuis `nativeLibraryDir`.
 *
 * Note vie privée : le trafic transite par Cloudflare (contrairement au relais
 * auto-hébergé). Compromis accepté pour le « ça marche tout de suite en 4G ».
 */
class CloudflaredTunnel(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val binary = File(context.applicationInfo.nativeLibraryDir, "libcloudflared.so")
    private val home = context.filesDir

    private val _publicUrl = MutableStateFlow<String?>(null)
    /** URL publique racine (`https://xxx.trycloudflare.com`), ou null. */
    val publicUrl: StateFlow<String?> = _publicUrl.asStateFlow()

    /** Le binaire est-il présent/exécutable pour cet appareil (ABI) ? */
    val available: Boolean get() = binary.exists()

    private var process: Process? = null
    private var job: Job? = null

    fun start(localPort: Int) {
        if (job != null || !available) return
        job = scope.launch(Dispatchers.IO) {
            runCatching {
                val pb = ProcessBuilder(
                    binary.absolutePath,
                    "tunnel", "--no-autoupdate",
                    "--url", "http://127.0.0.1:$localPort",
                ).redirectErrorStream(true)
                pb.environment()["HOME"] = home.absolutePath // cloudflared écrit ses logs/conf ici
                val p = pb.start().also { process = it }

                val rx = Regex("https://[a-z0-9-]+\\.trycloudflare\\.com")
                p.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (!isActive) break
                        if (_publicUrl.value == null) rx.find(line)?.let { _publicUrl.value = it.value }
                    }
                }
            }
            _publicUrl.value = null
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { process?.destroy() }
        process = null
        _publicUrl.value = null
    }
}
