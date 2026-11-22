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
import java.io.InterruptedIOException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.UnknownHostException
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

    /** Hôte seul (sans le port), pour les erreurs « injoignable ». */
    private val host = label.substringBeforeLast(':')

    @Volatile
    private var connection: AdbLink? = null

    /** Posé par [close] : plus aucune (re)connexion ni commande n'est permise ensuite. */
    @Volatile
    private var closed = false

    override suspend fun shell(command: String): String = lock.withLock {
        withContext(Dispatchers.IO) {
            val reused = connection != null
            val result = try {
                run(command)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ShellFailure) {
                dropConnection()
                // Une connexion conservée peut être morte (téléviseur en veille, Wi-Fi coupé) :
                // on retente une seule fois avec une connexion neuve, mais seulement si la
                // commande n'a vraisemblablement pas été exécutée (voir [ShellFailure.retryable]).
                if (!reused || !e.retryable || closed) throw toRemote(e)
                try {
                    run(command)
                } catch (retry: CancellationException) {
                    throw retry
                } catch (retry: ShellFailure) {
                    dropConnection()
                    throw toRemote(retry)
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

    /** Exécute [command] ; les échecs sont rangés par phase (connexion ou commande). */
    private fun run(command: String): ShellResult {
        ensureOpen()
        val link = connection ?: openLink()
        return try {
            link.shell(command)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ShellFailure(e, connecting = false)
        }
    }

    private fun openLink(): AdbLink {
        val link = try {
            connector()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ShellFailure(e, connecting = true)
        }
        connection = link
        // [close] a pu passer pendant la connexion : il n'a pas vu ce lien, on le ferme ici.
        if (closed) {
            dropConnection()
            ensureOpen()
        }
        return link
    }

    private fun ensureOpen() {
        if (closed) throw RemoteException(RemoteError.Interrupted(host))
    }

    private fun dropConnection() {
        val current = connection
        connection = null
        runCatching { current?.close() }
    }

    /**
     * Traduit un échec : délai dépassé ou appareil injoignable ne sont pas des refus ;
     * seul un port fermé ou une connexion refusée par l'appareil (clé non acceptée,
     * débogage désactivé) demande un réglage sur le téléviseur.
     */
    private fun toRemote(failure: ShellFailure): RemoteException {
        val cause = failure.cause
        if (cause is RemoteException) return cause
        if (closed) return RemoteException(RemoteError.Interrupted(host), cause)
        val reason = when {
            cause is InterruptedIOException -> RemoteError.Timeout
            cause is UnknownHostException || cause is NoRouteToHostException || cause is PortUnreachableException ->
                RemoteError.Unreachable(host)
            failure.connecting -> RemoteError.SetupRequired(Setup.ADB_DEBUGGING)
            else -> RemoteError.Interrupted(host)
        }
        return RemoteException(reason, cause)
    }

    /**
     * Ferme sans attendre le verrou : fermer le socket débloque une commande en cours
     * au lieu d'attendre l'expiration de son délai. Définitif : les commandes suivantes
     * échouent au lieu de rouvrir une connexion qui ne serait jamais fermée.
     */
    override suspend fun close() = withContext(Dispatchers.IO) {
        closed = true
        dropConnection()
    }

    /**
     * Échec d'une phase : [connecting] vrai si la connexion n'a pas pu s'ouvrir.
     * [retryable] : une commande sur une connexion conservée ne se rejoue que si l'échec ne
     * vient pas d'un délai de lecture (la commande a pu être exécutée : `am start` ou
     * `input text` ne doivent pas l'être deux fois) ; une coupure franche (socket fermé,
     * réinitialisé) signale une connexion périmée avant que l'appareil n'ait traité la commande.
     */
    private class ShellFailure(override val cause: Exception, val connecting: Boolean) : Exception(cause) {
        val retryable get() = !connecting && cause !is InterruptedIOException && cause !is RemoteException
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
        locked(keyDir) {
            val private = File(keyDir, PRIVATE)
            val public = File(keyDir, PUBLIC)
            val existing = if (private.isFile && public.isFile) {
                runCatching { AdbKeyPair.read(private, public) }.getOrNull()
            } else {
                null
            }
            existing ?: run {
                generateAtomically(keyDir, generate)
                AdbKeyPair.read(private, public)
            }
        }

    /**
     * Exécute [block] sous le verrou du répertoire [keyDir] (réentrant) : toute lecture des
     * fichiers de clé doit s'y faire, sinon elle peut voir une paire en cours de régénération.
     */
    fun <T> locked(keyDir: File, block: () -> T): T =
        synchronized(locks.computeIfAbsent(keyDir.canonicalPath) { Any() }, block)

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
