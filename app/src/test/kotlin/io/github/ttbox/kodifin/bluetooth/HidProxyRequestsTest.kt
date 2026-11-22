package io.github.ttbox.kodifin.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HidProxyRequestsTest {

    @Test
    fun `stop then quick start keeps only the proxy of the last request`() {
        val requests = HidProxyRequests()
        val first = checkNotNull(requests.begin())
        requests.cancel()
        val second = checkNotNull(requests.begin())
        // Les deux proxys arrivent, dans un ordre quelconque : seul le second est gardé.
        assertFalse("premier proxy à rendre", requests.accept(first))
        assertTrue(requests.accept(second))
        assertFalse(requests.isCurrent(first))
        assertTrue(requests.isCurrent(second))
    }

    @Test
    fun `a proxy arriving after stop is refused, and a pending request is not duplicated`() {
        val requests = HidProxyRequests()
        val id = checkNotNull(requests.begin())
        assertNull("demande déjà en cours", requests.begin())
        requests.cancel()
        assertFalse(requests.accept(id))
        assertTrue("événements de l'ancien proxy encore courants tant qu'aucune demande ne suit", requests.isCurrent(id))
    }

    @Test
    fun `a failed request can be retried`() {
        val requests = HidProxyRequests()
        val id = checkNotNull(requests.begin())
        requests.failed(id)
        val retry = requests.begin()
        assertNotNull(retry)
        assertEquals(id + 1, retry)
        // Échec signalé pour une demande périmée : la demande courante reste active.
        requests.failed(id)
        assertTrue(requests.accept(checkNotNull(retry)))
    }
}
