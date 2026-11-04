package io.github.jmorille.zapette.mediaserver

import io.github.jmorille.zapette.model.Capability
import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import io.github.jmorille.zapette.model.PersistsCredentials
import io.github.jmorille.zapette.model.PlaybackState
import io.github.jmorille.zapette.model.RemoteController
import io.github.jmorille.zapette.model.RemoteException
import io.github.jmorille.zapette.model.RemoteKey
import io.github.jmorille.zapette.model.requireKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient

/**
 * Pilote une session cliente (TV, navigateur, application) d'un serveur Jellyfin/Emby.
 * Le [Device] représente le serveur ; la session cible est `extras[sessionId]`
 * ou, à défaut, la première session contrôlable.
 */
class MediaServerController(
    override val device: Device,
    private val client: MediaServerClient,
    private val pollIntervalMs: Long = 2_000,
) : RemoteController, PersistsCredentials {

    override fun persistableExtras(): Map<String, String> = buildMap {
        putAll(device.extras - EXTRA_PASSWORD)
        client.accessToken?.let { put(EXTRA_TOKEN, it) }
        userId?.let { put(EXTRA_USER_ID, it) }
    }

    override val capabilities = setOf(
        Capability.NAVIGATION, Capability.PLAYBACK, Capability.VOLUME, Capability.SEEK,
        Capability.TEXT_INPUT, Capability.NOW_PLAYING,
    )
    override val supportedKeys = RemoteKey.entries.toSet()

    private var userId: String? = device.extras[EXTRA_USER_ID]
    private var sessionId: String? = device.extras[EXTRA_SESSION_ID]

    override suspend fun connect() {
        if (client.accessToken == null) {
            val user = device.extras[EXTRA_USERNAME] ?: throw RemoteException("Identifiants manquants")
            userId = client.authenticate(user, device.extras[EXTRA_PASSWORD].orEmpty()).user.id
        }
        session()
    }

    private suspend fun session(): String {
        sessionId?.let { return it }
        val target = client.sessions(userId).firstOrNull { it.supportsRemoteControl }
            ?: throw RemoteException("Aucun client contrôlable sur ${device.name}")
        sessionId = target.id
        return target.id
    }

    override suspend fun send(key: RemoteKey) {
        requireKey(key)
        val id = session()
        when (key) {
            RemoteKey.UP -> client.generalCommand(id, "MoveUp")
            RemoteKey.DOWN -> client.generalCommand(id, "MoveDown")
            RemoteKey.LEFT -> client.generalCommand(id, "MoveLeft")
            RemoteKey.RIGHT -> client.generalCommand(id, "MoveRight")
            RemoteKey.SELECT -> client.generalCommand(id, "Select")
            RemoteKey.BACK -> client.generalCommand(id, "Back")
            RemoteKey.HOME -> client.generalCommand(id, "GoHome")
            RemoteKey.MENU -> client.generalCommand(id, "ToggleContextMenu")
            RemoteKey.INFO -> client.generalCommand(id, "ToggleOsdMenu")
            RemoteKey.VOLUME_UP -> client.generalCommand(id, "VolumeUp")
            RemoteKey.VOLUME_DOWN -> client.generalCommand(id, "VolumeDown")
            RemoteKey.MUTE -> client.generalCommand(id, "ToggleMute")
            RemoteKey.PLAY_PAUSE -> client.playstate(id, "PlayPause")
            RemoteKey.STOP -> client.playstate(id, "Stop")
            RemoteKey.NEXT -> client.playstate(id, "NextTrack")
            RemoteKey.PREVIOUS -> client.playstate(id, "PreviousTrack")
            RemoteKey.REWIND -> client.playstate(id, "Rewind")
            RemoteKey.FAST_FORWARD -> client.playstate(id, "FastForward")
        }
    }

    override suspend fun setVolume(percent: Int) {
        client.generalCommand(session(), "SetVolume", mapOf("Volume" to percent.coerceIn(0, 100).toString()))
    }

    override suspend fun seekTo(positionMs: Long) {
        client.playstate(session(), "Seek", seekPositionTicks = positionMs * TICKS_PER_MS)
    }

    override suspend fun sendText(text: String) {
        client.generalCommand(session(), "SendString", mapOf("String" to text))
    }

    override fun playbackState(): Flow<PlaybackState> = flow {
        while (true) {
            val id = session()
            val s = client.sessions(userId).firstOrNull { it.id == id }
            emit(
                PlaybackState(
                    title = s?.nowPlaying?.name,
                    isPlaying = s?.nowPlaying != null && s.playState?.isPaused == false,
                    positionMs = (s?.playState?.positionTicks ?: 0) / TICKS_PER_MS,
                    durationMs = (s?.nowPlaying?.runTimeTicks ?: 0) / TICKS_PER_MS,
                    volumePercent = s?.playState?.volumeLevel,
                    muted = s?.playState?.isMuted ?: false,
                ),
            )
            delay(pollIntervalMs)
        }
    }

    companion object {
        const val EXTRA_USERNAME = "username"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_USER_ID = "userId"
        const val EXTRA_SESSION_ID = "sessionId"
        const val EXTRA_TOKEN = "token"

        fun create(device: Device, http: OkHttpClient, deviceId: String): MediaServerController {
            val flavor = if (device.kind == DeviceKind.EMBY) MediaServerFlavor.EMBY else MediaServerFlavor.JELLYFIN
            val client = MediaServerClient(http, device.baseUrl, flavor, deviceId, device.extras[EXTRA_TOKEN])
            return MediaServerController(device, client)
        }
    }
}
