package dev.kamol1dn.syllable

import dev.kamol1dn.syllable.spotify.SpotifyCanvas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpotifyCanvasTest {
    @Test
    fun picksTheVideoOutOfACanvasAnswer() {
        fun field(n: Int, text: String) = byteArrayOf((n shl 3 or 2).toByte(), text.length.toByte()) + text.toByteArray()
        fun canvas(url: String, type: Int) = (field(1, "id") + field(2, url) + byteArrayOf(0x20, type.toByte())).let {
            byteArrayOf(0x0A, it.size.toByte()) + it
        }
        assertEquals("https://c.example/a.mp4", SpotifyCanvas.parseCanvas(canvas("https://c.example/pic.jpg", 0) + canvas("https://c.example/a.mp4", 2)))
        assertNull(SpotifyCanvas.parseCanvas(canvas("https://c.example/pic.jpg", 0)))
        assertNull(SpotifyCanvas.parseCanvas(byteArrayOf()))
    }
}
