package io.github.ttbox.kodifin.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceToStringTest {
    @Test
    fun `toString masks secrets but keeps other extras`() {
        val d = Device(
            "id", "thor", DeviceKind.JELLYFIN, "h", 8096,
            mapOf("token" to "T0K", "password" to "pw", "password_enc" to "ENC", "plex_token" to "P", "username" to "jerome", "tls_pin" to "AB:CD"),
        )
        val s = d.toString()
        listOf("T0K", "=pw", "ENC", "=P,").forEach { assertFalse(s, it in s) }
        assertTrue(s, "username=jerome" in s)
        assertTrue(s, "tls_pin=AB:CD" in s)
    }
}
