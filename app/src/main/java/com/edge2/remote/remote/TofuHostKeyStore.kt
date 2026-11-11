package com.edge2.remote.remote

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.schmizz.sshj.common.Buffer

/**
 * Trust-on-first-use store for the relay's SSH host key (pure, JVM-testable).
 *
 * The first key seen is remembered in [file] (app-private storage); any
 * different key afterwards is refused and flagged in [mismatch] until the user
 * explicitly accepts the new key with [trustNew]. This replaces sshj's
 * `PromiscuousVerifier`, which accepted any key and let a network attacker
 * impersonate the relay.
 */
class TofuHostKeyStore(private val file: File) {

    private val _mismatch = MutableStateFlow(false)
    /** true if the relay host key CHANGED (possible interception) → tunnel refused. */
    val mismatch: StateFlow<Boolean> = _mismatch.asStateFlow()

    /**
     * The remembered fingerprint, or null ONLY if nothing was ever remembered
     * (file absent). Any other read error throws: a transient I/O failure must
     * never be mistaken for a first use, which would overwrite the pinned key.
     */
    @Throws(IOException::class)
    fun known(): String? {
        if (!file.exists()) return null
        return file.readText().trim()
    }

    /**
     * true if [fingerprint] is the remembered key, or the first one ever seen
     * (then remembered). Throws [IOException] if the store cannot be read, or if
     * a first key cannot be persisted — the caller must refuse the connection.
     */
    @Throws(IOException::class)
    fun verify(fingerprint: String): Boolean {
        val known = known()
        if (known == null) {
            persist(fingerprint)
            return true
        }
        if (known == fingerprint) return true
        // Different key — or an unreadable, empty store: refused, and the user may re-accept.
        _mismatch.value = true
        return false
    }

    /** Atomic write (temp file + rename): a crash can't leave a half-written pin behind. */
    private fun persist(fingerprint: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(fingerprint)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("cannot persist the relay host key to $file")
        }
    }

    /** The user explicitly accepts a new key (legitimate change on the relay's side): forget the old one. */
    fun trustNew() {
        runCatching { file.delete() }
        _mismatch.value = false
    }

    companion object {
        /** `SHA256:<base64>` of the public key's SSH wire blob — `ssh-keygen -l` format. */
        fun fingerprint(key: PublicKey): String {
            val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
            val d = MessageDigest.getInstance("SHA-256").digest(blob)
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(d)
        }
    }
}
