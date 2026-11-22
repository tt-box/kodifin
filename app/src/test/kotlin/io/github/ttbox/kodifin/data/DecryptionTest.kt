package io.github.ttbox.kodifin.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.IOException
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.IllegalBlockSizeException

class DecryptionTest {

    @Test
    fun `only failures proving the secret is gone for good are classified as lost`() {
        assertEquals(Decryption.Lost, Decryption.ofFailure(AEADBadTagException("étiquette")))
        assertEquals(Decryption.Lost, Decryption.ofFailure(UnrecoverableKeyException("clé")))
        assertEquals(Decryption.Lost, Decryption.ofFailure(IllegalBlockSizeException("tronqué")))
        assertEquals(Decryption.Lost, Decryption.ofFailure(IllegalArgumentException("base64")))
    }

    @Test
    fun `transient keystore failures keep the secret`() {
        assertEquals(Decryption.Unavailable, Decryption.ofFailure(ProviderException("Keystore occupé")))
        assertEquals(Decryption.Unavailable, Decryption.ofFailure(IOException("chargement")))
        assertEquals(Decryption.Unavailable, Decryption.ofFailure(KeyStoreException("binder")))
    }

    @Test
    fun `plain result hides its value and decrypt maps failures to null`() {
        assertEquals("Plain(***)", Decryption.Plain("secret").toString())
        assertEquals(Decryption.Plain("a"), Decryption.Plain("a"))
        assertEquals(Decryption.Plain("a").hashCode(), Decryption.Plain("a").hashCode())
        assertFalse(Decryption.Plain("a") == Decryption.Plain("b"))
        val secrets = FakeSecretStore()
        assertEquals("pw", secrets.decrypt(secrets.encrypt("pw")))
        assertEquals(null, secrets.decrypt("illisible"))
        secrets.unavailable = true
        assertEquals(null, secrets.decrypt(secrets.encrypt("pw")))
    }
}
