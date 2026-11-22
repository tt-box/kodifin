package io.github.ttbox.kodifin.data

import java.security.GeneralSecurityException

/**
 * Faux chiffrement pour les tests JVM (pas d'Android Keystore) : réversible, mais le texte
 * chiffré ne contient jamais le texte en clair (inversé et préfixé).
 */
class FakeSecretStore : SecretStore {
    var failEncrypt = false
    val decrypted = mutableListOf<String>()

    override fun encrypt(plain: String): String {
        if (failEncrypt) throw GeneralSecurityException("Keystore indisponible")
        return PREFIX + plain.reversed()
    }

    /** Simule un Keystore momentanément indisponible (occupé, ProviderException…). */
    var unavailable = false

    /** Fil d'exécution de chaque déchiffrement (vérifie qu'il ne s'agit pas du thread principal). */
    val openThreads = mutableListOf<String>()

    override fun open(encoded: String): Decryption {
        decrypted += encoded
        openThreads += Thread.currentThread().name
        return when {
            unavailable -> Decryption.Unavailable
            encoded.startsWith(PREFIX) -> Decryption.Plain(encoded.removePrefix(PREFIX).reversed())
            else -> Decryption.Lost
        }
    }

    private companion object {
        const val PREFIX = "enc:"
    }
}
