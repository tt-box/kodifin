package io.github.ttbox.kodifin.model

import java.net.Inet6Address
import java.net.InetAddress

/** Résolveur de noms d'hôte (nom → adresses) ; peut bloquer : appeler hors du fil principal. */
typealias HostResolver = (String) -> List<InetAddress>

/** Résolveur système (DNS local, mDNS selon la plateforme). */
val SYSTEM_HOST_RESOLVER: HostResolver = { InetAddress.getAllByName(it).toList() }

/**
 * Classement des hôtes « réseau local » partagé par les protocoles (jeton Plex, pochettes
 * UPnP/Cast…). Adresses locales : privées RFC 1918, lien local, IPv6 ULA (`fc00::/7`) et,
 * selon l'appelant, boucle locale. Les adresses littérales sont analysées sans DNS.
 */
object LocalNetwork {
    private val DOTTED_QUAD = Regex("""(0|[1-9]\d{0,2})(\.(0|[1-9]\d{0,2})){3}""")
    private val HEX = Regex("0x[0-9a-f]*")
    private val LABEL = Regex("[a-z0-9]([a-z0-9-]*[a-z0-9])?")

    /** Forme comparable d'un hôte : sans espaces ni crochets IPv6, en minuscules. */
    fun normalize(host: String): String = host.trim().removePrefix("[").removeSuffix("]").lowercase()

    /**
     * Hôte du réseau local :
     * - adresse littérale privée (RFC 1918), de lien local, de boucle ou IPv6 ULA (`fc00::/7`) ;
     * - `localhost`, ou nom en `.local` (mDNS), `.lan` ou `.home.arpa` (RFC 8375) ;
     * - tout autre nom DNS (court comme `htpc`, ou qualifié comme `htpc.home.loc`) UNIQUEMENT
     *   s'il résout, via [resolve], vers des adresses toutes locales : un nom public, ou un
     *   suffixe de recherche DNS (`htpc` → `htpc.exemple.com`), désignerait sinon un hôte
     *   public. Échec ou absence de résolution, ou une seule adresse publique : non local.
     *
     * Les formes numériques ambiguës (`134744072`, `0x08080808`, `8.8.2056`, zéros de tête
     * interprétés en octal) sont refusées : un résolveur les traduirait en adresse publique.
     * Le point final (`nom.local.`) et l'adresse joker `0.0.0.0` sont refusés.
     * [resolve] peut bloquer (DNS) : appeler hors du fil principal.
     */
    fun isLocalHost(host: String, resolve: HostResolver = { emptyList() }): Boolean {
        val h = host.removePrefix("[").removeSuffix("]").lowercase()
        if (h.isEmpty()) return false
        literal(h)?.let { return isLocalAddress(it, allowLoopback = true) }
        if (!isValidName(h)) return false
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa")) return true
        val addresses = try {
            resolve(h)
        } catch (_: Exception) {
            return false
        }
        return addresses.isNotEmpty() && addresses.all { isLocalAddress(it, allowLoopback = true) }
    }

    /**
     * Adresses de [host] si TOUTES sont locales (privées, lien local, ULA ; boucle locale
     * seulement si [allowLoopback]), sinon `null`. Contrairement à [isLocalHost], aucun suffixe
     * de nom n'est cru sur parole : tout nom est résolu via [resolve] (seule requête émise, vers
     * le résolveur local), une adresse littérale est analysée sans DNS. Formes numériques
     * ambiguës, nom invalide, échec ou résultat vide : `null`. Peut bloquer (DNS).
     */
    fun lanAddresses(host: String, resolve: HostResolver, allowLoopback: Boolean = false): List<InetAddress>? {
        val h = normalize(host)
        if (h.isEmpty()) return null
        val addresses = literal(h)?.let(::listOf) ?: run {
            if (!isValidName(h)) return null
            try {
                resolve(h)
            } catch (_: Exception) {
                return null
            }
        }
        return addresses.takeIf { list -> list.isNotEmpty() && list.all { isLocalAddress(it, allowLoopback) } }
    }

    /**
     * Adresse littérale (IPv6, ou IPv4 en quadruplet canonique) analysée sans DNS ; `null` pour
     * un nom ou une adresse illisible.
     */
    private fun literal(h: String): InetAddress? {
        if (':' !in h && !DOTTED_QUAD.matches(h)) return null
        // Une chaîne contenant « : » ou un quadruplet pointé n'est jamais résolue par getByName.
        return runCatching { InetAddress.getByName(h) }.getOrNull()
    }

    /** Nom DNS acceptable : ni forme numérique déguisée, ni label vide (point final) ou invalide. */
    private fun isValidName(h: String): Boolean {
        if (':' in h || DOTTED_QUAD.matches(h)) return false
        val labels = h.split('.')
        // Nombre seul, hexadécimal ou quadruplet non canonique : potentiellement une IPv4 déguisée.
        if (labels.all { it.isNotEmpty() && (it.all(Char::isDigit) || HEX.matches(it)) }) return false
        if (labels.any { !LABEL.matches(it) }) return false
        return !labels.last().all(Char::isDigit)
    }

    /** Adresse locale ; une IPv4 mappée (`::ffff:a.b.c.d`) est évaluée comme l'IPv4 incluse. */
    fun isLocalAddress(address: InetAddress, allowLoopback: Boolean = true): Boolean {
        val bytes = address.address
        // ::ffff:a.b.c.d : certaines JVM la renvoient en Inet6Address, on évalue l'IPv4 incluse.
        if (address is Inet6Address && bytes.size == 16 &&
            (0 until 10).all { bytes[it].toInt() == 0 } && bytes[10].toInt() == -1 && bytes[11].toInt() == -1
        ) {
            return isLocalAddress(InetAddress.getByAddress(bytes.copyOfRange(12, 16)), allowLoopback)
        }
        val uniqueLocalV6 = address is Inet6Address && (bytes[0].toInt() and 0xfe) == 0xfc
        return address.isSiteLocalAddress || address.isLinkLocalAddress || uniqueLocalV6 ||
            (allowLoopback && address.isLoopbackAddress)
    }
}
