package io.github.ttbox.kodifin.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
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

    /**
     * Déchiffre une valeur produite par [encrypt] en distinguant un secret PERDU pour de bon
     * ([Decryption.Lost] : clé absente ou invalidée, données abîmées — à purger) d'un échec du
     * MOMENT ([Decryption.Unavailable] : Keystore occupé ou indisponible — à conserver).
     * Un secret ABSENT ne passe jamais par ici : c'est l'absence de la clé chiffrée dans les
     * extras de l'appareil ([DeviceSecrets.reveal] distingue les cas).
     */
    fun open(encoded: String): Decryption

    /** Texte en clair, ou `null` si [open] n'a pas pu déchiffrer (perdu ou indisponible). */
    fun decrypt(encoded: String): String? = (open(encoded) as? Decryption.Plain)?.value
}

/** Résultat typé de [SecretStore.open]. */
sealed interface Decryption {
    /** Secret déchiffré ; [toString] ne le révèle pas (journaux). */
    class Plain(val value: String) : Decryption {
        override fun equals(other: Any?): Boolean = other is Plain && other.value == value
        override fun hashCode(): Int = value.hashCode()
        override fun toString(): String = "Plain(***)"
    }

    /** Définitivement illisible (clé perdue ou invalidée, chiffré abîmé) : le secret peut être purgé. */
    data object Lost : Decryption

    /** Erreur passagère du Keystore : le secret est peut-être intact, il ne faut surtout pas l'effacer. */
    data object Unavailable : Decryption

    companion object {
        /**
         * Classe une exception levée pendant le déchiffrement : seules celles qui prouvent que le
         * chiffré ne pourra plus jamais être lu donnent [Lost] (clé invalidée ou irrécupérable,
         * étiquette GCM fausse, données tronquées ou Base64 invalide) ; tout le reste
         * ([ProviderException], [java.io.IOException], [java.security.KeyStoreException]…) est
         * considéré comme passager ([Unavailable]), pour ne jamais détruire un secret par erreur.
         */
        fun ofFailure(e: Throwable): Decryption = when (e) {
            is KeyPermanentlyInvalidatedException, is UnrecoverableKeyException,
            is BadPaddingException, is IllegalBlockSizeException, is IllegalArgumentException,
            -> Lost
            else -> Unavailable
        }
    }
}

/**
 * AES-GCM avec une clé générée dans l'Android Keystore : non exportable, elle ne quitte jamais
 * le matériel sécurisé (ou le processus keystore) et disparaît avec l'application.
 * Format : Base64(IV de 12 octets ‖ texte chiffré + étiquette).
 */
class KeystoreSecretStore(private val alias: String = "kodifin_secrets") : SecretStore {

    /** Clé existante, ou `null` si elle a disparu (données effacées, restauration sur un autre téléphone). */
    private fun existingKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.getKey(alias, null) as? SecretKey
    }

    /**
     * Clé de chiffrement, créée si besoin. Seul [encrypt] la crée : au déchiffrement, une clé
     * absente signifie que les anciens chiffrés sont perdus, et en recréer une sous le même
     * alias masquerait cette perte (le déchiffrement échouerait de toute façon).
     */
    private fun key(): SecretKey {
        existingKey()?.let { return it }
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
    override fun open(encoded: String): Decryption = try {
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        val key = existingKey()
        if (payload.size <= IV_SIZE || key == null) {
            // Chiffré tronqué, ou clé disparue (données effacées, restauration ailleurs) : perdu.
            Decryption.Lost
        } else {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, payload, 0, IV_SIZE))
            Decryption.Plain(String(cipher.doFinal(payload, IV_SIZE, payload.size - IV_SIZE), Charsets.UTF_8))
        }
    } catch (e: GeneralSecurityException) {
        Decryption.ofFailure(e)
    } catch (e: IllegalArgumentException) {
        Decryption.ofFailure(e)
    } catch (e: java.io.IOException) {
        // Chargement du Keystore impossible pour le moment.
        Decryption.ofFailure(e)
    } catch (e: ProviderException) {
        // Keystore occupé ou indisponible : passager, le secret est conservé.
        Decryption.ofFailure(e)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
    }
}
