package dev.kamol1dn.syllable

import dev.kamol1dn.syllable.lyrics.SpicyLyricsProvider
import dev.kamol1dn.syllable.lyrics.SyncType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Payloads follow the shapes in the Spicy Lyrics API reference. */
class SpicyLyricsParsingTest {
    private val provider = SpicyLyricsProvider(apiKey = { null })

    @Test
    fun syllableSyncWithBackgroundAndDuet() {
        val body = """
        {"Body":{"Type":"Syllable","StartTime":7.3,"EndTime":20.0,"id":"x","ids":{"spotify":"x"},
          "source":"spicy_lyrics",
          "UploadAttribution":{"Uploader":{"id":"1","username":"spikerko","url":"https://spicylyrics.org/uid/1"},
                               "Maker":{"id":"2","username":"gc","url":"https://spicylyrics.org/uid/2"}},
          "Content":[
            {"Type":"Vocal","OppositeAligned":false,
             "Lead":{"StartTime":7.3,"EndTime":9.0,"Syllables":[
               {"Text":"Wan","StartTime":7.3,"EndTime":7.6,"IsPartOfWord":true},
               {"Text":"na","StartTime":7.6,"EndTime":7.9,"IsPartOfWord":false},
               {"Text":"dance","StartTime":7.9,"EndTime":9.0,"IsPartOfWord":false}]},
             "Background":[{"StartTime":8.5,"EndTime":9.6,"Syllables":[
               {"Text":"(oh","StartTime":8.5,"EndTime":9.0,"IsPartOfWord":false},
               {"Text":"yeah)","StartTime":9.0,"EndTime":9.6,"IsPartOfWord":false}]}]},
            {"Type":"Vocal","OppositeAligned":true,
             "Lead":{"StartTime":9.4,"EndTime":11.0,"Syllables":[
               {"Text":"Me","StartTime":9.4,"EndTime":9.8,"IsPartOfWord":false},
               {"Text":"too","StartTime":9.8,"EndTime":11.0,"IsPartOfWord":false}]}}
          ]},"Status":200,"Type":"object"}
        """
        val l = provider.parse(JSONObject(body))!!
        assertEquals(SyncType.WORD, l.sync)
        assertEquals("Spicy Lyrics", l.source)
        assertEquals(2, l.lines.size)

        val first = l.lines[0]
        assertEquals("Wanna dance", first.text)
        assertEquals(listOf("Wan", "na ", "dance"), first.words.map { it.text })
        assertEquals(7_300L, first.startMs)
        assertEquals("(oh yeah)", first.backgroundText)
        // The line lasts until its background vocals end, so it overlaps the next line.
        assertEquals(9_600L, first.endMs)
        assertTrue(first.endMs > l.lines[1].startMs)
        assertFalse(first.oppositeAligned)
        assertTrue(l.lines[1].oppositeAligned)

        assertEquals(listOf("uploaded by" to "spikerko", "made by" to "gc"), l.credits.map { it.label to it.name })
        assertEquals("https://spicylyrics.org/uid/1", l.credits[0].url)
    }

    @Test
    fun lineSyncFromAppleMusic() {
        val body = """
        {"Body":{"Type":"Line","id":"x","ids":{"spotify":"x"},"source":"apple_music","Content":[
          {"Type":"Vocal","Text":"First line here","StartTime":1.0,"EndTime":3.5,"OppositeAligned":false},
          {"Type":"Vocal","Text":"Second","StartTime":4.0,"EndTime":5.0,"OppositeAligned":true}]},"Status":200,"Type":"object"}
        """
        val l = provider.parse(JSONObject(body))!!
        assertEquals(SyncType.LINE, l.sync)
        assertEquals("Apple Music via Spicy Lyrics", l.source)
        assertTrue(l.credits.isEmpty())
        assertEquals("First line here", l.lines[0].text)
        assertEquals(1_000L, l.lines[0].startMs)
        assertTrue(l.lines[0].words.last().endMs <= 3_500L)
        assertTrue(l.lines[1].oppositeAligned)
    }

    @Test
    fun staticLyrics() {
        val body = """{"Body":{"Type":"Static","id":"x","ids":{"spotify":"x"},"source":"spotify",
            "Lines":[{"Text":"One"},{"Text":"Two"}]},"Status":200,"Type":"object"}"""
        val l = provider.parse(JSONObject(body))!!
        assertEquals(SyncType.NONE, l.sync)
        assertEquals(listOf("One", "Two"), l.lines.map { it.text })
    }
}
