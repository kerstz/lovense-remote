package com.edge2.remote.remote

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.schmizz.sshj.AndroidConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder
import net.schmizz.sshj.connection.channel.forwarded.SocketForwardingConnectListener
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.net.InetSocketAddress
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Security
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec

/**
 * Tunnel internet via SSH vers **localhost.run** : expose le serveur embarqué
 * (`localhost:port`) sur une URL publique `https://…lhr.life` → marche **en 4G**
 * et **hors de notre réseau** (le tél sort vers localhost.run), sans compte ni
 * relais à héberger.
 *
 * Stack JVM (sshj) → la résolution DNS d'Android fonctionne (un binaire Go comme
 * cloudflared échouait faute de `/etc/resolv.conf`).
 *
 * Clé **ed25519** (les serveurs SSH récents rejettent l'ancien `ssh-rsa`/SHA-1).
 * Keep-alive + reconnexion automatique pour limiter les coupures.
 */
class SshTunnel(context: Context, private val scope: CoroutineScope) {

    // Clé persistée → localhost.run rend le MÊME sous-domaine à chaque partage
    // (lié à la clé) → le lien partagé reste valable.
    private val keyFile = File(context.filesDir, "lhr_id_rsa")

    private val _publicUrl = MutableStateFlow<String?>(null)
    val publicUrl: StateFlow<String?> = _publicUrl.asStateFlow()

    val available: Boolean get() = true

    @Volatile private var ssh: SSHClient? = null
    private var job: Job? = null

    fun start(localPort: Int) {
        if (job != null) return
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching { runTunnel(localPort) }
                    .onFailure { android.util.Log.w("SshTunnel", "tunnel échoué: ${it.message}") }
                _publicUrl.value = null
                runCatching { ssh?.disconnect() }
                ssh = null
                if (isActive) delay(3000) // reconnexion après coupure
            }
        }
    }

    private fun runTunnel(localPort: Int) {
        ensureFullBouncyCastle()
        val client = SSHClient(AndroidConfig())
        client.connectTimeout = 15_000
        client.addHostKeyVerifier(PromiscuousVerifier())
        client.connect("localhost.run", 22)
        ssh = client

        // Clé RSA persistée (sshj négocie rsa-sha2-256/512 avec les serveurs récents).
        val kp = persistentKeyPair()
        client.authPublickey("edge2", object : KeyProvider {
            override fun getPrivate() = kp.private
            override fun getPublic() = kp.public
            override fun getType() = KeyType.fromKey(kp.public)
        })

        client.remotePortForwarder.bind(
            RemotePortForwarder.Forward(80),
            SocketForwardingConnectListener(InetSocketAddress("127.0.0.1", localPort)),
        )

        val session = client.startSession()
        session.allocateDefaultPTY()
        val shell = session.startShell()
        val rx = Regex("https://[a-z0-9-]+\\.(lhr\\.life|localhost\\.run)")
        // forEachLine bloque tant que le canal est ouvert → maintient le tunnel.
        shell.inputStream.bufferedReader().forEachLine { line ->
            if (_publicUrl.value == null) rx.find(line)?.let {
                _publicUrl.value = it.value
                android.util.Log.i("SshTunnel", "URL publique: ${it.value}")
            }
        }
    }

    /** Charge la clé RSA persistée, ou en génère une et la sauve (PKCS8). */
    private fun persistentKeyPair(): KeyPair {
        if (keyFile.exists()) {
            runCatching {
                val priv = KeyFactory.getInstance("RSA")
                    .generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes())) as RSAPrivateCrtKey
                val pub = KeyFactory.getInstance("RSA")
                    .generatePublic(RSAPublicKeySpec(priv.modulus, priv.publicExponent))
                return KeyPair(pub, priv)
            }
        }
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()
        runCatching { keyFile.writeBytes(kp.private.encoded) }
        return kp
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { ssh?.disconnect() }
        ssh = null
        _publicUrl.value = null
    }

    companion object {
        @Volatile private var bcReplaced = false

        /**
         * Le provider "BC" d'Android est allégé (pas de X25519) → le key exchange
         * curve25519 de SSH échoue. On le remplace par le BouncyCastle complet
         * (bundlé via sshj) qui fournit X25519.
         */
        @Synchronized
        private fun ensureFullBouncyCastle() {
            if (bcReplaced) return
            Security.removeProvider("BC")
            Security.insertProviderAt(BouncyCastleProvider(), 1)
            bcReplaced = true
        }
    }
}
