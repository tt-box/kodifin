package io.github.ttbox.kodifin.kodi

import io.github.ttbox.kodifin.model.Device
import io.github.ttbox.kodifin.model.DeviceKind
import io.github.ttbox.kodifin.model.RemoteError
import io.github.ttbox.kodifin.model.RemoteException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Test
import kotlin.test.assertEquals

class KodiServerDescriptionTest {
    private val device = Device("k", "Salon", DeviceKind.KODI, "10.0.0.2")

    private class Transport(private val responses: Map<String, String>) : KodiTransport {
        override suspend fun call(method: String, params: JsonObject?): JsonElement =
            Json.parseToJsonElement(responses[method] ?: throw RemoteException(RemoteError.UnexpectedResponse("Kodi")))
    }

    private val version = KodiMethod.VERSION to """{"version":{"major":13,"minor":5,"patch":0}}"""

    @Test
    fun `describes application name, version and JSON-RPC version`() = runTest {
        val app = KodiMethod.APP_GET_PROPERTIES to
            """{"name":"Kodi","version":{"major":21,"minor":2,"revision":"x","tag":"stable"}}"""
        val c = KodiController(device, KodiClient(Transport(mapOf(version, app))))
        assertEquals("Kodi 21.2 (JSON-RPC 13.5.0)", c.serverDescription())
    }

    @Test
    fun `pre-release tag is shown`() = runTest {
        val app = KodiMethod.APP_GET_PROPERTIES to """{"name":"Kodi","version":{"major":22,"minor":0,"tag":"alpha2"}}"""
        val c = KodiController(device, KodiClient(Transport(mapOf(version, app))))
        assertEquals("Kodi 22.0 alpha2 (JSON-RPC 13.5.0)", c.serverDescription())
    }

    @Test
    fun `falls back to JSON-RPC version only`() = runTest {
        val c = KodiController(device, KodiClient(Transport(mapOf(version))))
        assertEquals("Kodi (JSON-RPC 13.5.0)", c.serverDescription())
    }
}
