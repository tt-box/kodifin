package io.github.ttbox.kodifin.kodi

import io.github.ttbox.kodifin.model.Chapter
import org.junit.Assert.assertEquals
import org.junit.Test

class KodiChaptersTest {
    @Test
    fun `chapter percents become positions in ms`() {
        assertEquals(
            listOf(Chapter(0), Chapter(250_000), Chapter(500_000)),
            KodiController.parseChapterPercents("0.00, 50,25 ,abc,120", 1_000_000),
        )
        assertEquals(emptyList<Chapter>(), KodiController.parseChapterPercents("10,20", 0))
        assertEquals(emptyList<Chapter>(), KodiController.parseChapterPercents("", 1000))
    }
}
