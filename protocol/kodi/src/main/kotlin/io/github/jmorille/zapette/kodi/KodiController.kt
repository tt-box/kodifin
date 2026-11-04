package io.github.jmorille.zapette.kodi

import io.github.jmorille.zapette.model.Capability
import io.github.jmorille.zapette.model.Device
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
 * Télécommande Kodi. Les commandes passent par HTTP (`/jsonrpc`) ; l'état peut
 * aussi être alimenté par le WebSocket (port 9090) quand il est disponible.
 */
class KodiController(
    override val device: Device,
    private val client: KodiClient,
    private val pollIntervalMs: Long = 1_000,
) : RemoteController {

    override val capabilities = Capability.entries.toSet()
    override val supportedKeys = RemoteKey.entries.toSet()

    override suspend fun connect() {
        // Un serveur qui répond 200 sans être Kodi (ou une réponse inattendue) ne doit
        // pas être considéré comme connecté.
        if (!client.ping()) throw RemoteException("L'appareil ne répond pas comme un serveur Kodi")
    }

    override suspend fun send(key: RemoteKey) {
        requireKey(key)
        when (key) {
            RemoteKey.UP -> client.input(KodiMethod.INPUT_UP)
            RemoteKey.DOWN -> client.input(KodiMethod.INPUT_DOWN)
            RemoteKey.LEFT -> client.input(KodiMethod.INPUT_LEFT)
            RemoteKey.RIGHT -> client.input(KodiMethod.INPUT_RIGHT)
            RemoteKey.SELECT -> client.input(KodiMethod.INPUT_SELECT)
            RemoteKey.BACK -> client.input(KodiMethod.INPUT_BACK)
            RemoteKey.HOME -> client.input(KodiMethod.INPUT_HOME)
            RemoteKey.MENU -> client.input(KodiMethod.INPUT_CONTEXT_MENU)
            RemoteKey.INFO -> client.input(KodiMethod.INPUT_INFO)
            RemoteKey.VOLUME_UP -> client.stepVolume("increment")
            RemoteKey.VOLUME_DOWN -> client.stepVolume("decrement")
            RemoteKey.MUTE -> client.toggleMute()
            RemoteKey.PLAY_PAUSE -> withPlayer { client.playPause(it) }
            RemoteKey.STOP -> withPlayer { client.stop(it) }
            RemoteKey.NEXT -> withPlayer { client.goTo(it, next = true) }
            RemoteKey.PREVIOUS -> withPlayer { client.goTo(it, next = false) }
            RemoteKey.REWIND -> withPlayer { client.seekStep(it, "smallbackward") }
            RemoteKey.FAST_FORWARD -> withPlayer { client.seekStep(it, "smallforward") }
        }
    }

    override suspend fun playUrl(url: String, title: String?) {
        client.openUrl(url)
    }

    override suspend fun setVolume(percent: Int) {
        client.setVolume(percent)
    }

    override suspend fun seekTo(positionMs: Long) {
        withPlayer { client.seekTo(it, positionMs) }
    }

    override suspend fun sendText(text: String) {
        client.sendText(text)
    }

    override fun playbackState(): Flow<PlaybackState> = flow {
        while (true) {
            emit(snapshot())
            delay(pollIntervalMs)
        }
    }

    internal suspend fun snapshot(): PlaybackState {
        val app = client.appProperties()
        val player = client.activePlayers().firstOrNull()
            ?: return PlaybackState(volumePercent = app.volume, muted = app.muted)
        // Le lecteur peut s'arrêter entre deux appels : Kodi répond alors par une erreur
        // JSON-RPC, que l'on traduit en état « à l'arrêt » plutôt qu'en échec.
        val (props, item) = try {
            client.playerProperties(player.id) to client.playerItem(player.id)
        } catch (_: KodiRpcException) {
            return PlaybackState(volumePercent = app.volume, muted = app.muted)
        }
        return PlaybackState(
            title = item.label.ifBlank { null },
            isPlaying = props.speed != 0,
            positionMs = props.timeMs,
            durationMs = props.totalTimeMs,
            volumePercent = app.volume,
            muted = app.muted,
        )
    }

    override suspend fun disconnect() {
        // Sans effet pour HTTP ; ferme la connexion et libère les requêtes en attente
        // pour un transport WebSocket.
        client.close()
    }

    private suspend fun withPlayer(action: suspend (Int) -> Unit) {
        val player = client.activePlayers().firstOrNull() ?: return
        action(player.id)
    }

    companion object {
        const val EXTRA_USERNAME = "username"
        const val EXTRA_PASSWORD = "password"

        fun create(device: Device, http: OkHttpClient): KodiController {
            val transport = KodiHttpTransport(
                http,
                device.baseUrl,
                device.extras[EXTRA_USERNAME],
                device.extras[EXTRA_PASSWORD],
            )
            return KodiController(device, KodiClient(transport))
        }
    }
}
