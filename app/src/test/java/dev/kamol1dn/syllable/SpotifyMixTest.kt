package dev.kamol1dn.syllable

import dev.kamol1dn.syllable.spotify.SpotifyMix
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64

/** Messages are built by hand in the layout the Spotify desktop client writes. */
class SpotifyMixTest {
    private val a = "spotify:track:" + "A".repeat(22)
    private val b = "spotify:track:" + "B".repeat(22)

    private fun ByteArrayOutputStream.varint(v: Long) {
        var x = v
        while (true) {
            val low = (x and 0x7F).toInt()
            x = x ushr 7
            if (x == 0L) { write(low); return }
            write(low or 0x80)
        }
    }

    private fun ByteArrayOutputStream.number(field: Int, v: Long) { varint((field shl 3).toLong()); varint(v) }
    private fun ByteArrayOutputStream.bytes(field: Int, v: ByteArray) { varint((field shl 3 or 2).toLong()); varint(v.size.toLong()); write(v) }
    private fun ByteArrayOutputStream.float(field: Int, v: Float) {
        varint((field shl 3 or 5).toLong())
        val bits = v.toRawBits()
        for (i in 0 until 4) write(bits ushr (8 * i) and 0xFF)
    }

    private fun recipe(): String {
        val overlap = ByteArrayOutputStream().apply {
            bytes(1, "row-a".toByteArray())
            number(3, 201_500)
            number(4, 12_000)
            number(5, 16_000)
            float(7, 1.04f)
            number(9, 1)
            bytes(10, a.toByteArray())
            bytes(11, b.toByteArray())
            float(16, 120f)
        }.toByteArray()
        val transition = ByteArrayOutputStream().apply {
            bytes(1, overlap)
            bytes(2, byteArrayOf(8, 3)) // a preset, which the lyrics don't need
            number(3, 1)
        }.toByteArray()
        return Base64.getEncoder().encodeToString(transition)
    }

    @Test
    fun decodesATransition() {
        val t = SpotifyMix.decode(recipe())!!
        assertEquals("A".repeat(22), t.trackAId)
        assertEquals("B".repeat(22), t.trackBId)
        assertEquals(201_500L, t.startAMs)
        assertEquals(12_000L, t.startBMs)
        assertEquals(16_000L, t.durationMs)
        assertEquals(1.04f, t.speedB, 0.0001f)
        assertTrue(t.beatmatched)
    }

    @Test
    fun rejectsWhatIsNotATransition() {
        assertNull(SpotifyMix.decode("not base64 at all!"))
        assertNull(SpotifyMix.decode(Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))))
        assertNull(SpotifyMix.decode(""))
    }

    @Test
    fun readsThemOffAPlaylist() {
        val body = """
        {"contents":{"items":[
          {"uri":"$a","attributes":{"formatAttributes":[{"key":"item.speed","value":"1"},{"key":"${SpotifyMix.RECIPE}","value":"${recipe()}"}]}},
          {"uri":"$b","attributes":{"formatAttributes":[{"key":"item.speed","value":"1"}]}},
          {"uri":"$b"}
        ]}}
        """
        val list = SpotifyMix.parsePlaylist(JSONObject(body))
        assertEquals(1, list.size)
        assertEquals(16_000L, list[0].durationMs)
    }
}
