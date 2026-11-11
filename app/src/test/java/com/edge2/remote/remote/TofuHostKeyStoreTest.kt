package com.edge2.remote.remote

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TofuHostKeyStoreTest {

    private fun tempStore(): Pair<TofuHostKeyStore, File> {
        val f = File.createTempFile("lhr_known_host", null).also { it.delete(); it.deleteOnExit() }
        return TofuHostKeyStore(f) to f
    }

    @Test fun firstKeyIsRememberedThenEnforced() {
        val (store, file) = tempStore()
        assertNull(store.known())
        assertTrue(store.verify("SHA256:aaa")) // trust on first use …
        assertEquals("SHA256:aaa", file.readText())
        assertTrue(store.verify("SHA256:aaa"))
        assertFalse(store.mismatch.value)
        // … then any other key (interception or rotation) is refused and flagged.
        assertFalse(store.verify("SHA256:bbb"))
        assertTrue(store.mismatch.value)
        assertEquals("SHA256:aaa", store.known()) // the attacker's key is NOT remembered
    }

    @Test fun userCanExplicitlyTrustANewKey() {
        val (store, _) = tempStore()
        store.verify("SHA256:aaa")
        assertFalse(store.verify("SHA256:bbb"))
        store.trustNew()
        assertFalse(store.mismatch.value)
        assertNull(store.known())
        assertTrue(store.verify("SHA256:bbb"))
        assertEquals("SHA256:bbb", store.known())
        assertFalse(store.verify("SHA256:aaa")) // the old key is gone for good
    }

    @Test fun readErrorIsNotAFirstUseAndWriteErrorIsReported() {
        // Unreadable store (here: a directory where the file should be) → refusal, the pin is not overwritten.
        val dir = Files.createTempDirectory("tofu").toFile()
        val unreadable = File(dir, "lhr_known_host").also { it.mkdir() }
        val store = TofuHostKeyStore(unreadable)
        assertThrows(IOException::class.java) { store.verify("SHA256:evil") }
        assertTrue(unreadable.isDirectory)
        // Store that cannot be written (missing parent directory) → the failure surfaces.
        val unwritable = TofuHostKeyStore(File(dir, "missing/lhr_known_host"))
        assertThrows(IOException::class.java) { unwritable.verify("SHA256:aaa") }
        assertNull(unwritable.known())
        dir.deleteRecursively()
    }

    @Test fun emptyStoreIsRefusedNotTreatedAsFirstUse() {
        val (store, file) = tempStore()
        file.writeText("")
        assertFalse(store.verify("SHA256:aaa"))
        assertTrue(store.mismatch.value) // the user can still explicitly re-accept
        store.trustNew()
        assertTrue(store.verify("SHA256:aaa"))
    }

    @Test fun fingerprintMatchesOpenSshFormat() {
        val pub = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair().public as RSAPublicKey
        // RFC 4253 §6.6 wire blob: string "ssh-rsa", mpint e, mpint n (BigInteger.toByteArray() is the mpint encoding).
        fun str(b: ByteArray) = ByteBuffer.allocate(4 + b.size).putInt(b.size).put(b).array()
        val blob = str("ssh-rsa".toByteArray()) + str(pub.publicExponent.toByteArray()) + str(pub.modulus.toByteArray())
        val expected = "SHA256:" + Base64.getEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))
        assertEquals(expected, TofuHostKeyStore.fingerprint(pub))
        assertTrue(Regex("SHA256:[A-Za-z0-9+/]{43}").matches(expected))
    }
}
