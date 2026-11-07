package io.github.ttbox.kodifin.model

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Fichier publié : nom (avec extension, utile aux lecteurs), type, taille et ouverture à un décalage. */
class LocalMedia(
    val name: String,
    val mimeType: String,
    /** Taille en octets ; négative si inconnue (pas de reprise ni de saut possible). */
    val length: Long,
    /** Flux positionné à [offset] octets du début. */
    val open: (offset: Long) -> InputStream,
)

/**
 * Petit serveur HTTP de diffusion de fichiers du téléphone vers les lecteurs du réseau local
 * (Chromecast, DLNA, Kodi…). Seuls les fichiers explicitement publiés sont servis, chacun sous un
 * chemin aléatoire de 128 bits (`/m/<jeton>/<nom>`) ; tout le reste répond 404. GET et HEAD,
 * requêtes partielles (`Range`) pour le saut dans le média, en-têtes DLNA de diffusion.
 * Aucune donnée ne sort ailleurs que vers le lecteur qui demande le fichier.
 */
class LocalMediaServer(
    private val maxConnections: Int = 8,
    private val socketTimeoutMs: Int = 30_000,
) {
    private val random = SecureRandom()
    private val media = ConcurrentHashMap<String, LocalMedia>()
    private val active = AtomicInteger()

    @Volatile private var socket: ServerSocket? = null
    @Volatile private var pool: ExecutorService? = null
    @Volatile private var host: String? = null

    val isRunning: Boolean get() = socket?.isClosed == false

    /** Nombre de fichiers publiés. */
    val publishedCount: Int get() = media.size

    /** Démarre (si besoin) sur [bindAddress], port libre choisi par le système. */
    @Synchronized
    fun start(bindAddress: InetAddress) {
        if (isRunning && host == bindAddress.hostAddress) return
        stop()
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(bindAddress, 0))
        val executor = Executors.newFixedThreadPool(maxConnections + 1) { r ->
            Thread(r, "kodifin-partage").apply { isDaemon = true }
        }
        socket = server
        pool = executor
        host = bindAddress.hostAddress
        executor.execute { acceptLoop(server, executor) }
    }

    /** Arrête le serveur et oublie tous les fichiers publiés. */
    @Synchronized
    fun stop() {
        media.clear()
        runCatching { socket?.close() }
        pool?.shutdownNow()
        socket = null
        pool = null
        host = null
    }

    /** Publie [item] et renvoie son adresse ; le serveur doit être démarré. */
    fun publish(item: LocalMedia): String {
        val server = socket ?: throw IllegalStateException("Serveur de partage arrêté")
        val token = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        media[token] = item
        val address = host!!.let { if (':' in it) "[${it.substringBefore('%')}]" else it }
        return "http://$address:${server.localPort}/m/$token/${encodeName(item.name)}"
    }

    /** Retire un fichier publié (adresse renvoyée par [publish]). */
    fun unpublish(url: String) {
        tokenOf(url)?.let(media::remove)
    }

    private fun acceptLoop(server: ServerSocket, executor: ExecutorService) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (_: IOException) {
                return
            }
            if (active.incrementAndGet() > maxConnections) {
                active.decrementAndGet()
                runCatching { client.close() }
                continue
            }
            try {
                executor.execute {
                    try {
                        client.use(::serve)
                    } finally {
                        active.decrementAndGet()
                    }
                }
            } catch (_: Exception) {
                active.decrementAndGet()
                runCatching { client.close() }
            }
        }
    }

    private fun serve(client: Socket) {
        client.soTimeout = socketTimeoutMs
        val input = BufferedInputStream(client.getInputStream())
        val out = client.getOutputStream()
        val requestLine = readLine(input) ?: return
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            if (headers.size >= MAX_HEADERS) return respond(out, 431, "Request Header Fields Too Large")
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val parts = requestLine.split(' ')
        if (parts.size != 3) return respond(out, 400, "Bad Request")
        val (method, path) = parts
        if (method != "GET" && method != "HEAD") return respond(out, 405, "Method Not Allowed", "Allow: GET, HEAD")
        val item = tokenOf(path)?.let(media::get) ?: return respond(out, 404, "Not Found")
        try {
            send(out, item, method == "HEAD", headers["range"])
        } catch (_: SocketException) {
            // Lecteur parti en cours de route (saut, arrêt) : rien à signaler.
        }
    }

    private fun send(out: OutputStream, item: LocalMedia, headOnly: Boolean, rangeHeader: String?) {
        val length = item.length
        val range = if (rangeHeader != null && length >= 0) parseRange(rangeHeader, length) else null
        if (rangeHeader != null && length >= 0 && range == null) {
            return respond(out, 416, "Range Not Satisfiable", "Content-Range: bytes */$length")
        }
        val start = range?.first ?: 0L
        val count = when {
            range != null -> range.last - range.first + 1
            length >= 0 -> length
            else -> -1
        }
        val head = buildString {
            append(if (range != null) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            append("Content-Type: ${item.mimeType}\r\n")
            if (count >= 0) append("Content-Length: $count\r\n")
            if (range != null) append("Content-Range: bytes ${range.first}-${range.last}/$length\r\n")
            if (length >= 0) append("Accept-Ranges: bytes\r\n")
            append("transferMode.dlna.org: Streaming\r\n")
            append("contentFeatures.dlna.org: DLNA.ORG_OP=${if (length >= 0) "01" else "00"};DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        if (headOnly) return out.flush()
        item.open(start).use { source ->
            val buffer = ByteArray(64 * 1024)
            var remaining = count
            while (remaining != 0L) {
                val wanted = if (remaining < 0) buffer.size else minOf(buffer.size.toLong(), remaining).toInt()
                val read = source.read(buffer, 0, wanted)
                if (read < 0) break
                out.write(buffer, 0, read)
                if (remaining > 0) remaining -= read
            }
        }
        out.flush()
    }

    private fun respond(out: OutputStream, code: Int, reason: String, extra: String? = null) {
        val head = "HTTP/1.1 $code $reason\r\n" + (extra?.let { "$it\r\n" } ?: "") +
            "Content-Length: 0\r\nConnection: close\r\n\r\n"
        runCatching {
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            out.flush()
        }
    }

    companion object {
        private const val MAX_LINE = 8 * 1024
        private const val MAX_HEADERS = 64
        private val TOKEN = Regex("^/m/([0-9a-f]{32})(/[^?#]*)?(\\?.*)?$")

        /** Jeton d'un chemin ou d'une adresse `…/m/<jeton>/<nom>`, `null` sinon. */
        internal fun tokenOf(pathOrUrl: String): String? {
            val path = pathOrUrl.substringAfter("://", "").let { if (it.isEmpty()) pathOrUrl else "/" + it.substringAfter('/', "") }
            return TOKEN.find(path)?.groupValues?.get(1)
        }

        /** Nom de fichier sûr dans une URL (extension conservée pour que le lecteur devine le format). */
        internal fun encodeName(name: String): String {
            val clean = name.replace(Regex("[\\\\/\\p{Cntrl}]"), "_").ifBlank { "media" }.take(120)
            return URLEncoder.encode(clean, "UTF-8").replace("+", "%20")
        }

        /**
         * Plage `bytes=a-b`, `bytes=a-` ou `bytes=-n` (une seule) ; `null` si invalide ou hors du
         * fichier de [length] octets.
         */
        internal fun parseRange(header: String, length: Long): LongRange? {
            val spec = header.trim().removePrefix("bytes=").takeIf { header.trim().startsWith("bytes=") } ?: return null
            if (',' in spec || length <= 0) return null
            val dash = spec.indexOf('-').takeIf { it >= 0 } ?: return null
            val a = spec.substring(0, dash).trim()
            val b = spec.substring(dash + 1).trim()
            return when {
                a.isEmpty() -> b.toLongOrNull()?.takeIf { it > 0 }?.let { n -> (length - minOf(n, length)) until length }
                else -> {
                    val first = a.toLongOrNull() ?: return null
                    val last = if (b.isEmpty()) length - 1 else (b.toLongOrNull() ?: return null).coerceAtMost(length - 1)
                    if (first >= length || last < first) null else first..last
                }
            }
        }

        /** Ligne terminée par CRLF (ou LF), bornée ; `null` en fin de flux. */
        private fun readLine(input: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0) return if (sb.isEmpty()) null else sb.toString()
                if (c == '\n'.code) return sb.toString().removeSuffix("\r")
                if (sb.length >= MAX_LINE) return null
                sb.append(c.toChar())
            }
        }
    }
}
