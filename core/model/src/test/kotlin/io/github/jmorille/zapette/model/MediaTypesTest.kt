package io.github.jmorille.zapette.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaTypesTest {
    @Test
    fun `mime type table driven`() {
        val cases = listOf(
            "http://nas/film.mp4" to "video/mp4",
            "http://nas/film.MKV" to "video/x-matroska",
            "http://nas/film.m4v" to "video/mp4",
            "http://nas/film.mov" to "video/quicktime",
            "http://nas/film.avi" to "video/x-msvideo",
            "http://nas/film.webm" to "video/webm",
            "http://nas/film.ts" to "video/mp2t",
            "https://cdn/live/index.m3u8?token=1" to "application/vnd.apple.mpegurl",
            "https://cdn/live/manifest.mpd#t=10" to "application/dash+xml",
            "http://nas/a.MP3#t=10" to "audio/mpeg",
            "http://nas/a.flac?x=1#y" to "audio/flac",
            "http://nas/a.m4a" to "audio/mp4",
            "http://nas/a.aac" to "audio/aac",
            "http://nas/a.ogg" to "audio/ogg",
            "http://nas/a.opus" to "audio/ogg",
            "http://nas/a.wav" to "audio/wav",
            "http://nas/photo.JPEG" to "image/jpeg",
            "http://nas/photo.png" to "image/png",
            "x.flac" to "audio/flac",
            "file:///sdcard/Music/a.mp3" to "audio/mpeg",
            "http://[fe80::1]:8200/media/1.mkv" to "video/x-matroska",
            "http://nas/dossier.v2/fichier.mp4" to "video/mp4",
            // Sans extension : l'hôte pointé ne doit pas être pris pour une extension.
            "http://nas.local/stream" to null,
            "http://nas.local" to null,
            "http://nas.local/" to null,
            "http://nas/dossier.v2/stream" to null,
            "http://nas/stream?format=film.mp4" to null,
            "http://nas/stream#a.mp3" to null,
            "http://nas/fichier.inconnu" to null,
            "http://nas/fichier." to null,
            "" to null,
        )
        cases.forEach { (url, expected) -> assertEquals(expected, MediaTypes.forUrl(url), "URL « $url »") }
    }

    @Test
    fun `extension ignores query fragment and case`() {
        assertEquals("m3u8", MediaTypes.extension("https://cdn/INDEX.M3U8?a=b.c#d.e"))
        assertEquals("mp3", MediaTypes.extension("ftp://h.example/a.mp3?x"))
        assertNull(MediaTypes.extension("ftp://h.example"))
        assertNull(MediaTypes.extension("http://nas.local/stream"))
    }

    @Test
    fun `forExtension is case insensitive`() {
        assertEquals("audio/mpeg", MediaTypes.forExtension("MP3"))
        assertNull(MediaTypes.forExtension(""))
    }

    @Test
    fun `family predicates`() {
        assertTrue(MediaTypes.isAudio("audio/flac"))
        assertTrue(MediaTypes.isVideo("video/mp4"))
        assertTrue(MediaTypes.isImage("image/png"))
        assertFalse(MediaTypes.isAudio(null))
        assertFalse(MediaTypes.isVideo("application/dash+xml"))
        assertFalse(MediaTypes.isImage("video/mp4"))
    }
}
