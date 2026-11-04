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

/** Service UPnP : [scpdUrl] (description des actions et variables d'état) est facultative. */
data class UpnpService(val serviceType: String, val controlUrl: String, val scpdUrl: String? = null)

/**
 * Plage de la variable d'état `Volume` de RenderingControl (`allowedValueRange` du SCPD).
 * UPnP n'impose pas 0–100 : certains renderers annoncent 0–30, 0–60, un pas de 5… Les
 * pourcentages de l'application sont convertis vers cette plage, et inversement.
 */
data class VolumeRange(val minimum: Int = 0, val maximum: Int = 100, val step: Int = 1) {
    init {
        require(maximum > minimum && step > 0) { "Plage de volume invalide : $minimum..$maximum pas $step" }
    }

    private val span get() = maximum - minimum

    /** Valeur brute → pourcentage (0–100), valeur hors plage ramenée aux bornes. */
    fun toPercent(raw: Int): Int =
        Math.round((raw.coerceIn(minimum, maximum) - minimum) * 100.0 / span).toInt()

    /** Pourcentage → valeur brute alignée sur le pas, dans la plage. */
    fun fromPercent(percent: Int): Int {
        val raw = minimum + percent.coerceIn(0, 100) * span / 100.0
        val snapped = minimum + Math.round((raw - minimum) / step).toInt() * step
        return snapped.coerceIn(minimum, maximum)
    }

    /**
     * Valeur brute après un pas de [deltaPercent] depuis [raw] ; au moins un pas de la plage
     * (sinon une plage étroite, 0–10 par exemple, ne bougerait jamais).
     */
    fun adjust(raw: Int, deltaPercent: Int): Int {
        val current = raw.coerceIn(minimum, maximum)
        val target = fromPercent(toPercent(current) + deltaPercent)
        if (target != current || deltaPercent == 0) return target
        return (current + if (deltaPercent > 0) step else -step).coerceIn(minimum, maximum)
    }

