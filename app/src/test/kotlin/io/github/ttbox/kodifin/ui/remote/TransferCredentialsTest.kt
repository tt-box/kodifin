package io.github.ttbox.kodifin.ui.remote

import io.github.ttbox.kodifin.model.TransferableMedia
import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class TransferCredentialsTest {
    @Test
    fun `une adresse Kodi avec identifiants est signalee`() {
        assertTrue(TransferableMedia("http://kodi:secret@bragi:8080/vfs/film.mkv").carriesCredentials())
    }

    @Test
    fun `la variante convertie est aussi examinee`() {
        val media = TransferableMedia("http://bragi:8080/a.mkv", transcodedUrl = "http://kodi:pw@bragi:8080/b.mp4")
        assertTrue(media.carriesCredentials())
    }

    @Test
    fun `une adresse sans identifiants ou invalide ne l'est pas`() {
        assertFalse(TransferableMedia("http://bragi:8096/Videos/1/stream?api_key=x").carriesCredentials())
        assertFalse(TransferableMedia("pas une url ::").carriesCredentials())
    }
}
