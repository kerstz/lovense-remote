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
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.net.InetSocketAddress
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Security
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec

/**
 * Internet tunnel over SSH to **localhost.run**: exposes the embedded server
 * (`localhost:port`) on a public `https://…lhr.life` URL → works **over 4G**
 * and **outside our network** (the phone dials out to localhost.run), with no
 * account and no relay to host.
 *
 * JVM stack (sshj) → Android DNS resolution works (a Go binary such as
 * cloudflared failed for lack of `/etc/resolv.conf`).
 *
 * Persisted RSA key (negotiated as rsa-sha2-256/512, not legacy `ssh-rsa`/SHA-1).
 * Relay host key verified with TOFU. Automatic reconnection after a drop.
 *
 * ⚠️ TLS terminates at localhost.run: that third party sees the control traffic.
 * The PIN + per-controller approval remain the real access barrier.
 */
class SshTunnel(context: Context, private val scope: CoroutineScope) {

    // Persisted key → localhost.run may hand back the SAME subdomain on each
    // share (tied to the key) → a shared link can stay valid.
    private val keyFile = File(context.filesDir, "lhr_id_rsa")

    /** Fingerprint of the relay host key, remembered on first connection (TOFU). */
    private val knownHostFile = File(context.filesDir, "lhr_known_host")

    /** true if the relay host key CHANGED (possible interception) → tunnel refused. */
    private val _hostKeyMismatch = MutableStateFlow(false)
    val hostKeyMismatch: StateFlow<Boolean> = _hostKeyMismatch.asStateFlow()

    private val _publicUrl = MutableStateFlow<String?>(null)
    val publicUrl: StateFlow<String?> = _publicUrl.asStateFlow()

    val available: Boolean get() = true

    @Volatile private var ssh: SSHClient? = null
    private var job: Job? = null

    fun start(localPort: Int) {
        // The loop may have ended (host key refused): only skip if it is still running.
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            while (isActive && !_hostKeyMismatch.value) {
                runCatching { runTunnel(localPort) }
                    .onFailure { android.util.Log.w("SshTunnel", "tunnel failed: ${it.message}") }
                _publicUrl.value = null
                runCatching { ssh?.disconnect() }
                ssh = null
                if (isActive) delay(3000) // reconnect after a drop
            }
        }
    }

    private fun runTunnel(localPort: Int) {
        ensureFullBouncyCastle()
        val client = SSHClient(AndroidConfig())
        client.connectTimeout = 15_000
        // Host key verification (TOFU): the former PromiscuousVerifier accepted
        // any key → a network attacker could impersonate the relay and
        // intercept the control traffic.
        client.addHostKeyVerifier(tofuVerifier)
        client.connect(HOST, 22)
        ssh = client

        // Persisted RSA key (sshj negotiates rsa-sha2-256/512 with recent servers).
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
        // forEachLine blocks while the channel is open → keeps the tunnel up.
        shell.inputStream.bufferedReader().forEachLine { line ->
            if (_publicUrl.value == null) rx.find(line)?.let { _publicUrl.value = it.value }
        }
    }

    private val tofuVerifier = object : HostKeyVerifier {
        override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
            val fp = fingerprint(key)
            val known = runCatching { knownHostFile.readText().trim() }.getOrNull()
            if (known.isNullOrEmpty()) {
                runCatching { knownHostFile.writeText(fp) }
                return true
            }
            if (known == fp) return true
            android.util.Log.w("SshTunnel", "relay host key changed — connection refused")
            _hostKeyMismatch.value = true
            return false
        }

        override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
    }

    /** SHA-256 (base64) of the public key's SSH blob — `ssh-keygen -l` format. */
    private fun fingerprint(key: PublicKey): String {
        val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
        val d = MessageDigest.getInstance("SHA-256").digest(blob)
        return "SHA256:" + java.util.Base64.getEncoder().withoutPadding().encodeToString(d)
    }

    /**
     * The user explicitly accepts the relay's new key (after a legitimate
     * change on localhost.run's side): forget the old one.
     */
    fun trustNewHostKey() {
        runCatching { knownHostFile.delete() }
        _hostKeyMismatch.value = false
    }

    /** Loads the persisted RSA key, or generates and saves one (PKCS8). */
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
        runCatching {
            keyFile.writeBytes(kp.private.encoded)
            // App-private storage; drop any residual non-owner permission.
            keyFile.setReadable(false, false); keyFile.setReadable(true, true)
            keyFile.setWritable(false, false); keyFile.setWritable(true, true)
        }
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
        private const val HOST = "localhost.run"
        @Volatile private var bcReplaced = false

        /**
         * Android's "BC" provider is stripped down (no X25519) → SSH's curve25519
         * key exchange fails. Replace it with the full BouncyCastle (bundled
         * for sshj), which provides X25519.
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
