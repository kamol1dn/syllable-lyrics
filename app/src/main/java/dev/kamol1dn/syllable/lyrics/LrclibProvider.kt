package dev.kamol1dn.syllable.lyrics

import org.json.JSONArray
import org.json.JSONObject

/** lrclib.net — free, open, mostly line-synced (sometimes word-tagged) LRC. */
class LrclibProvider : LyricsProvider {
    override val name = "LRCLIB"

    override suspend fun fetch(query: TrackQuery): Lyrics? {
        val direct = buildString {
            append("https://lrclib.net/api/get?track_name=").append(Net.enc(query.title))
            append("&artist_name=").append(Net.enc(query.artist))
            query.album?.takeIf { it.isNotBlank() }?.let { append("&album_name=").append(Net.enc(it)) }
            query.durationMs?.takeIf { it > 0 }?.let { append("&duration=").append(it / 1000) }
        }
        runCatching { Net.get(direct) }.getOrNull()?.let { body ->
            fromRecord(JSONObject(body))?.let { return it }
        }

        val search = "https://lrclib.net/api/search?track_name=${Net.enc(query.title)}&artist_name=${Net.enc(query.artist)}"
        val arr = JSONArray(Net.get(search) ?: return null)
        var best: JSONObject? = null
        var bestScore = 0.0
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val score = TextMatch.score(
                query,
                o.optString("trackName"),
                o.optString("artistName"),
                (o.optDouble("duration", 0.0) * 1000).toLong(),
            ) + if (o.optString("syncedLyrics").isNotBlank()) 0.1 else 0.0
            if (score > bestScore) { bestScore = score; best = o }
        }
        return best?.let { fromRecord(it) }
    }

    private fun fromRecord(o: JSONObject): Lyrics? {
        if (o.optBoolean("instrumental")) return null
        val synced = o.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
        if (synced != null) LrcParser.parse(synced, name)?.let { return it }
        val plain = o.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
        return plain?.let { LrcParser.plain(it, name) }
    }
}
