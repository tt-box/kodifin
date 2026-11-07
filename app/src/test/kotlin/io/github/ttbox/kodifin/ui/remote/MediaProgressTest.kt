package io.github.ttbox.kodifin.ui.remote

import io.github.ttbox.kodifin.R
import io.github.ttbox.kodifin.model.Chapter
import io.github.ttbox.kodifin.text.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaProgressTest {
    private val chapters = listOf(Chapter(0, "Intro"), Chapter(60_000), Chapter(300_000, "Fin"))

    @Test
    fun `current chapter is the last one started`() {
        assertEquals(0, chapterAt(chapters, 0))
        assertEquals(1, chapterAt(chapters, 299_999))
        assertEquals(2, chapterAt(chapters, 400_000))
        assertNull(chapterAt(listOf(Chapter(10_000)), 5_000))
    }

    @Test
    fun `chapter label shows rank and optional name`() {
        assertEquals(UiText.Res(R.string.remote_chapter_named, 1, 3, "Intro"), chapterLabel(chapters, 0))
        assertEquals(UiText.Res(R.string.remote_chapter, 2, 3), chapterLabel(chapters, 1))
    }
}
