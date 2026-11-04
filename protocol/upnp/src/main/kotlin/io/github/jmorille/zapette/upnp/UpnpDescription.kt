package io.github.jmorille.zapette.upnp

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import org.xml.sax.SAXException

data class UpnpService(val serviceType: String, val controlUrl: String)

data class UpnpDeviceDescription(
    val friendlyName: String,
    val deviceType: String,
    val udn: String,
    val manufacturer: String?,
    val modelName: String?,
    val services: List<UpnpService>,
) {
    fun service(prefix: String): UpnpService? = services.firstOrNull { it.serviceType.startsWith(prefix) }
}

/** Analyse sécurisée (XXE désactivé) des documents XML UPnP. */
object UpnpXml {
    const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:"
    const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:"

    /** Motif de détection d'une déclaration de type de document (DTD), interdite ici. */
    private val DOCTYPE = Regex("<!DOCTYPE", RegexOption.IGNORE_CASE)

    /**
     * Analyse un document XML sans DTD. Les DOCTYPE sont refusés avant l'analyse : sur Android,
     * le DocumentBuilderFactory ne prend pas en charge les fonctionnalités Xerces
     * (`disallow-doctype-decl`, traitement sécurisé…) et leur activation échoue silencieusement.
     * Le résolveur d'entités vide reste une seconde barrière contre les entités externes.
     */
    internal fun parseDocument(xml: String): Element {
        if (DOCTYPE.containsMatchIn(xml)) throw SAXException("DOCTYPE interdit dans un document UPnP")
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isValidating = false
            runCatching { isXIncludeAware = false }
            runCatching { isExpandEntityReferences = false }
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        }
        val builder = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> InputSource(StringReader("")) }
        }
        return builder.parse(InputSource(StringReader(xml))).documentElement
    }

    internal fun Element.children(localName: String): List<Element> {
        val out = mutableListOf<Element>()
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n.nodeType == Node.ELEMENT_NODE && (n.localName ?: n.nodeName) == localName) out += n as Element
        }
        return out
    }

    internal fun Element.child(localName: String): Element? = children(localName).firstOrNull()

    internal fun Element.text(localName: String): String? = child(localName)?.textContent?.trim()?.ifEmpty { null }

    /** Premier élément descendant portant ce nom local (quel que soit l'espace de noms). */
    internal fun Element.find(localName: String): Element? {
        val list = getElementsByTagNameNS("*", localName)
        return if (list.length > 0) list.item(0) as Element else null
    }

    /**
     * Analyse la description d'appareil et résout les URL relatives (par rapport à URLBase si elle
     * est valide, sinon à [location], conformément à UDA 1.1). Lève [SAXException] si le document
     * est invalide ou incomplet.
     */
    fun parseDescription(xml: String, location: String): UpnpDeviceDescription {
        val root = parseDocument(xml)
        val locationUrl = location.toHttpUrlOrNull() ?: throw SAXException("URL de description invalide : $location")
        val base: HttpUrl = root.text("URLBase")?.let { locationUrl.resolve(it) } ?: locationUrl
        val device = root.child("device") ?: throw SAXException("Élément <device> absent")
        val services = mutableListOf<UpnpService>()
        fun collect(d: Element) {
            d.child("serviceList")?.children("service")?.forEach { s ->
                val type = s.text("serviceType") ?: return@forEach
                val control = s.text("controlURL") ?: return@forEach
                val url = base.resolve(control) ?: return@forEach
                services += UpnpService(type, url.toString())
            }
            d.child("deviceList")?.children("device")?.forEach(::collect)
        }
        collect(device)
        return UpnpDeviceDescription(
            friendlyName = device.text("friendlyName") ?: "UPnP",
            deviceType = device.text("deviceType").orEmpty(),
            udn = device.text("UDN").orEmpty(),
            manufacturer = device.text("manufacturer"),
            modelName = device.text("modelName"),
            services = services,
        )
    }
}
