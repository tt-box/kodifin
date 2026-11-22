package io.github.ttbox.kodifin.cast

import io.github.ttbox.kodifin.model.Device
import io.github.ttbox.kodifin.model.DeviceKind
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList

/** Pochettes Cast : le Chromecast et le LAN seulement, jamais le CDN d'une application. */
class CastImagePolicyTest {
    private val requested = CopyOnWriteArrayList<String>()

    /** Intercepteur d'application : voit toute requête avant le réseau et répond lui-même. */
    private val http = OkHttpClient.Builder().addInterceptor { chain ->
        requested += chain.request().url.host
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(byteArrayOf(7).toResponseBody("image/jpeg".toMediaType())).build()
    }.build()

    private val resolver: (String) -> List<InetAddress> = { host ->
        when (host) {
            "jellyfin.maison.example" -> listOf(InetAddress.getByName("192.168.1.30"))
            "i.ytimg.com" -> listOf(InetAddress.getByName("142.250.74.110"))
            else -> throw UnknownHostException(host)
        }
    }

    private val device = Device("c", "Salon", DeviceKind.CHROMECAST, "192.168.1.40", 8009)

    @Test
    fun `public cdn artwork is not fetched`() = runTest {
        val fetch = CastController.lanImageFetcher(http, device, resolver)
        assertNull(fetch("https://i.ytimg.com/vi/abc/hqdefault.jpg"))
        assertNull(fetch("https://lh3.googleusercontent.com/x"))
        assertNull(fetch("http://8.8.8.8/a.jpg"))
        assertTrue(requested.isEmpty())
    }

    @Test
    fun `chromecast and lan artwork are fetched`() = runTest {
        val fetch = CastController.lanImageFetcher(http, device, resolver)
        assertArrayEquals(byteArrayOf(7), fetch("http://192.168.1.40:8008/icon.png"))
        assertArrayEquals(byteArrayOf(7), fetch("http://jellyfin.maison.example:8096/Items/1/Images/Primary"))
        assertEquals(listOf("192.168.1.40", "jellyfin.maison.example"), requested.toList())
    }
}
