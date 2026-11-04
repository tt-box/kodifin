package io.github.jmorille.zapette.firetv

import dadb.AdbKeyPair
import dadb.Dadb
import io.github.jmorille.zapette.model.RemoteException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Connexion ADB TCP avec la bibliothèque dadb. La paire de clés RSA est générée une
 * fois dans [keyDir] (stockage privé de l'application) ; le téléviseur demande à
 * l'utilisateur d'autoriser cette clé à la première connexion.
 */
class DadbShell(
    private val host: String,
    private val port: Int,
    private val keyDir: File,
) : AdbShell {
    private val lock = Mutex()
    private var connection: Dadb? = null

    private fun keyPair(): AdbKeyPair {
        val private = File(keyDir, "adbkey")
        val public = File(keyDir, "adbkey.pub")
        if (!private.exists() || !public.exists()) {
            keyDir.mkdirs()
            AdbKeyPair.generate(private, public)
        }
        return AdbKeyPair.read(private, public)
    }

    private fun open(): Dadb = connection ?: Dadb.create(host, port, keyPair()).also { connection = it }

    override suspend fun shell(command: String): String = lock.withLock {
        withContext(Dispatchers.IO) {
            try {
                open().shell(command).allOutput
            } catch (e: Exception) {
                runCatching { connection?.close() }
                connection = null
                throw RemoteException("ADB indisponible sur $host:$port (débogage réseau activé et clé acceptée ?)", e)
            }
        }
    }

    override suspend fun close() = lock.withLock {
        withContext(Dispatchers.IO) {
            runCatching { connection?.close() }
            connection = null
        }
    }
}
