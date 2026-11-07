package io.github.ttbox.kodifin.upnp

import io.github.ttbox.kodifin.model.TransferableMedia
import io.github.ttbox.kodifin.model.SupportsTransferOut
import io.github.ttbox.kodifin.model.fetchImage
import io.github.ttbox.kodifin.model.SupportsSeekStep
import io.github.ttbox.kodifin.model.SupportsPlayMode
import io.github.ttbox.kodifin.model.SupportsMediaDetails
import io.github.ttbox.kodifin.model.SupportsImages
import io.github.ttbox.kodifin.model.RepeatMode
import io.github.ttbox.kodifin.model.MediaDetails
import io.github.ttbox.kodifin.model.Capability
import io.github.ttbox.kodifin.model.Device
import io.github.ttbox.kodifin.model.DeviceKind
import io.github.ttbox.kodifin.model.MediaTypes
import io.github.ttbox.kodifin.model.PlaybackState
import io.github.ttbox.kodifin.model.RemoteController
import io.github.ttbox.kodifin.model.RemoteError
import io.github.ttbox.kodifin.model.RemoteException
import io.github.ttbox.kodifin.model.UnsupportedAction
import io.github.ttbox.kodifin.model.UnsupportedActionException
import io.github.ttbox.kodifin.model.RemoteKey
import io.github.ttbox.kodifin.model.fetchString
import io.github.ttbox.kodifin.model.formatHms
import io.github.ttbox.kodifin.model.parseHms
import io.github.ttbox.kodifin.model.requireKey
import io.github.ttbox.kodifin.model.SsdpResponse
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
) : RemoteController, SupportsMediaDetails, SupportsPlayMode, SupportsSeekStep, SupportsImages, SupportsTransferOut {

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
            val location = device.extras[EXTRA_LOCATION] ?: throw RemoteException(RemoteError.UnexpectedResponse("UPnP"))
            val request = runCatching { Request.Builder().url(location).build() }
                .getOrElse { throw RemoteException(RemoteError.InvalidAddress(location), it) }
            val xml = http.fetchString(request)
            val parsed = try {
                UpnpXml.parseDescription(xml, location)
            } catch (e: SAXException) {
                throw RemoteException(RemoteError.UnexpectedResponse("UPnP"), e)
            } catch (e: IOException) {
                throw RemoteException(RemoteError.UnexpectedResponse("UPnP"), e)
            }
            parsed.also { description = it }
        }
    }

    /** Plage de volume lue dans le SCPD de RenderingControl ; `null` tant qu'elle n'est pas connue. */
    @Volatile
    private var volumeRange: VolumeRange? = null

    /**
     * Choix d'instance : toutes les actions visent `InstanceID` = 0. UPnP AV réserve 0 à
     * l'instance par défaut, toujours présente ; d'autres instances n'existent que si un
     * ConnectionManager les crée via `PrepareForConnection`, ce que les renderers grand public
     * n'exposent pratiquement jamais et que l'application n'utilise pas.
     */
    private suspend fun avTransport(action: String, vararg args: Pair<String, String>): Map<String, String> {
        val service = describe().service(UpnpXml.AV_TRANSPORT) ?: throw RemoteException(RemoteError.UnexpectedResponse("UPnP"))
        return soap.invoke(service.controlUrl, service.serviceType, action, listOf("InstanceID" to INSTANCE_ID) + args)
    }

    /** Actions RenderingControl sur l'instance 0 (voir [avTransport]) et le canal `Master`. */
    private suspend fun rendering(action: String, vararg args: Pair<String, String>): Map<String, String> {
        val service = describe().service(UpnpXml.RENDERING_CONTROL) ?: throw RemoteException(RemoteError.UnexpectedResponse("UPnP"))
        return soap.invoke(
            service.controlUrl,
            service.serviceType,
            action,
            listOf("InstanceID" to INSTANCE_ID, "Channel" to "Master") + args,
        )
    }

    /**
     * Plage de `Volume` annoncée par le SCPD de RenderingControl, lue une fois. SCPD absent,
     * plage absente ou incohérente : 0–100 (mémorisé). Erreur de transport : 0–100 pour cette
     * fois, nouvelle lecture à l'appel suivant.
     */
    private suspend fun volumeRange(): VolumeRange {
        volumeRange?.let { return it }
        val scpdUrl = describe().service(UpnpXml.RENDERING_CONTROL)?.scpdUrl
        val range = if (scpdUrl == null) {
            VolumeRange.DEFAULT
        } else {
            val xml = try {
                val request = runCatching { Request.Builder().url(scpdUrl).build() }.getOrNull()
                request?.let { http.fetchString(it) }
            } catch (_: RemoteException) {
                return VolumeRange.DEFAULT
            }
            xml?.let { runCatching { UpnpXml.parseVolumeRange(it) }.getOrNull() } ?: VolumeRange.DEFAULT
        }
        volumeRange = range
        return range
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
            RemoteKey.VOLUME_UP -> setRawVolume(volumeRange().adjust(currentRawVolume(), VOLUME_STEP))
            RemoteKey.VOLUME_DOWN -> setRawVolume(volumeRange().adjust(currentRawVolume(), -VOLUME_STEP))
            RemoteKey.MUTE -> {
                val muted = currentMute() ?: throw RemoteException(RemoteError.UnexpectedResponse("UPnP"))
                rendering("SetMute", "DesiredMute" to if (muted) "0" else "1")
            }
            else -> Unit
        }
    }

    /** Volume brut (unités du renderer) ; une valeur illisible lève une erreur plutôt que de repartir de 0. */
    private suspend fun currentRawVolume(): Int = rendering("GetVolume")["CurrentVolume"]?.trim()?.toIntOrNull()
        ?: throw RemoteException(RemoteError.UnexpectedResponse("UPnP"))

    /** Volume courant en pourcentage de la plage annoncée. */
    private suspend fun currentVolume(): Int = volumeRange().toPercent(currentRawVolume())

    private suspend fun currentMute(): Boolean? = parseUpnpBoolean(rendering("GetMute")["CurrentMute"])

    private suspend fun setRawVolume(raw: Int) {
        rendering("SetVolume", "DesiredVolume" to raw.toString())
    }

    /** [percent] (0–100) est converti dans la plage `Volume` du renderer (voir [VolumeRange]). */
    override suspend fun setVolume(percent: Int) {
        setRawVolume(volumeRange().fromPercent(percent))
    }

    override suspend fun seekTo(positionMs: Long) {
        avTransport("Seek", "Unit" to "REL_TIME", "Target" to formatHms(positionMs.coerceAtLeast(0)))
    }

    override suspend fun seekBy(deltaMs: Long) {
        val position = avTransport("GetPositionInfo")
        val duration = parseHms(position["TrackDuration"])
        val target = parseHms(position["RelTime"]) + deltaMs
        seekTo(if (duration > 0) target.coerceAtMost(duration - 1_000) else target)
    }

    // --- Modes de lecture (AVTransport PlayMode) ----------------------------------------------

    override suspend fun mediaDetails(): MediaDetails {
        val playing = avTransport("GetTransportInfo")["CurrentTransportState"] == "PLAYING"
        // GetTransportSettings est obligatoire selon UPnP AV, mais des renderers l'omettent.
        val mode = try {
            avTransport("GetTransportSettings")["PlayMode"]?.trim()?.uppercase()
        } catch (_: RemoteException) {
            null
        }
        return MediaDetails(
            repeat = when (mode) {
                null -> null
                "REPEAT_ONE" -> RepeatMode.ONE
                "REPEAT_ALL" -> RepeatMode.ALL
                else -> RepeatMode.OFF
            },
            shuffle = mode?.let { it == "SHUFFLE" || it == "RANDOM" },
            speed = if (playing) 1 else 0,
        )
    }

    override suspend fun setRepeat(mode: RepeatMode) = setPlayMode(
        when (mode) {
            RepeatMode.OFF -> "NORMAL"
            RepeatMode.ONE -> "REPEAT_ONE"
            RepeatMode.ALL -> "REPEAT_ALL"
        },
    )

    override suspend fun setShuffle(enabled: Boolean) = setPlayMode(if (enabled) "SHUFFLE" else "NORMAL")

    private suspend fun setPlayMode(mode: String) {
        try {
            avTransport("SetPlayMode", "NewPlayMode" to mode)
        } catch (e: UpnpSoapFault) {
            throw UnsupportedActionException(UnsupportedAction.PlayMode, device.kind, e)
        }
    }

    /** Média en cours (`GetMediaInfo` → CurrentURI) et position, si c'est une adresse HTTP. */
    override suspend fun currentMedia(): TransferableMedia? {
        val info = avTransport("GetMediaInfo")
        val uri = info["CurrentURI"]?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return null
        val position = avTransport("GetPositionInfo")
        val title = (position["TrackMetaData"] ?: info["CurrentURIMetaData"])?.let(::didlInfo)?.title
        return TransferableMedia(uri, title, positionMs = parseHms(position["RelTime"]))
    }

    /** Pochette annoncée par le renderer (`upnp:albumArtURI`), téléchargée sans authentification. */
    override suspend fun image(imageKey: String, maxWidth: Int): ByteArray? = http.fetchImage(imageKey)

    /** Option « DLNA alternatif » : métadonnées minimales pour les renderers capricieux. */
    private val alternativeDidl: Boolean
        get() = device.extras[EXTRA_ALT_DIDL]?.trim().equals("true", ignoreCase = true)

    override suspend fun playUrl(url: String, title: String?) {
        if (alternativeDidl) {
            try {
                setUri(url, didlMetadataAlternative(url, title))
            } catch (_: UpnpSoapFault) {
                // Certains renderers refusent toute métadonnée qu'ils ne savent pas analyser :
                // nouvel essai avec CurrentURIMetaData vide (autorisé par UPnP AV).
                setUri(url, "")
            }
        } else {
            setUri(url, didlMetadata(url, title))
        }
        avTransport("Play", "Speed" to "1")
    }

    private suspend fun setUri(url: String, metadata: String) {
        avTransport("SetAVTransportURI", "CurrentURI" to url, "CurrentURIMetaData" to metadata)
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
            val meta = position["TrackMetaData"]?.let(::didlInfo)
            emit(
                PlaybackState(
                    title = meta?.title,
                    artist = meta?.artist,
                    album = meta?.album,
                    artworkKey = meta?.artUrl,
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

        /** Fabricant et modèle annoncés dans la description (repérage des renderers BubbleUPnP Server). */
        const val EXTRA_MANUFACTURER = "upnp_manufacturer"
        const val EXTRA_MODEL = "upnp_model"

        /**
         * Renderer exposé par BubbleUPnP Server (Chromecast ou autre lecteur présenté en DLNA, avec
         * conversion à la volée par le serveur) : repéré au fabricant, au modèle ou au nom.
         */
        fun isBubbleUpnpServer(device: Device): Boolean =
            device.kind == DeviceKind.UPNP_RENDERER &&
                listOfNotNull(device.extras[EXTRA_MANUFACTURER], device.extras[EXTRA_MODEL], device.name)
                    .any { it.contains("bubbleupnp", ignoreCase = true) }

        /** Extra « DLNA alternatif » (valeur `true`). */
        const val EXTRA_ALT_DIDL = "upnp_alt_didl"
        /** Pas des touches de volume, en pourcentage de la plage. */
        private const val VOLUME_STEP = 5

        /** Instance par défaut d'AVTransport et de RenderingControl (voir [avTransport]). */
        private const val INSTANCE_ID = "0"

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

        /**
         * Variante tolérante : protocolInfo générique `http-get:*:*:*` (aucun type MIME imposé,
         * aucun paramètre `DLNA.ORG_*`), identifiants d'objet neutres ; la classe UPnP reste
         * déduite de l'extension pour que le renderer choisisse le bon lecteur.
         */
        fun didlMetadataAlternative(url: String, title: String?): String {
            val mime = guessMime(url)
            val upnpClass = when {
                mime.startsWith("audio") -> "object.item.audioItem"
                mime.startsWith("image") -> "object.item.imageItem"
                else -> "object.item.videoItem"
            }
            return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
                "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
                "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
                "<item id=\"1\" parentID=\"0\" restricted=\"1\">" +
                "<dc:title>${xmlEscape(title ?: url.substringAfterLast('/'))}</dc:title>" +
                "<upnp:class>$upnpClass</upnp:class>" +
                "<res protocolInfo=\"http-get:*:*:*\">${xmlEscape(url)}</res>" +
                "</item></DIDL-Lite>"
        }

        fun guessMime(url: String): String = MediaTypes.forUrl(url) ?: "video/mp4"

        /** Booléen UPnP (UDA 1.1 : « 0 », « 1 », « false », « true », « no », « yes »). */
        internal fun parseUpnpBoolean(value: String?): Boolean? = when (value?.trim()?.lowercase()) {
            "1", "true", "yes" -> true
            "0", "false", "no" -> false
            else -> null
        }

        internal fun didlTitle(metadata: String): String? = didlInfo(metadata)?.title

        /** Métadonnées DIDL-Lite du morceau en cours ; `null` si absentes ou illisibles. */
        internal fun didlInfo(metadata: String): DidlInfo? = runCatching {
            if (metadata.isBlank() || metadata == "NOT_IMPLEMENTED") return null
            with(UpnpXml) {
                val root = UpnpXml.parseDocument(metadata)
                fun text(name: String) = root.find(name)?.textContent?.trim()?.ifEmpty { null }
                DidlInfo(
                    title = text("title"),
                    artist = text("artist") ?: text("creator"),
                    album = text("album"),
                    artUrl = text("albumArtURI")?.takeIf { it.startsWith("http://") || it.startsWith("https://") },
                )
            }
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
                extras = buildMap {
                    put(EXTRA_LOCATION, url.toString())
                    description.manufacturer?.takeIf { it.isNotBlank() }?.let { put(EXTRA_MANUFACTURER, it.take(80)) }
                    description.modelName?.takeIf { it.isNotBlank() }?.let { put(EXTRA_MODEL, it.take(80)) }
                },
            )
        }
    }
}

/** Titre, artiste, album et pochette d'un élément DIDL-Lite. */
data class DidlInfo(val title: String?, val artist: String?, val album: String?, val artUrl: String?)
