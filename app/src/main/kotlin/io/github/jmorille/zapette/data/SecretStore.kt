package io.github.jmorille.zapette.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Chiffrement des secrets qui doivent être conservés (mot de passe Kodi, exigé à chaque requête
 * en authentification Basic). Isolé derrière une interface pour garder la logique testable en JVM.
 */
interface SecretStore {
    /** Chiffre [plain] ; lève une exception plutôt que de renvoyer le texte en clair. */
    fun encrypt(plain: String): String

    /** Déchiffre une valeur produite par [encrypt], ou `null` si elle est illisible (clé perdue, données abîmées). */
    fun decrypt(encoded: String): String?
}

/**
 * AES-GCM avec une clé générée dans l'Android Keystore : non exportable, elle ne quitte jamais
 * le matériel sécurisé (ou le processus keystore) et disparaît avec l'application.
 * Format : Base64(IV de 12 octets ‖ texte chiffré + étiquette).
 */
class KeystoreSecretStore(private val alias: String = "zapette_secrets") : SecretStore {

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    @Synchronized
    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val payload = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    @Synchronized
    override fun decrypt(encoded: String): String? = try {
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        if (payload.size <= IV_SIZE) {
            null
        } else {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, payload, 0, IV_SIZE))
            String(cipher.doFinal(payload, IV_SIZE, payload.size - IV_SIZE), Charsets.UTF_8)
        }
    } catch (_: GeneralSecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: ProviderException) {
        // Keystore indisponible ou clé invalidée : le secret est perdu, l'utilisateur le ressaisira.
        null
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
    }
}
