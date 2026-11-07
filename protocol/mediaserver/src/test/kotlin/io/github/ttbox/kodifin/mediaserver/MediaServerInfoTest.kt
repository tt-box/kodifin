package io.github.ttbox.kodifin.mediaserver

import io.github.ttbox.kodifin.mediaserver.MediaServerController.Companion.toEntry
import io.github.ttbox.kodifin.mediaserver.MediaServerController.Companion.toInfo
import io.github.ttbox.kodifin.model.Capability
import io.github.ttbox.kodifin.model.ControlFeature
import io.github.ttbox.kodifin.model.Device
import io.github.ttbox.kodifin.model.DeviceKind
import io.github.ttbox.kodifin.model.UiCommand
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Fiches détaillées et fonctions masquées selon les commandes acceptées par le client piloté. */
class MediaServerInfoTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun controller() = MediaServerController.create(
        Device("srv", "Serveur", DeviceKind.JELLYFIN, server.hostName, server.port, mapOf("token" to "tok", "userId" to "u1", "sessionId" to "s1")),
        OkHttpClient(),
        "dev-1",
    )

    private fun json(body: String) = server.enqueue(MockResponse.Builder().body(body).build())

    private val episode = ItemDto(
        id = "e2", name = "I'm Home", type = "Episode", seriesName = "Attack on Titan", parentIndexNumber = 2, indexNumber = 2,
        runTimeTicks = 14_400_000_000, overview = "  Le bataillon rentre.  ", communityRating = 8.24, officialRating = "TV-14",
        premiereDate = "2017-04-08T00:00:00.0000000Z", genres = listOf("Animation", ""), studios = listOf(NameDto("MAPPA")),
        people = listOf(
            PersonDto("Yuki Kaji", "Eren (voix)", "Actor"), PersonDto("Invité", null, "GuestStar"),
            PersonDto("Tetsurō Araki", type = "Director"), PersonDto("Yasuko Kobayashi", type = "Writer"), PersonDto("", type = "Actor"),
        ),
        mediaStreams = listOf(
            MediaStreamDto("Video", 0, "1080p HEVC"),
            MediaStreamDto("Audio", 1, "Japonais AAC"),
            MediaStreamDto("Audio", 2, "Français AAC 5.1", isDefault = true),
        ),
    )

    @Test
    fun `item info maps overview, dates, people and streams`() {
        val info = episode.toInfo()
        assertEquals("Le bataillon rentre.", info.overview)
        assertEquals("Attack on Titan", info.seriesTitle)
        assertEquals(2, info.season)
        assertEquals(2, info.episode)
        assertEquals("2017-04-08", info.airDate)
        assertEquals(1_440_000L, info.runtimeMs)
        assertEquals(8.24, info.rating!!, 0.0)
        assertEquals("TV-14", info.officialRating)
        assertEquals(listOf("Animation"), info.genres)
        assertEquals(listOf("MAPPA"), info.studios)
        assertEquals(listOf("Tetsurō Araki"), info.directors)
        assertEquals(listOf("Yasuko Kobayashi"), info.writers)
        assertEquals(listOf("Yuki Kaji (Eren (voix))", "Invité"), info.cast)
        assertEquals("1080p HEVC", info.video)
        assertEquals("Français AAC 5.1", info.audio)
        assertEquals("Le bataillon rentre.", episode.toEntry().overview)
    }

    @Test
    fun `movie info has no series fields and invalid dates are dropped`() {
        val info = ItemDto(id = "m", name = "", type = "Movie", productionYear = 2008, premiereDate = "inconnue", overview = " ").toInfo()
        assertEquals("m", info.title)
        assertNull(info.seriesTitle)
        assertNull(info.episode)
        assertNull(info.airDate)
        assertNull(info.overview)
        assertEquals(2008, info.year)
    }

    @Test
    fun `item info is fetched, unknown items give null`() = runTest {
        val c = controller()
        json("""{"Id":"e2","Name":"I'm Home","Type":"Episode","Overview":"Résumé"}""")
        assertEquals("Résumé", c.itemInfo("e2")!!.overview)
        server.enqueue(MockResponse.Builder().code(404).build())
        assertNull(c.itemInfo("absent"))
    }

    @Test
    fun `now playing info follows the session item`() = runTest {
        val c = controller()
        json("""[{"Id":"s1","Client":"Web","DeviceName":"Salon","SupportsRemoteControl":true,"NowPlayingItem":{"Id":"e2","Name":"I'm Home"},"PlayState":{}}]""")
        json("""{"Id":"e2","Name":"I'm Home","Type":"Episode","Overview":"Résumé"}""")
        assertEquals("Résumé", c.nowPlayingInfo()!!.overview)
        json("""[{"Id":"s1","Client":"Web","DeviceName":"Salon","SupportsRemoteControl":true,"PlayState":{}}]""")
        assertNull(c.nowPlayingInfo())
    }

    @Test
    fun `features follow the commands accepted by the controlled client`() = runTest {
        val c = controller()
        // Avant toute session connue : tout est permis, sauf ce que le serveur ne fait jamais.
        assertTrue(c.isAvailable(ControlFeature.NOTIFY))
        assertFalse(c.isAvailable(ControlFeature.CLEAN_LIBRARY))
        assertFalse(c.isAvailable(ControlFeature.QUEUE_EDIT))
        assertTrue(Capability.TEXT_INPUT in c.capabilities)

        // Lecteur sans interface : seulement la lecture et le volume.
        json(
            """[{"Id":"s1","Client":"Lecteur","DeviceName":"Salon","SupportsRemoteControl":true,"PlayState":{},""" +
                """"Capabilities":{"SupportedCommands":["VolumeUp","VolumeDown","SetVolume","ToggleMute","SetAudioStreamIndex"]}}]""",
        )
        c.playbackState().first()
        assertFalse(c.isAvailable(ControlFeature.NOTIFY))
        assertFalse(c.isAvailable(ControlFeature.PLAY_MODE))
        assertTrue(c.isAvailable(ControlFeature.TRACKS))
        assertTrue(c.isAvailable(ControlFeature.SCAN_LIBRARY))
        assertFalse(Capability.TEXT_INPUT in c.capabilities)
        assertFalse(Capability.NAVIGATION in c.capabilities)
        assertTrue(c.uiCommands.isEmpty())
        assertFalse(UiCommand.GUIDE in c.uiCommands)
    }
}
