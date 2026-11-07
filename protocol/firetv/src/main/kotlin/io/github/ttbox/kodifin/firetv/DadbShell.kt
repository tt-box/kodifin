package io.github.ttbox.kodifin.firetv

import dadb.AdbKeyPair
import dadb.Dadb
import io.github.ttbox.kodifin.model.RemoteError
import io.github.ttbox.kodifin.model.RemoteException
import io.github.ttbox.kodifin.model.Setup
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

    /**
     * [timeoutMs] : délai de connexion de l'appareil (réglage « Délai » du formulaire).
     * [tls] : appareil appairé par « Débogage sans fil » (port `adb-tls-connect`), sinon ADB classique.
     */
    constructor(host: String, port: Int, keyDir: File, timeoutMs: Int = DEFAULT_TIMEOUT_MS, tls: Boolean = false) : this(
        "$host:$port",
        if (tls) {
            { tlsLink(host, port, keyDir, timeoutMs) }
        } else {
            { dadbLink(host, port, keyDir, timeoutMs) }
        },
    )

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
                // Sortie de la commande : texte de l'appareil, affiché tel quel.
                throw RemoteException(
                    RemoteError.DeviceRefused(detail = result.output.trim().take(200), code = result.exitCode.toString()),
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

    /** Connexion impossible : débogage réseau désactivé ou clé non acceptée sur [label]. */
    private fun unavailable(cause: Exception) =
        RemoteException(RemoteError.SetupRequired(Setup.ADB_DEBUGGING), cause)

    /**
     * Ferme sans attendre le verrou : fermer le socket débloque une commande en cours
     * au lieu d'attendre l'expiration de son délai.
     */
    override suspend fun close() = withContext(Dispatchers.IO) {
        dropConnection()
    }

    companion object {
        /** Extra d'appareil : `"true"` si appairé par « Débogage sans fil » (connexion TLS). */
        const val EXTRA_TLS = "adbTls"

        /** Extra d'appareil : GUID annoncé par le téléviseur à l'appairage (information). */
        const val EXTRA_GUID = "adbGuid"

        /** Délai par défaut si l'appareil n'en précise pas. */
        const val DEFAULT_TIMEOUT_MS = 5_000

        /**
         * Minimum du délai de lecture en ADB classique : à la première connexion, l'utilisateur
         * doit accepter la clé sur le téléviseur avant que celui-ci ne réponde.
         */
        internal const val KEY_APPROVAL_TIMEOUT_MS = 30_000

        /** Minimum du délai de lecture en TLS : une commande (`am start`…) peut prendre quelques secondes. */
        internal const val MIN_READ_TIMEOUT_MS = 10_000

        /** Délais (connexion, lecture) dérivés du délai de l'appareil. */
        internal fun timeouts(timeoutMs: Int, tls: Boolean): Pair<Int, Int> {
            val connect = timeoutMs.coerceAtLeast(1)
            // Appareil appairé : la clé est déjà autorisée, aucune confirmation à attendre.
            val read = maxOf(connect, if (tls) MIN_READ_TIMEOUT_MS else KEY_APPROVAL_TIMEOUT_MS)
            return connect to read
        }

        private fun dadbLink(host: String, port: Int, keyDir: File, timeoutMs: Int): AdbLink {
            val (connect, read) = timeouts(timeoutMs, tls = false)
            return DadbLink(Dadb.create(host, port, AdbKeys.load(keyDir), connect, read))
        }

        private fun tlsLink(host: String, port: Int, keyDir: File, timeoutMs: Int): AdbLink {
            val (connect, read) = timeouts(timeoutMs, tls = true)
            return DadbLink(AdbTlsConnection.open(host, port, AdbIdentity.load(keyDir), connect, read))
        }
    }

    private class DadbLink(private val dadb: Dadb) : AdbLink {
        override fun shell(command: String): ShellResult =
            dadb.shell(command).let { ShellResult(it.allOutput, it.exitCode) }

        override fun close() = dadb.close()
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
