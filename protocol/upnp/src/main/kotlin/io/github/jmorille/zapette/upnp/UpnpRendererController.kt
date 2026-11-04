package io.github.jmorille.zapette.upnp

import io.github.jmorille.zapette.model.Capability
import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import io.github.jmorille.zapette.model.PlaybackState
import io.github.jmorille.zapette.model.RemoteController
import io.github.jmorille.zapette.model.RemoteException
import io.github.jmorille.zapette.model.RemoteKey
import io.github.jmorille.zapette.model.fetchString
import io.github.jmorille.zapette.model.formatHms
import io.github.jmorille.zapette.model.parseHms
import io.github.jmorille.zapette.model.requireKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Renderer UPnP AV / DLNA (téléviseurs, enceintes, Kodi en mode DLNA…). */
class UpnpRendererController(
    override val device: Device,
    private val http: OkHttpClient,
    private val soap: SoapClient = SoapClient(http),
    private val pollIntervalMs: Long = 1_000,
) : RemoteController {

    override val capabilities = setOf(
        Capability.PLAYBACK, Capability.VOLUME, Capability.SEEK, Capability.PLAY_URL, Capability.NOW_PLAYING,
    )
    override val supportedKeys = setOf(
        RemoteKey.PLAY_PAUSE, RemoteKey.STOP, RemoteKey.NEXT, RemoteKey.PREVIOUS,
        RemoteKey.VOLUME_UP, RemoteKey.VOLUME_DOWN, RemoteKey.MUTE,
    )

    private val lock = Mutex()
    private var description: UpnpDeviceDescription? = null

    private suspend fun describe(): UpnpDeviceDescription = lock.withLock {
        description ?: run {
            val location = device.extras[EXTRA_LOCATION] ?: throw RemoteException("URL de description UPnP absente")
            val xml = http.fetchString(Request.Builder().url(location).build())
            UpnpXml.parseDescription(xml, location).also { description = it }
        }
    }

    private suspend fun avTransport(action: String, vararg args: Pair<String, String>): Map<String, String> {
        val service = describe().service(UpnpXml.AV_TRANSPORT) ?: throw RemoteException("AVTransport absent")
        return soap.invoke(service.controlUrl, service.serviceType, action, listOf("InstanceID" to "0") + args)
    }

    private suspend fun rendering(action: String, vararg args: Pair<String, String>): Map<String, String> {
        val service = describe().service(UpnpXml.RENDERING_CONTROL) ?: throw RemoteException("RenderingControl absent")
        return soap.invoke(
            service.controlUrl,
            service.serviceType,
            action,
            listOf("InstanceID" to "0", "Channel" to "Master") + args,
        )
    }

    override suspend fun connect() {
        describe()
    }

    override suspend fun send(key: RemoteKey) {
        requireKey(key)
        when (key) {
            RemoteKey.PLAY_PAUSE -> {
                val state = avTransport("GetTransportInfo")["CurrentTransportState"]
                if (state == "PLAYING") avTransport("Pause") else avTransport("Play", "Speed" to "1")
            }
            RemoteKey.STOP -> avTransport("Stop")
            RemoteKey.NEXT -> avTransport("Next")
            RemoteKey.PREVIOUS -> avTransport("Previous")
            RemoteKey.VOLUME_UP -> setVolume(currentVolume() + VOLUME_STEP)
            RemoteKey.VOLUME_DOWN -> setVolume(currentVolume() - VOLUME_STEP)
            RemoteKey.MUTE -> {
                val muted = rendering("GetMute")["CurrentMute"] == "1"
                rendering("SetMute", "DesiredMute" to if (muted) "0" else "1")
            }
            else -> Unit
        }
    }

    private suspend fun currentVolume(): Int = rendering("GetVolume")["CurrentVolume"]?.toIntOrNull() ?: 0

    override suspend fun setVolume(percent: Int) {
        rendering("SetVolume", "DesiredVolume" to percent.coerceIn(0, 100).toString())
    }

    override suspend fun seekTo(positionMs: Long) {
        avTransport("Seek", "Unit" to "REL_TIME", "Target" to formatHms(positionMs))
    }

    override suspend fun playUrl(url: String, title: String?) {
        avTransport("SetAVTransportURI", "CurrentURI" to url, "CurrentURIMetaData" to didlMetadata(url, title))
        avTransport("Play", "Speed" to "1")
    }

    override fun playbackState(): Flow<PlaybackState> = flow {
        while (true) {
            val transport = avTransport("GetTransportInfo")
            val position = avTransport("GetPositionInfo")
            val volume = runCatching { currentVolume() }.getOrNull()
            emit(
                PlaybackState(
                    title = position["TrackMetaData"]?.let(::didlTitle),
                    isPlaying = transport["CurrentTransportState"] == "PLAYING",
                    positionMs = parseHms(position["RelTime"]),
                    durationMs = parseHms(position["TrackDuration"]),
                    volumePercent = volume,
                ),
            )
            delay(pollIntervalMs)
        }
    }

    companion object {
        const val EXTRA_LOCATION = "location"
        private const val VOLUME_STEP = 5

        /** Métadonnées DIDL-Lite minimales ; le résultat est échappé une seconde fois dans l'enveloppe SOAP. */
        fun didlMetadata(url: String, title: String?): String {
            val mime = guessMime(url)
            val upnpClass = when {
                mime.startsWith("audio") -> "object.item.audioItem.musicTrack"
                mime.startsWith("image") -> "object.item.imageItem.photo"
                else -> "object.item.videoItem"
            }
            return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
                "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
                "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
                "<item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
                "<dc:title>${xmlEscape(title ?: url.substringAfterLast('/'))}</dc:title>" +
                "<upnp:class>$upnpClass</upnp:class>" +
                "<res protocolInfo=\"http-get:*:$mime:*\">${xmlEscape(url)}</res>" +
                "</item></DIDL-Lite>"
        }

        fun guessMime(url: String): String {
            val ext = runCatching { url.toHttpUrl().encodedPath }.getOrDefault(url)
                .substringAfterLast('.', "").lowercase()
            return when (ext) {
                "mp3" -> "audio/mpeg"
                "flac" -> "audio/flac"
                "aac", "m4a" -> "audio/mp4"
                "ogg", "opus" -> "audio/ogg"
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "mkv" -> "video/x-matroska"
                "webm" -> "video/webm"
                "avi" -> "video/x-msvideo"
                "m3u8" -> "application/vnd.apple.mpegurl"
                else -> "video/mp4"
            }
        }

        internal fun didlTitle(metadata: String): String? = runCatching {
            if (metadata.isBlank() || metadata == "NOT_IMPLEMENTED") return null
            with(UpnpXml) { UpnpXml.parseDocument(metadata).find("title")?.textContent?.trim() }
        }.getOrNull()

        /** Construit l'appareil à partir d'une réponse SSDP et de sa description. */
        fun deviceFrom(response: SsdpResponse, description: UpnpDeviceDescription): Device {
            val url = response.location!!.toHttpUrl()
            return Device(
                id = "UPNP:${description.udn.ifEmpty { response.usn ?: url.toString() }}",
                name = description.friendlyName,
                kind = DeviceKind.UPNP_RENDERER,
                host = url.host,
                port = url.port,
                extras = mapOf(EXTRA_LOCATION to url.toString()),
            )
        }
    }
}