    companion object {
        /** Plage supposée quand le SCPD est absent, illisible ou muet : 0–100, pas de 1. */
        val DEFAULT = VolumeRange()
    }
}

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

    /** Début d'une déclaration de type de document (DTD). */
    private val DOCTYPE = Regex("<!DOCTYPE", RegexOption.IGNORE_CASE)

    /** Déclaration d'entité, interdite partout (XXE, expansion exponentielle). */
    private val ENTITY = Regex("<!\\s*ENTITY", RegexOption.IGNORE_CASE)

    /** Début d'élément (ni `<?`, ni `<!`) : un DOCTYPE doit le précéder. */
    private val ELEMENT_START = Regex("<[^?!]")

    /**
     * Retire l'éventuelle déclaration DOCTYPE, tolérée si elle ne déclare aucune entité :
     * `<!DOCTYPE racine>`, identifiant externe (jamais chargé, puisque retiré avant l'analyse)
     * ou sous-ensemble interne sans `<!ENTITY` ni entité paramètre (`%`). Toute déclaration
     * d'entité (où qu'elle soit), un DOCTYPE multiple, mal placé ou non terminé est refusé.
     */
    private fun stripDoctype(xml: String): String {
        if (ENTITY.containsMatchIn(xml)) throw SAXException("DOCTYPE avec déclaration d'entité interdit (XXE)")
        val matches = DOCTYPE.findAll(xml).toList()
        if (matches.isEmpty()) return xml
        if (matches.size > 1) throw SAXException("DOCTYPE multiple interdit")
        val start = matches.single().range.first
        if (ELEMENT_START.containsMatchIn(xml.substring(0, start))) throw SAXException("DOCTYPE après l'élément racine interdit")
        var i = matches.single().range.last + 1
        var quote: Char? = null
        var subset = false
        while (i < xml.length) {
            val c = xml[i]
            when {
                quote != null -> if (c == quote) quote = null
                c == '"' || c == '\'' -> quote = c
                c == '[' -> subset = true
                c == ']' -> subset = false
                c == '>' && !subset -> break
            }
            i++
        }
        if (i >= xml.length) throw SAXException("DOCTYPE non terminé")
        val declaration = xml.substring(start, i + 1)
        if ('[' in declaration && '%' in declaration.substringAfter('[')) {
            throw SAXException("DOCTYPE avec entité paramètre interdit (XXE)")
        }
        return xml.substring(0, start) + xml.substring(i + 1)
    }

    /** Taille maximale d'un document (description, réponse SOAP) : largement au-delà des cas réels. */
    const val MAX_DOCUMENT_CHARS = 512 * 1024

    /** Profondeur maximale d'imbrication des éléments. */
    const val MAX_DEPTH = 64

    /**
     * Estime la profondeur d'imbrication avant l'analyse DOM (borne contre les documents
     * pathologiques) ; commentaires, CDATA et instructions de traitement sont sautés.
     */
    private fun checkDepth(xml: String) {
        var depth = 0
        var i = xml.indexOf('<')
        while (i >= 0 && i < xml.length - 1) {
            val next = xml[i + 1]
            val end = when {
                xml.startsWith("<!--", i) -> xml.indexOf("-->", i).let { if (it < 0) -1 else it + 2 }
                xml.startsWith("<![CDATA[", i) -> xml.indexOf("]]>", i).let { if (it < 0) -1 else it + 2 }
                else -> xml.indexOf('>', i)
            }
            if (end < 0) return // document tronqué : l'analyseur le rejettera
            when {
                next == '/' -> depth--
                next == '!' || next == '?' -> Unit
                xml[end - 1] != '/' -> if (++depth > MAX_DEPTH) throw SAXException("Document UPnP trop imbriqué")
            }
            i = xml.indexOf('<', end + 1)
        }
    }

    /**
     * Analyse un document XML sans DTD. Le DOCTYPE éventuel est retiré AVANT l'analyse s'il ne
     * déclare aucune entité, refusé sinon (voir [stripDoctype]) : sur Android, le
     * DocumentBuilderFactory ne prend pas en charge les fonctionnalités Xerces
     * (`disallow-doctype-decl`, traitement sécurisé…) et leur activation échoue silencieusement.
     * Le résolveur d'entités vide reste une seconde barrière contre les entités externes.
     */
    internal fun parseDocument(xml: String): Element {
        if (xml.length > MAX_DOCUMENT_CHARS) throw SAXException("Document UPnP trop volumineux (${xml.length} caractères)")
        val withoutDoctype = stripDoctype(xml)
        checkDepth(withoutDoctype)
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
        return builder.parse(InputSource(StringReader(withoutDoctype))).documentElement
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
     *
     * Protection SSRF : les requêtes SOAP ne doivent viser que l'appareil décrit. Une URLBase
     * désignant un autre hôte que [location] est ignorée (repli sur [location]) ; un service dont
     * l'URL de contrôle résolue désigne un autre hôte, ou un schéma autre que http/https, est ignoré.
     */
    fun parseDescription(xml: String, location: String): UpnpDeviceDescription {
        val root = parseDocument(xml)
        val locationUrl = location.toHttpUrlOrNull() ?: throw SAXException("URL de description invalide : $location")
        val base: HttpUrl = root.text("URLBase")
            ?.let { locationUrl.resolve(it) }
            ?.takeIf { it.host == locationUrl.host }
            ?: locationUrl
        val device = root.child("device") ?: throw SAXException("Élément <device> absent")
        val services = mutableListOf<UpnpService>()
        fun collect(d: Element) {
            d.child("serviceList")?.children("service")?.forEach { s ->
                val type = s.text("serviceType") ?: return@forEach
                val control = s.text("controlURL") ?: return@forEach
                // resolve() n'aboutit que pour http/https : file:, javascript:… sont écartés.
                val url = base.resolve(control)?.takeIf { it.host == locationUrl.host } ?: return@forEach
                // Même contrainte d'hôte pour le SCPD ; absent ou étranger, il est simplement ignoré.
                val scpd = s.text("SCPDURL")?.let { base.resolve(it) }?.takeIf { it.host == locationUrl.host }
                services += UpnpService(type, url.toString(), scpd?.toString())
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

    /**
     * Plage de la variable d'état `Volume` dans un SCPD RenderingControl
     * (`stateVariable/allowedValueRange` : `minimum`, `maximum`, `step` facultatif) ;
     * `null` si elle est absente ou incohérente (maximum ≤ minimum, pas ≤ 0).
     */
    fun parseVolumeRange(scpd: String): VolumeRange? {
        val root = parseDocument(scpd)
        val variables = root.getElementsByTagNameNS("*", "stateVariable")
        for (i in 0 until variables.length) {
            val variable = variables.item(i) as Element
            if (variable.text("name") != "Volume") continue
            val range = variable.child("allowedValueRange") ?: return null
            val min = range.text("minimum")?.toIntOrNull() ?: return null
            val max = range.text("maximum")?.toIntOrNull() ?: return null
            val step = range.text("step")?.let { it.toIntOrNull() ?: return null } ?: 1
            return if (max > min && step > 0) VolumeRange(min, max, step) else null
        }
        return null
    }
}
