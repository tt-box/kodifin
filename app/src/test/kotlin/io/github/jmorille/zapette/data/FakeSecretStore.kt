package io.github.jmorille.zapette.data

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

    override fun decrypt(encoded: String): String? {
        decrypted += encoded
        return if (encoded.startsWith(PREFIX)) encoded.removePrefix(PREFIX).reversed() else null
    }

    private companion object {
        const val PREFIX = "enc:"
    }
}
