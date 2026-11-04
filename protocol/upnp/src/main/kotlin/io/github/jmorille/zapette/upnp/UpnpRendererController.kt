package io.github.jmorille.zapette.upnp

import io.github.jmorille.zapette.model.Capability
import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import io.github.jmorille.zapette.model.MediaTypes
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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xml.sax.SAXException
import java.io.IOException

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
            val request = runCatching { Request.Builder().url(location).build() }
                .getOrElse { throw RemoteException("URL de description UPnP invalide : $location", it) }
            val xml = http.fetchString(request)
            val parsed = try {
                UpnpXml.parseDescription(xml, location)
            } catch (e: SAXException) {
                throw RemoteException("Description UPnP invalide", e)
            } catch (e: IOException) {
                throw RemoteException("Description UPnP illisible", e)
            }
            parsed.also { description = it }
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
                val muted = currentMute() ?: throw RemoteException("État de sourdine illisible")
                rendering("SetMute", "DesiredMute" to if (muted) "0" else "1")
            }
            else -> Unit
        }
    }

    /** Volume courant ; une valeur illisible lève une erreur plutôt que de repartir de 0. */
    private suspend fun currentVolume(): Int = rendering("GetVolume")["CurrentVolume"]?.trim()?.toIntOrNull()
        ?: throw RemoteException("Volume courant illisible")

    private suspend fun currentMute(): Boolean? = parseUpnpBoolean(rendering("GetMute")["CurrentMute"])

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

    /**
     * Sonde l'état toutes les [pollIntervalMs] ms. Volume et sourdine sont facultatifs (état émis
     * sans eux en cas d'échec) ; une erreur sur AVTransport (transport, description) termine le flux
     * avec une [RemoteException], au collecteur de décider d'une reprise.
     */
    override fun playbackState(): Flow<PlaybackState> = flow {
        while (true) {
            val transport = avTransport("GetTransportInfo")
            val position = avTransport("GetPositionInfo")
            // Volume et sourdine sont facultatifs : un échec ne doit pas interrompre le suivi.
            val volume = try { currentVolume() } catch (_: RemoteException) { null }
            val muted = try { currentMute() } catch (_: RemoteException) { null }
            emit(
                PlaybackState(
                    title = position["TrackMetaData"]?.let(::didlTitle),
                    isPlaying = transport["CurrentTransportState"] == "PLAYING",
                    positionMs = parseHms(position["RelTime"]),
                    durationMs = parseHms(position["TrackDuration"]),
                    volumePercent = volume,
                    muted = muted ?: false,
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

        fun guessMime(url: String): String = MediaTypes.forUrl(url) ?: "video/mp4"

        /** Booléen UPnP (UDA 1.1 : « 0 », « 1 », « false », « true », « no », « yes »). */
        internal fun parseUpnpBoolean(value: String?): Boolean? = when (value?.trim()?.lowercase()) {
            "1", "true", "yes" -> true
            "0", "false", "no" -> false
            else -> null
        }

        internal fun didlTitle(metadata: String): String? = runCatching {
            if (metadata.isBlank() || metadata == "NOT_IMPLEMENTED") return null
            with(UpnpXml) { UpnpXml.parseDocument(metadata).find("title")?.textContent?.trim() }
        }.getOrNull()

        /** Construit l'appareil à partir d'une réponse SSDP et de sa description ; `null` si LOCATION est invalide. */
        fun deviceFrom(response: SsdpResponse, description: UpnpDeviceDescription): Device? {
            val url = response.location?.toHttpUrlOrNull() ?: return null
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
