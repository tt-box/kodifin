package io.github.jmorille.zapette.upnp

import io.github.jmorille.zapette.model.RemoteException
import io.github.jmorille.zapette.model.XML_MEDIA_TYPE
import io.github.jmorille.zapette.model.HttpStatusException
import io.github.jmorille.zapette.model.await
import io.github.jmorille.zapette.upnp.UpnpXml.find
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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

class UpnpSoapFault(val errorCode: String?, message: String) : RemoteException("UPnP $errorCode : $message")

/** Client d'actions SOAP UPnP. */
class SoapClient(private val http: OkHttpClient) {

    fun envelope(serviceType: String, action: String, args: List<Pair<String, String>>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
        append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
        append("<u:").append(action).append(" xmlns:u=\"").append(serviceType).append("\">")
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
        val request = Request.Builder()
            .url(controlUrl)
            .header("SOAPACTION", "\"$serviceType#$action\"")
            .post(envelope(serviceType, action, args).toRequestBody(XML_MEDIA_TYPE))
            .build()
        val response = try {
            http.newCall(request).await()
        } catch (e: IOException) {
            throw RemoteException("Appareil UPnP injoignable", e)
        }
        val (code, body) = response.use { it.code to it.body.string() }
        if (code !in 200..299 && code != 500) throw HttpStatusException(code, "UPnP $action : HTTP $code")
        val root = runCatching { UpnpXml.parseDocument(body) }
            .getOrElse { throw RemoteException("Réponse SOAP invalide pour $action", it) }
        root.find("Fault")?.let { fault ->
            throw UpnpSoapFault(
                fault.find("errorCode")?.textContent?.trim(),
                fault.find("errorDescription")?.textContent?.trim() ?: "erreur SOAP",
            )
        }
        val result = root.find("${action}Response") ?: return emptyMap()
        val out = linkedMapOf<String, String>()
        val nodes = result.childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n.nodeType == org.w3c.dom.Node.ELEMENT_NODE) out[n.localName ?: n.nodeName] = n.textContent.orEmpty()
        }
        return out
    }
}
