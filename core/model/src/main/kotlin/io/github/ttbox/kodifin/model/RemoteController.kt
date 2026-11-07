package io.github.ttbox.kodifin.model

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
    /** Épisode de série : nom de la série, saison et numéro (affichés « Série · S01E10 · Titre »). */
    val seriesTitle: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    /** Musique : artiste et album (affichés sous le titre). */
    val artist: String? = null,
    val album: String? = null,
    /** Application qui diffuse (Chromecast : « YouTube », « Netflix »…). */
    val appName: String? = null,
    /** Pochette ou affiche, clé à passer à [SupportsImages.image] ; `null` sans image. */
    val artworkKey: String? = null,
    /** Image de fond (fanart de la série ou du film), même principe que [artworkKey]. */
    val backdropKey: String? = null,
)

/** « S01E10 » si saison et épisode sont connus, « E10 » si seul l'épisode l'est, sinon `null`. */
fun PlaybackState.episodeCode(): String? = when {
    season != null && episode != null -> "S%02dE%02d".format(season, episode)
    episode != null -> "E%02d".format(episode)
    else -> null
}

/**
 * Levée quand une action n'existe pas pour le protocole de l'appareil. [action] dit laquelle,
 * [kind] le type d'appareil concerné s'il est utile au libellé ; le message ne sert qu'aux journaux.
 */
class UnsupportedActionException(val action: UnsupportedAction, val kind: DeviceKind? = null, cause: Throwable? = null) :
    UnsupportedOperationException("Non pris en charge : $action" + kind?.let { " ($it)" }.orEmpty(), cause)

/**
 * Erreur de communication avec l'appareil. [reason] dit ce qui s'est passé et se traduit
 * dans l'application ; le message (dérivé de la raison) ne sert qu'aux journaux.
 */
open class RemoteException(val reason: RemoteError, cause: Throwable? = null) : Exception(reason.toString(), cause)

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
        throw UnsupportedActionException(UnsupportedAction.PlayUrl, device.kind)

    suspend fun setVolume(percent: Int): Unit =
        throw UnsupportedActionException(UnsupportedAction.Volume, device.kind)

    suspend fun seekTo(positionMs: Long): Unit =
        throw UnsupportedActionException(UnsupportedAction.Seek, device.kind)

    suspend fun sendText(text: String): Unit =
        throw UnsupportedActionException(UnsupportedAction.TextInput, device.kind)

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
        throw UnsupportedActionException(UnsupportedAction.Key(key), device.kind)
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
