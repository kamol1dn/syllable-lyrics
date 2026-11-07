package dev.kamol1dn.syllable.lyrics

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Musixmatch desktop-app API. "Richsync" lyrics carry per-word timing; falls back to line-synced
 * subtitles. Uses an anonymous user token (cached; Musixmatch sometimes rate-limits these).
 */
class MusixmatchProvider(private val prefs: SharedPreferences) : LyricsProvider {
    override val name = "Musixmatch"

    private val base = "https://apic-desktop.musixmatch.com/ws/1.1/"
    private val headers = arrayOf(
        "Cookie" to "AWSELBCORS=0; AWSELB=0",
        "Origin" to "musixmatch.com",
    )

    private suspend fun token(forceNew: Boolean = false): String? {
        val cached = prefs.getString(KEY_TOKEN, null)
        val age = System.currentTimeMillis() - prefs.getLong(KEY_TOKEN_TIME, 0)
        if (!forceNew && cached != null && age < 6 * 3600_000L) return cached
        val body = Net.get("${base}token.get?app_id=web-desktop-app-v1.0", *headers) ?: return null
        val msg = JSONObject(body).optJSONObject("message") ?: return null
        val tok = msg.optJSONObject("body")?.optString("user_token")
        if (tok.isNullOrBlank() || tok.startsWith("UpgradeOnly")) return null
        prefs.edit().putString(KEY_TOKEN, tok).putLong(KEY_TOKEN_TIME, System.currentTimeMillis()).apply()
        return tok
    }

    private suspend fun call(method: String, params: String): JSONObject? {
        repeat(2) { attempt ->
            val tok = token(forceNew = attempt > 0) ?: return null
            val body = Net.get("$base$method?format=json&app_id=web-desktop-app-v1.0&usertoken=$tok&$params", *headers)
                ?: return null
            val msg = JSONObject(body).optJSONObject("message") ?: return null
            val status = msg.optJSONObject("header")?.optInt("status_code") ?: 0
            if (status == 401) {
                prefs.edit().remove(KEY_TOKEN).apply()
                return@repeat
            }
            if (status != 200) return null
            return msg.optJSONObject("body")
        }
        return null
    }

    override suspend fun fetch(query: TrackQuery): Lyrics? {
        val params = buildString {
            append("q_track=").append(Net.enc(query.title))
            append("&q_artist=").append(Net.enc(query.artist))
            query.durationMs?.takeIf { it > 0 }?.let { append("&q_duration=").append(it / 1000) }
        }
        val track = call("matcher.track.get", params)?.optJSONObject("track") ?: return null
        val score = TextMatch.score(
            query, track.optString("track_name"), track.optString("artist_name"),
            track.optLong("track_length") * 1000,
        )
        if (score < 0) return null
        if (track.optInt("instrumental") == 1) return null
        val id = track.optLong("track_id")

        if (track.optInt("has_richsync") == 1) {
            val rs = call("track.richsync.get", "track_id=$id")
                ?.optJSONObject("richsync")?.optString("richsync_body")
            if (!rs.isNullOrBlank()) parseRichsync(rs)?.let { return it }
        }
        if (track.optInt("has_subtitles") == 1) {
            val sub = call("track.subtitle.get", "track_id=$id&subtitle_format=lrc")
                ?.optJSONObject("subtitle")?.optString("subtitle_body")
            if (!sub.isNullOrBlank()) LrcParser.parse(sub, name)?.let { return it }
        }
        return null
    }

    fun parseRichsync(body: String): Lyrics? {
        val arr = JSONArray(body)
        val lines = mutableListOf<LyricLine>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val ts = (o.optDouble("ts") * 1000).toLong()
            val te = (o.optDouble("te") * 1000).toLong()
            val chars = o.optJSONArray("l") ?: continue
            // Merge character groups into words; a " " entry ends the previous word.
            data class Piece(val text: String, val start: Long)
            val pieces = (0 until chars.length()).map {
                val c = chars.getJSONObject(it)
                Piece(c.optString("c"), ts + (c.optDouble("o") * 1000).toLong())
            }
            val words = mutableListOf<LyricWord>()
            var idx = 0
            while (idx < pieces.size) {
                val p = pieces[idx]
                if (p.text.isBlank()) {
                    if (words.isNotEmpty()) words[words.lastIndex] = words.last().copy(text = words.last().text + " ")
                    idx++
                    continue
                }
                val nextStart = pieces.drop(idx + 1).firstOrNull()?.start ?: te
                words += LyricWord(p.text, p.start, maxOf(nextStart, p.start + 60))
                idx++
            }
            if (words.isEmpty()) continue
            lines += LyricLine(ts, maxOf(te, words.last().endMs), words)
        }
        if (lines.isEmpty()) return null
        return Lyrics(lines, SyncType.WORD, name)
    }

    companion object {
        private const val KEY_TOKEN = "mxm_token"
        private const val KEY_TOKEN_TIME = "mxm_token_time"
    }
}
