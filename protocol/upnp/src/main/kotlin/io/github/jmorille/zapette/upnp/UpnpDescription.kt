package io.github.jmorille.zapette.upnp

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

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

    internal fun parseDocument(xml: String): Element {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            isExpandEntityReferences = false
        }
        return factory.newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement
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

    /** Analyse la description d'appareil et résout les URL relatives. */
    fun parseDescription(xml: String, location: String): UpnpDeviceDescription {
        val root = parseDocument(xml)
        val base: HttpUrl = (root.text("URLBase") ?: location).toHttpUrl()
        val device = root.child("device") ?: error("Élément <device> absent")
        val services = mutableListOf<UpnpService>()
        fun collect(d: Element) {
            d.child("serviceList")?.children("service")?.forEach { s ->
                val type = s.text("serviceType") ?: return@forEach
                val control = s.text("controlURL") ?: return@forEach
                services += UpnpService(type, base.resolve(control)?.toString() ?: control)
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
