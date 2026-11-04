package io.github.jmorille.zapette.firetv

import dadb.AdbKeyPair
import dadb.Dadb
import io.github.jmorille.zapette.model.RemoteException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/** Résultat brut d'une commande shell ADB. */
internal data class ShellResult(val output: String, val exitCode: Int)

/** Connexion ADB ouverte ; abstraite pour tester la logique de reconnexion sans appareil. */
internal interface AdbLink : AutoCloseable {
    fun shell(command: String): ShellResult
}

/**
 * Connexion ADB TCP avec la bibliothèque dadb. La paire de clés RSA est générée une
 * fois dans le répertoire de clés (stockage privé de l'application) ; le téléviseur
 * demande à l'utilisateur d'autoriser cette clé à la première connexion.
 *
 * Les appels réseau sont bloquants : ils s'exécutent sur [Dispatchers.IO] et sont bornés
 * par des délais (sans eux, dadb attend indéfiniment et le verrou n'est jamais rendu).
 */
class DadbShell internal constructor(
    private val label: String,
    private val connector: () -> AdbLink,
) : AdbShell {

    constructor(host: String, port: Int, keyDir: File) : this("$host:$port", { dadbLink(host, port, keyDir) })

    private val lock = Mutex()

    @Volatile
    private var connection: AdbLink? = null

    override suspend fun shell(command: String): String = lock.withLock {
        withContext(Dispatchers.IO) {
            val reused = connection != null
            val result = try {
                run(command)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                dropConnection()
                // Une connexion conservée peut être morte (téléviseur en veille, Wi-Fi coupé) :
                // on retente une seule fois avec une connexion neuve.
                if (!reused) throw unavailable(e)
                try {
                    run(command)
                } catch (retry: CancellationException) {
                    throw retry
                } catch (retry: Exception) {
                    dropConnection()
                    throw unavailable(retry)
                }
            }
            if (result.exitCode != 0) {
                throw RemoteException(
                    "Commande refusée par l'appareil (code ${result.exitCode}) : ${result.output.trim().take(200)}",
                )
            }
            result.output
        }
    }

    private fun run(command: String): ShellResult =
        (connection ?: connector().also { connection = it }).shell(command)

    private fun dropConnection() {
        val current = connection
        connection = null
        runCatching { current?.close() }
    }

    private fun unavailable(cause: Exception) =
        RemoteException("ADB indisponible sur $label (débogage réseau activé et clé acceptée ?)", cause)

    /**
     * Ferme sans attendre le verrou : fermer le socket débloque une commande en cours
     * au lieu d'attendre l'expiration de son délai.
     */
    override suspend fun close() = withContext(Dispatchers.IO) {
        dropConnection()
    }

    companion object {
        internal const val CONNECT_TIMEOUT_MS = 5_000

        /** Large : à la première connexion, l'utilisateur doit accepter la clé sur le téléviseur. */
        internal const val SOCKET_TIMEOUT_MS = 30_000

        private fun dadbLink(host: String, port: Int, keyDir: File): AdbLink {
            val dadb = Dadb.create(host, port, AdbKeys.load(keyDir), CONNECT_TIMEOUT_MS, SOCKET_TIMEOUT_MS)
            return object : AdbLink {
                override fun shell(command: String): ShellResult =
                    dadb.shell(command).let { ShellResult(it.allOutput, it.exitCode) }

                override fun close() = dadb.close()
            }
        }
    }
}

/** Gestion de la paire de clés ADB, partagée par toutes les connexions Fire TV. */
internal object AdbKeys {
    private val locks = ConcurrentHashMap<String, Any>()

    /**
     * Charge la paire de clés, ou la (re)génère si elle est absente ou illisible.
     * Sérialisé par répertoire : deux appareils connectés en même temps ne doivent pas
     * générer deux paires différentes (clé privée et publique dépareillées).
     */
    fun load(keyDir: File, generate: (File, File) -> Unit = AdbKeyPair::generate): AdbKeyPair =
        synchronized(locks.computeIfAbsent(keyDir.canonicalPath) { Any() }) {
            val private = File(keyDir, PRIVATE)
            val public = File(keyDir, PUBLIC)
            if (private.isFile && public.isFile) {
                runCatching { AdbKeyPair.read(private, public) }.getOrNull()?.let { return it }
            }
            generateAtomically(keyDir, generate)
            AdbKeyPair.read(private, public)
        }

    /** Génère dans des fichiers temporaires puis renomme : jamais de paire à moitié écrite. */
    internal fun generateAtomically(keyDir: File, generate: (File, File) -> Unit) {
        if (!keyDir.isDirectory && !keyDir.mkdirs()) throw IOException("Impossible de créer $keyDir")
        val tmpPrivate = File(keyDir, "$PRIVATE.tmp")
        val tmpPublic = File(keyDir, "$PUBLIC.tmp")
        try {
            generate(tmpPrivate, tmpPublic)
            Files.move(tmpPublic.toPath(), File(keyDir, PUBLIC).toPath(), StandardCopyOption.REPLACE_EXISTING)
            Files.move(tmpPrivate.toPath(), File(keyDir, PRIVATE).toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            tmpPrivate.delete()
            tmpPublic.delete()
        }
    }

    private const val PRIVATE = "adbkey"
    private const val PUBLIC = "adbkey.pub"
}
