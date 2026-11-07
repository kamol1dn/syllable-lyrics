package dev.kamol1dn.syllable.spotify

import dev.kamol1dn.syllable.lyrics.LrcParser
import dev.kamol1dn.syllable.lyrics.Lyrics
import dev.kamol1dn.syllable.lyrics.LyricLine
import dev.kamol1dn.syllable.lyrics.LyricWord
import dev.kamol1dn.syllable.lyrics.LyricsProvider
import dev.kamol1dn.syllable.lyrics.Net
import dev.kamol1dn.syllable.lyrics.SyncType
import dev.kamol1dn.syllable.lyrics.TextMatch
import dev.kamol1dn.syllable.lyrics.TrackQuery
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/** Spotify's own lyrics (the ones shown in the Spotify app). Requires signing in under Settings. */
class SpotifyLyricsProvider : LyricsProvider {
    override val name = "Spotify"

    override suspend fun fetch(query: TrackQuery): Lyrics? {
        val token = SpotifyAuth.getToken() ?: return null
        return try {
            fetchWith(token, query)
        } catch (e: AuthException) {
            SpotifyAuth.invalidateToken()
            val fresh = SpotifyAuth.getToken() ?: return null
            fetchWith(fresh, query)
        }
    }

    private class AuthException : IOException("Spotify token rejected")

    private suspend fun authedGet(url: String, token: String, webPlayer: Boolean): String? {
        val b = Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .header("User-Agent", Net.BROWSER_UA)
            .header("Accept", "application/json")
        if (webPlayer) b.header("app-platform", "WebPlayer")
        return try {
            Net.fetch(b.build())
        } catch (e: IOException) {
            if (e.message?.contains("HTTP 401") == true) throw AuthException()
            throw e
        }
    }

    private suspend fun fetchWith(token: String, query: TrackQuery): Lyrics? {
        val id = query.spotifyTrackId ?: search(token, query) ?: return null
        val body = authedGet(
            "https://spclient.wg.spotify.com/color-lyrics/v2/track/$id?format=json&vocalRemoval=false&market=from_token",
            token, webPlayer = true,
        ) ?: return null
        if (body.isBlank()) return null
        return parse(JSONObject(body))
    }

    private suspend fun search(token: String, query: TrackQuery): String? {
        val q = Net.enc("track:${query.title} artist:${TextMatch.primaryArtist(query.artist)}")
        val body = authedGet("https://api.spotify.com/v1/search?q=$q&type=track&limit=8", token, webPlayer = false)
            ?: return null
        val items = JSONObject(body).optJSONObject("tracks")?.optJSONArray("items") ?: return null
        var best: String? = null
        var bestScore = 0.0
        for (i in 0 until items.length()) {
            val t = items.getJSONObject(i)
            val artists = t.optJSONArray("artists")
            val names = buildList {
                if (artists != null) for (j in 0 until artists.length()) add(artists.getJSONObject(j).optString("name"))
            }.joinToString(", ")
            val s = TextMatch.score(query, t.optString("name"), names, t.optLong("duration_ms"))
            if (s > bestScore) { bestScore = s; best = t.optString("id") }
        }
        return best
    }

    fun parse(root: JSONObject): Lyrics? {
        val ly = root.optJSONObject("lyrics") ?: return null
        val sync = ly.optString("syncType")
        val arr = ly.optJSONArray("lines") ?: return null
        data class Raw(val start: Long, val end: Long, val text: String, val syl: org.json.JSONArray?)
        val raws = (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Raw(
                o.optString("startTimeMs").toLongOrNull() ?: 0L,
                o.optString("endTimeMs").toLongOrNull() ?: 0L,
                o.optString("words"),
                o.optJSONArray("syllables"),
            )
        }.filter { it.text.isNotBlank() && it.text != "♪" }
        if (raws.isEmpty()) return null

        if (sync == "UNSYNCED") {
            return LrcParser.plain(raws.joinToString("\n") { it.text }, name)
        }
        var anyWord = false
        val lines = raws.mapIndexed { i, r ->
            val next = raws.getOrNull(i + 1)?.start ?: (r.start + 6_000)
            val words = r.syl?.let { syllables(r.text, r.start, if (r.end > r.start) r.end else next, it) }
            if (!words.isNullOrEmpty()) {
                anyWord = true
                LyricLine(r.start, maxOf(r.end, words.last().endMs), words)
            } else {
                LrcParser.estimateLine(r.text, r.start, next)
            }
        }
        return Lyrics(lines, if (anyWord) SyncType.WORD else SyncType.LINE, name)
    }

    /** Syllable entries carry a start time and either their text or a character count. */
    private fun syllables(text: String, lineStart: Long, lineEnd: Long, arr: org.json.JSONArray): List<LyricWord>? {
        if (arr.length() == 0) return null
        val out = mutableListOf<LyricWord>()
        var cursor = 0
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: return null
            val start = s.optString("startTimeMs").toLongOrNull() ?: return null
            val explicit = s.optString("words").ifEmpty { s.optString("text") }.ifEmpty { s.optString("syllable") }
            val count = s.optString("numChars").ifEmpty { s.optString("charsCount") }.toIntOrNull()
            val piece = when {
                explicit.isNotEmpty() -> explicit
                count != null -> text.substring(cursor.coerceAtMost(text.length), (cursor + count).coerceAtMost(text.length))
                else -> return null
            }
            cursor += piece.length
            val end = s.optString("endTimeMs").toLongOrNull()
            out += LyricWord(piece, start, end ?: 0L)
        }
        // Remaining text (e.g. trailing punctuation) sticks to the last syllable.
        if (cursor < text.length && out.isNotEmpty()) {
            out[out.lastIndex] = out.last().copy(text = out.last().text + text.substring(cursor))
        }
        for (i in out.indices) {
            if (out[i].endMs <= out[i].startMs) {
                val nextStart = out.getOrNull(i + 1)?.startMs ?: lineEnd
                out[i] = out[i].copy(endMs = maxOf(nextStart, out[i].startMs + 80))
            }
        }
        return out.ifEmpty { null }?.takeIf { it.first().startMs >= lineStart - 5_000 }
    }
}
