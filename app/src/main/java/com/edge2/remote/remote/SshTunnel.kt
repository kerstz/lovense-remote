package com.edge2.remote.remote

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
import java.net.InetSocketAddress
import java.security.KeyPairGenerator

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
class SshTunnel(private val scope: CoroutineScope) {

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
        val client = SSHClient(AndroidConfig())
        client.connectTimeout = 15_000
        client.addHostKeyVerifier(PromiscuousVerifier())
        client.connect("localhost.run", 22)
        ssh = client

        // Clé RSA éphémère (sshj négocie rsa-sha2-256/512 avec les serveurs récents).
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()
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

    fun stop() {
        job?.cancel()
        job = null
        runCatching { ssh?.disconnect() }
        ssh = null
        _publicUrl.value = null
    }
}
