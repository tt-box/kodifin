package io.github.jmorille.zapette.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.OpenableColumns
import io.github.jmorille.zapette.R
import io.github.jmorille.zapette.model.LocalMedia
import io.github.jmorille.zapette.model.LocalMediaServer
import io.github.jmorille.zapette.model.MediaTypes
import io.github.jmorille.zapette.model.RemoteException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress

/** Fichier du téléphone publié sur le réseau local. */
data class SharedFile(val url: String, val title: String)

/** Partage de fichiers du téléphone vers les lecteurs du réseau (isolé pour les tests JVM). */
interface LocalShare {
    /** Publie le document [uri] (sélecteur de fichiers Android) et renvoie son adresse locale. */
    suspend fun share(uri: String): SharedFile

    /** Arrête le partage : plus aucun fichier n'est servi. */
    fun stop()

    val isSharing: Boolean
}

/**
 * Implémentation Android : [LocalMediaServer] lié à l'adresse IPv4 du réseau local (Wi-Fi ou
 * Ethernet), documents ouverts par le ContentResolver, service au premier plan tant qu'un fichier
 * est partagé (sinon Android coupe le serveur dès que l'application passe en arrière-plan).
 */
class AndroidLocalShare(private val context: Context) : LocalShare {
    private val server = LocalMediaServer()
    private val granted = mutableSetOf<Uri>()

    override val isSharing: Boolean get() = server.isRunning && server.publishedCount > 0

    override suspend fun share(uri: String): SharedFile = withContext(Dispatchers.IO) {
        val doc = Uri.parse(uri)
        val resolver = context.contentResolver
        // Accès conservé tant que le partage dure, même si l'activité est recréée.
        runCatching { resolver.takePersistableUriPermission(doc, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            .onSuccess { synchronized(granted) { granted += doc } }
        var name = doc.lastPathSegment?.substringAfterLast('/') ?: "media"
        var size = -1L
        resolver.query(doc, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !c.isNull(it) }?.let { name = c.getString(it) }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
            }
        }
        val mime = resolver.getType(doc)?.takeIf { '/' in it && it != "application/octet-stream" }
            ?: MediaTypes.forUrl(name) ?: "application/octet-stream"
        val address = lanAddress() ?: throw RemoteException("Aucun réseau local (Wi-Fi ou Ethernet) pour partager le fichier")
        server.start(address)
        val url = server.publish(LocalMedia(name, mime, size) { offset -> open(doc, offset) })
        LocalShareService.start(context, name)
        SharedFile(url, name.substringBeforeLast('.').ifBlank { name })
    }

    /** Flux du document positionné à [offset] (saut direct si le fichier est un vrai fichier). */
    private fun open(doc: Uri, offset: Long): InputStream {
        val resolver = context.contentResolver
        if (offset > 0) {
            val afd = resolver.openAssetFileDescriptor(doc, "r")
            if (afd != null && afd.startOffset == 0L) {
                val stream = afd.createInputStream()
                if (stream is FileInputStream && runCatching { stream.channel.position(offset) }.isSuccess) return stream
                stream.close()
            } else {
                afd?.close()
            }
        }
        val stream = resolver.openInputStream(doc) ?: throw RemoteException("Fichier illisible")
        var remaining = offset
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped <= 0) {
                if (stream.read() < 0) break
                remaining--
            } else {
                remaining -= skipped
            }
        }
        return stream
    }

    override fun stop() {
        server.stop()
        synchronized(granted) {
            granted.forEach { runCatching { context.contentResolver.releasePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
            granted.clear()
        }
        LocalShareService.stop(context)
    }

    /** Adresse IPv4 du réseau local actif (Wi-Fi ou Ethernet), jamais celle du réseau mobile. */
    private fun lanAddress(): InetAddress? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(network) ?: return null
        val local = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        if (!local) return null
        return cm.getLinkProperties(network)?.linkAddresses
            ?.map { it.address }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
    }
}

/**
 * Service au premier plan pendant le partage : garde le serveur en vie quand l'application passe
 * en arrière-plan, et propose « Arrêter le partage » dans sa notification.
 */
class LocalShareService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            (application as? io.github.jmorille.zapette.ZapetteApp)?.container?.localShare?.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Partage vers la télévision", NotificationManager.IMPORTANCE_LOW),
        )
        val stop = PendingIntent.getService(
            this, 0, Intent(this, LocalShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val name = intent?.getStringExtra(EXTRA_NAME)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_share)
            .setContentTitle("Partage en cours")
            .setContentText(name?.let { "« $it » est servi sur le réseau local" } ?: "Fichier servi sur le réseau local")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Arrêter le partage", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "partage"
        private const val NOTIFICATION_ID = 41
        private const val ACTION_STOP = "io.github.jmorille.zapette.STOP_SHARE"
        private const val EXTRA_NAME = "name"

        fun start(context: Context, name: String) {
            val intent = Intent().setComponent(ComponentName(context, LocalShareService::class.java)).putExtra(EXTRA_NAME, name)
            runCatching { context.startForegroundService(intent) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LocalShareService::class.java))
        }
    }
}
