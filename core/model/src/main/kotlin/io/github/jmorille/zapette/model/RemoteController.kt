package io.github.jmorille.zapette.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Touches logiques de la télécommande, traduites par chaque protocole. */
enum class RemoteKey {
    UP, DOWN, LEFT, RIGHT, SELECT, BACK, HOME, MENU, INFO,
    PLAY_PAUSE, STOP, NEXT, PREVIOUS, REWIND, FAST_FORWARD,
    VOLUME_UP, VOLUME_DOWN, MUTE,
}

enum class Capability { NAVIGATION, PLAYBACK, VOLUME, SEEK, PLAY_URL, TEXT_INPUT, NOW_PLAYING }

data class PlaybackState(
    val title: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val volumePercent: Int? = null,
    val muted: Boolean = false,
)

/** Levée quand une action n'existe pas pour le protocole de l'appareil. */
class UnsupportedActionException(message: String) : UnsupportedOperationException(message)

/** Erreur de communication avec l'appareil (réseau, authentification, réponse invalide). */
open class RemoteException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Contrat commun à tous les protocoles. Les implémentations ne doivent rien envoyer
 * ailleurs qu'à l'appareil piloté (pas de télémétrie).
 */
interface RemoteController {
    val device: Device
    val capabilities: Set<Capability>

    /** Touches réellement prises en charge ; les autres lèvent [UnsupportedActionException]. */
    val supportedKeys: Set<RemoteKey>

    suspend fun connect() {}

    suspend fun send(key: RemoteKey)

    suspend fun playUrl(url: String, title: String? = null): Unit =
        throw UnsupportedActionException("Lecture d'URL non prise en charge par ${device.kind.label}")

    suspend fun setVolume(percent: Int): Unit =
        throw UnsupportedActionException("Volume absolu non pris en charge par ${device.kind.label}")

    suspend fun seekTo(positionMs: Long): Unit =
        throw UnsupportedActionException("Recherche non prise en charge par ${device.kind.label}")

    suspend fun sendText(text: String): Unit =
        throw UnsupportedActionException("Saisie de texte non prise en charge par ${device.kind.label}")

    /** État de lecture ; flux vide si l'appareil ne le publie pas. */
    fun playbackState(): Flow<PlaybackState> = emptyFlow()

    suspend fun disconnect() {}
}

/**
 * Implémenté par les contrôleurs qui échangent des identifiants contre un jeton :
 * l'application enregistre ces extras (sans mot de passe) après connexion.
 */
interface PersistsCredentials {
    fun persistableExtras(): Map<String, String>
}

/** Vérifie qu'une touche est prise en charge, sinon lève [UnsupportedActionException]. */
fun RemoteController.requireKey(key: RemoteKey) {
    if (key !in supportedKeys) {
        throw UnsupportedActionException("Touche $key non prise en charge par ${device.kind.label}")
    }
}

/** Formate une position en `hh:mm:ss`, format utilisé par UPnP et plusieurs API. */
fun formatHms(positionMs: Long): String {
    val totalSeconds = (positionMs.coerceAtLeast(0) / 1000)
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return "%02d:%02d:%02d".format(h, m, s)
}

/**
 * Analyse `h:mm:ss[.fff]` en millisecondes ; renvoie 0 si le format est invalide
 * (composante négative, non numérique ou non finie comprise).
 */
fun parseHms(value: String?): Long {
    if (value.isNullOrBlank()) return 0
    val parts = value.trim().split(":")
    if (parts.size != 3) return 0
    val h = parts[0].toLongOrNull() ?: return 0
    val m = parts[1].toLongOrNull() ?: return 0
    val s = parts[2].toDoubleOrNull() ?: return 0
    if (h < 0 || m < 0 || !s.isFinite() || s < 0) return 0
    return h * 3_600_000 + m * 60_000 + (s * 1000).toLong()
}

/**
 * Implémenté par les contrôleurs capables de décrire le serveur joint (produit, version) :
 * le test de connexion l'affiche, par exemple « Kodi 21.2 (JSON-RPC 13.5.0) ».
 */
interface DescribesServer {
    /** Description courte du serveur, ou `null` si elle n'est pas disponible. À appeler après `connect()`. */
    suspend fun serverDescription(): String?
}
