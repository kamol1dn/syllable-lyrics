package dev.kamol1dn.syllable.lyrics

import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Spicy Lyrics developer API (https://developers.spicylyrics.org). One request returns the best
 * sync it has: a community sync, then Apple Music, then Spotify. Community syncs carry background
 * vocals and duet sides. Needs an API key; without one this provider is skipped.
 */
class SpicyLyricsProvider(
    private val apiKey: () -> String?,
    /** Spotify track id from another lookup (a Spotify sign-in), tried before Spicy's own search. */
    private val spotifyIdLookup: suspend (TrackQuery) -> String? = { null },
) : LyricsProvider {
    override val name = "Spicy Lyrics"

    private companion object {
        const val BASE = "https://api.spicylyrics.org"
        val SPOTIFY_ID = Regex("^[0-9A-Za-z]{22}$")
    }

    override suspend fun fetch(query: TrackQuery): Lyrics? {
        val key = apiKey()?.trim()?.ifEmpty { null } ?: return null
        val id = query.spotifyTrackId?.takeIf { SPOTIFY_ID.matches(it) }
            ?: runCatching { spotifyIdLookup(query) }.getOrNull()
            ?: search(key, query)
            ?: return null
        val body = get(key, "$BASE/v1/lyrics/$id") ?: return null
        return parse(JSONObject(body))
    }

    /** GET with one retry when the API says it is busy (503). 404 gives null. */
    private suspend fun get(key: String, url: String): String? {
        repeat(2) { attempt ->
            try {
                return Net.get(url, "Authorization" to "Bearer $key", "Accept" to "application/json")
            } catch (e: IOException) {
                if (attempt == 0 && e.message?.contains("HTTP 503") == true) delay(2_000) else throw e
            }
        }
        return null
    }

    private suspend fun search(key: String, query: TrackQuery): String? {
        val url = buildString {
            append("$BASE/v1/tracks/search?limit=3")
            append("&title=").append(Net.enc(query.title))
            if (query.artist.isNotBlank()) append("&artist=").append(Net.enc(query.artist))
            query.album?.takeIf { it.isNotBlank() }?.let { append("&album=").append(Net.enc(it)) }
            query.durationMs?.takeIf { it > 0 }?.let { append("&duration=").append("%.3f".format(java.util.Locale.US, it / 1000.0)) }
        }
        val body = get(key, url) ?: return null
        val results = JSONObject(body).optJSONObject("Body")?.optJSONArray("results") ?: return null
        val first = results.optJSONObject(0) ?: return null
        // A different version of a song has different timing, so only take confident matches.
        return first.optString("id").takeIf { first.optString("tier") in setOf("exact", "likely") && it.isNotEmpty() }
    }

    fun parse(root: JSONObject): Lyrics? {
        val b = root.optJSONObject("Body") ?: root
        val label = when (b.optString("source")) {
            "spicy_lyrics" -> "Spicy Lyrics"
            "apple_music" -> "Apple Music via Spicy Lyrics"
            "spotify" -> "Spotify via Spicy Lyrics"
            else -> "Spicy Lyrics"
        }
        val credits = credits(b)
        val lyrics = when (b.optString("Type")) {
            "Syllable" -> syllableLyrics(b.optJSONArray("Content") ?: return null, label)
            "Line" -> lineLyrics(b.optJSONArray("Content") ?: return null, label)
            "Static" -> {
                val arr = b.optJSONArray("Lines") ?: return null
                val text = (0 until arr.length()).joinToString("\n") { arr.optJSONObject(it)?.optString("Text").orEmpty() }
                LrcParser.plain(text, label)
            }
            else -> null
        }
        return lyrics?.takeIf { !it.isEmpty }?.copy(credits = credits)
    }

    private fun credits(b: JSONObject): List<Credit> {
        val a = b.optJSONObject("UploadAttribution") ?: return emptyList()
        return buildList {
            a.optJSONObject("Uploader")?.let { add(Credit("uploaded by", it.optString("username"), it.optString("url").ifEmpty { null })) }
            a.optJSONObject("Maker")?.let { add(Credit("made by", it.optString("username"), it.optString("url").ifEmpty { null })) }
        }.filter { it.name.isNotBlank() }
    }

    private fun ms(o: JSONObject, key: String): Long = Math.round(o.optDouble(key, 0.0) * 1000)

    /** Syllables become words; a trailing space marks the end of a word unless the next syllable continues it. */
    private fun syllables(arr: JSONArray?): List<LyricWord> {
        if (arr == null) return emptyList()
        val out = ArrayList<LyricWord>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val text = s.optString("Text")
            if (text.isEmpty()) continue
            val joined = s.optBoolean("IsPartOfWord", false) || i == arr.length() - 1
            val start = ms(s, "StartTime")
            out += LyricWord(if (joined) text else "$text ", start, maxOf(ms(s, "EndTime"), start + 40))
        }
        return out
    }

    private fun syllableLyrics(content: JSONArray, label: String): Lyrics? {
        val lines = (0 until content.length()).mapNotNull { i ->
            val c = content.optJSONObject(i) ?: return@mapNotNull null
            val lead = c.optJSONObject("Lead") ?: return@mapNotNull null
            val words = syllables(lead.optJSONArray("Syllables"))
            if (words.isEmpty()) return@mapNotNull null
            val bgGroups = c.optJSONArray("Background")
            val background = buildList<LyricWord> {
                if (bgGroups != null) for (g in 0 until bgGroups.length()) {
                    val ws = syllables(bgGroups.optJSONObject(g)?.optJSONArray("Syllables"))
                    if (ws.isEmpty()) continue
                    // Separate background phrases with a space.
                    if (isNotEmpty() && !last().text.endsWith(" ")) set(lastIndex, last().copy(text = last().text + " "))
                    addAll(ws)
                }
            }
            val start = minOf(ms(lead, "StartTime").takeIf { it > 0 } ?: words.first().startMs, words.first().startMs)
            val end = maxOf(ms(lead, "EndTime"), words.last().endMs, background.lastOrNull()?.endMs ?: 0L)
            LyricLine(start, end, words, background, c.optBoolean("OppositeAligned", false))
        }
        return Lyrics(lines, SyncType.WORD, label)
    }

    private fun lineLyrics(content: JSONArray, label: String): Lyrics? {
        data class Raw(val text: String, val start: Long, val end: Long, val opposite: Boolean)
        val raws = (0 until content.length()).mapNotNull { i ->
            val c = content.optJSONObject(i) ?: return@mapNotNull null
            Raw(c.optString("Text"), ms(c, "StartTime"), ms(c, "EndTime"), c.optBoolean("OppositeAligned", false))
                .takeIf { it.text.isNotBlank() }
        }
        val lines = raws.mapIndexed { i, r ->
            val next = raws.getOrNull(i + 1)?.start ?: (r.start + 6_000)
            val end = if (r.end > r.start) r.end else next
            LrcParser.estimateLine(r.text, r.start, end).copy(oppositeAligned = r.opposite)
        }
        return Lyrics(lines, SyncType.LINE, label)
    }
}
