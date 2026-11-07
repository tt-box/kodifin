package io.github.ttbox.kodifin.upnp

import io.github.ttbox.kodifin.model.UdpDatagram
import io.github.ttbox.kodifin.model.UdpDestination
import io.github.ttbox.kodifin.model.UdpOptions
import io.github.ttbox.kodifin.model.UdpSocket
import io.github.ttbox.kodifin.model.UdpSocketFactory
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.Assert.assertTrue
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

internal fun MockWebServer.next(): RecordedRequest =
    takeRequest(1, TimeUnit.SECONDS) ?: throw AssertionError("requête attendue sous une seconde")

/** Vérifie que [block] échoue avec une exception de type [T] et la renvoie. */
internal suspend inline fun <reified T : Throwable> expectFailure(block: suspend () -> Unit): T {
    val error = try {
        block()
        null
    } catch (t: Throwable) {
        t
    }
    assertTrue("${T::class.simpleName} attendue, obtenu : $error", error is T)
    return error as T
}
