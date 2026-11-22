package io.github.ttbox.kodifin.upnp

import io.github.ttbox.kodifin.model.DEFAULT_MAX_BODY_BYTES
import io.github.ttbox.kodifin.model.HttpStatusException
import io.github.ttbox.kodifin.model.RedirectRefusedException
import io.github.ttbox.kodifin.model.RemoteError
import io.github.ttbox.kodifin.model.RemoteException
import io.github.ttbox.kodifin.model.XML_MEDIA_TYPE
import io.github.ttbox.kodifin.model.await
import io.github.ttbox.kodifin.upnp.UpnpXml.find
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import java.io.IOException

/** Échappement XML minimal pour les arguments SOAP et les métadonnées DIDL-Lite. */
fun xmlEscape(value: String): String = buildString(value.length) {
    value.forEach { c ->
        when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(c)
        }
    }
}

/**
 * Faute SOAP renvoyée par l'appareil : [description] est son texte (`errorDescription`),
 * transmis tel quel avec [errorCode].
 */
class UpnpSoapFault(val errorCode: String?, val description: String?) :
    RemoteException(RemoteError.DeviceRefused(detail = description, code = errorCode))

/**
 * Client d'actions SOAP UPnP. [maxBodyBytes] borne la réponse lue en mémoire (un appareil
 * défaillant ou hostile ne doit pas pouvoir épuiser la mémoire).
 */
class SoapClient(private val http: OkHttpClient, private val maxBodyBytes: Long = DEFAULT_MAX_BODY_BYTES) {

    fun envelope(serviceType: String, action: String, args: List<Pair<String, String>>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
        append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
        append("<u:").append(action).append(" xmlns:u=\"").append(xmlEscape(serviceType)).append("\">")
        args.forEach { (k, v) -> append('<').append(k).append('>').append(xmlEscape(v)).append("</").append(k).append('>') }
        append("</u:").append(action).append("></s:Body></s:Envelope>")
    }

    /** Invoque [action] et renvoie les arguments de sortie (nom local → texte). */
    suspend fun invoke(
        controlUrl: String,
        serviceType: String,
        action: String,
        args: List<Pair<String, String>> = emptyList(),
    ): Map<String, String> {
        // Le type de service vient de la description de l'appareil : il finit dans un en-tête
        // HTTP (OkHttp rejette alors tout caractère non ASCII ou de contrôle) et un attribut XML.
        if (!SERVICE_TYPE.matches(serviceType)) throw RemoteException(RemoteError.UnexpectedResponse("UPnP"))
        val request = Request.Builder()
            .url(controlUrl)
            .header("SOAPACTION", "\"$serviceType#$action\"")
            .post(envelope(serviceType, action, args).toRequestBody(XML_MEDIA_TYPE))
            .build()
        val response = try {
            http.newCall(request).await()
        } catch (e: RedirectRefusedException) {
            throw RemoteException(RemoteError.RedirectRefused, e)
        } catch (e: IOException) {
            throw RemoteException(RemoteError.Unreachable(request.url.host), e)
        }
        val (code, body) = response.use { it.code to readBounded(it, request.url.host) }
        if (code !in 200..299 && code != 500) throw HttpStatusException(code)
        // Certains renderers répondent 200 sans corps aux actions sans argument de sortie.
        if (code in 200..299 && body.isBlank()) return emptyMap()
        val root = runCatching { UpnpXml.parseDocument(body) }
            .getOrElse {
                if (code == 500) throw HttpStatusException(code)
                throw RemoteException(RemoteError.UnexpectedResponse("UPnP"), it)
            }
        root.find("Fault")?.let { fault ->
            throw UpnpSoapFault(
                fault.find("errorCode")?.textContent?.trim(),
                fault.find("errorDescription")?.textContent?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
        // UDA 1.1 : un statut 500 doit porter une faute SOAP ; sans elle, c'est une erreur HTTP.
        if (code == 500) throw HttpStatusException(code)
        val result = root.find("${action}Response") ?: return emptyMap()
        val out = linkedMapOf<String, String>()
        val nodes = result.childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n.nodeType == org.w3c.dom.Node.ELEMENT_NODE) out[n.localName ?: n.nodeName] = n.textContent.orEmpty()
        }
        return out
    }

    /**
     * Lit le corps (faute SOAP d'un statut 500 comprise) sans dépasser [maxBodyBytes] : au plus
     * `maxBodyBytes + 1` octets sont demandés, jamais le corps entier.
     */
    private fun readBounded(response: Response, host: String): String {
        val body = response.body
        val tooLarge = { RemoteException(RemoteError.ResponseTooLarge(host, maxBodyBytes)) }
        if (body.contentLength() > maxBodyBytes) throw tooLarge()
        return try {
            val source = body.source()
            if (source.request(maxBodyBytes + 1)) throw tooLarge()
            val buffer = source.buffer
            val charset = body.contentType()?.charset() ?: Charsets.UTF_8
            // Marque d'ordre d'octets UTF-8 ignorée, comme le fait ResponseBody.string().
            if (charset == Charsets.UTF_8 && buffer.size >= 3 && buffer.rangeEquals(0, UTF8_BOM)) buffer.skip(3)
            buffer.readString(charset)
        } catch (e: IOException) {
            // Corps interrompu : la réponse est inutilisable.
            throw RemoteException(RemoteError.Interrupted(host), e)
        }
    }

    private companion object {
        /** `urn:<domaine>:service:<type>:<version>` (UPnP Device Architecture), ASCII seulement. */
        val UTF8_BOM = ByteString.of(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

        val SERVICE_TYPE = Regex("^urn:[A-Za-z0-9][A-Za-z0-9.-]*:service:[A-Za-z0-9_.-]+:[0-9]+$")
    }
}
