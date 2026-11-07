package dev.kamol1dn.syllable

import android.content.SharedPreferences
import dev.kamol1dn.syllable.lyrics.LrcParser
import dev.kamol1dn.syllable.lyrics.MusixmatchProvider
import dev.kamol1dn.syllable.lyrics.NetEaseProvider
import dev.kamol1dn.syllable.lyrics.SyncType
import dev.kamol1dn.syllable.lyrics.TextMatch
import dev.kamol1dn.syllable.spotify.SpotifyLyricsProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class LyricsParsingTest {

    @Test
    fun lineLrcGetsEstimatedWords() {
        val l = LrcParser.parse("[ti:x]\n[00:01.00]Hello there world\n[00:04.50]Second line\n", "t")!!
        assertEquals(SyncType.LINE, l.sync)
        assertEquals(2, l.lines.size)
        val w = l.lines[0].words
        assertEquals(listOf("Hello ", "there ", "world"), w.map { it.text })
        assertEquals(1000L, w.first().startMs)
        assertTrue(w.zipWithNext().all { (a, b) -> a.endMs <= b.startMs + 1 })
        assertTrue(w.last().endMs <= 4500)
    }

    @Test
    fun enhancedLrcHasWordTiming() {
        val l = LrcParser.parse("[00:10.00]<00:10.00>One <00:10.40>two <00:11.00>three<00:12.00>\n[00:13.00]Next", "t")!!
        assertEquals(SyncType.WORD, l.sync)
        val w = l.lines[0].words
        assertEquals(listOf("One ", "two ", "three"), w.map { it.text })
        assertEquals(listOf(10_000L, 10_400L, 11_000L), w.map { it.startMs })
        assertEquals(12_000L, w.last().endMs)
    }

    @Test
    fun netEaseYrc() {
        val yrc = """{"t":0,"c":[{"tx":"作词: "},{"tx":"X"}]}
[12000,2000](12000,500,0)I (12500,700,0)love (13200,800,0)you
[15000,1000](15000,1000,0)Yeah"""
        val l = NetEaseProvider().parseYrc(yrc)!!
        assertEquals(2, l.lines.size)
        assertEquals("I love you", l.lines[0].text)
        assertEquals(12_500L, l.lines[0].words[1].startMs)
        assertEquals(13_200L, l.lines[0].words[1].endMs)
    }

    @Test
    fun musixmatchRichsync() {
        val body = """[{"ts":5.0,"te":7.0,"l":[{"c":"Hello","o":0},{"c":" ","o":0.5},{"c":"world","o":0.6}],"x":"Hello world"}]"""
        val prefs = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, _, _ -> null } as SharedPreferences
        val l = MusixmatchProvider(prefs).parseRichsync(body)!!
        assertEquals("Hello world", l.lines[0].text)
        assertEquals(listOf(5_000L, 5_600L), l.lines[0].words.map { it.startMs })
        assertEquals(7_000L, l.lines[0].words[1].endMs)
    }

    @Test
    fun spotifySyllables() {
        val json = JSONObject("""{"lyrics":{"syncType":"SYLLABLE_SYNCED","lines":[
          {"startTimeMs":"1000","words":"Hello world","syllables":[{"startTimeMs":"1000","numChars":"6"},{"startTimeMs":"1500","numChars":"5"}],"endTimeMs":"2200"},
          {"startTimeMs":"3000","words":"Line two","syllables":[],"endTimeMs":"0"}]}}""")
        val l = SpotifyLyricsProvider().parse(json)!!
        assertEquals(SyncType.WORD, l.sync)
        assertEquals(listOf("Hello ", "world"), l.lines[0].words.map { it.text })
        assertEquals(2_200L, l.lines[0].words[1].endMs)
        assertEquals("Line two", l.lines[1].text)
    }

    @Test
    fun cleansYoutubeStyleTitles() {
        assertEquals("Blinding Lights" to "The Weeknd", TextMatch.clean("The Weeknd - Blinding Lights (Official Video)", "TheWeekndVEVO"))
        assertEquals("Here Comes the Sun" to "The Beatles", TextMatch.clean("Here Comes the Sun - Remastered 2009", "The Beatles"))
        assertEquals("Song" to "Artist", TextMatch.clean("Song (feat. Someone)", "Artist - Topic"))
    }
}
