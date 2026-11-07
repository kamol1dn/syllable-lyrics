package dev.kamol1dn.syllable.lyrics

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max

/** Cleans titles that come from media notifications and scores how well a search result matches. */
object TextMatch {

    private val noiseInBrackets = Regex(
        """\s*[(\[][^)\]]*(official|video|audio|lyric|visuali[sz]er|remaster|live at|explicit|clean|hd|hq|4k|mv|m/v|from .*soundtrack)[^)\]]*[)\]]""",
        RegexOption.IGNORE_CASE,
    )
    private val featInBrackets = Regex("""\s*[(\[](feat\.?|ft\.?|featuring|with)\s[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)
    private val featTail = Regex("""\s+(feat\.?|ft\.?|featuring)\s.*$""", RegexOption.IGNORE_CASE)
    private val dashSuffix = Regex(
        """\s+-\s+(.*(remaster|version|edit|mix|mono|stereo|live|acoustic|radio).*)$""",
        RegexOption.IGNORE_CASE,
    )
    private val artistNoise = Regex("""(\s*-\s*topic$)|(vevo$)|(\s*official$)""", RegexOption.IGNORE_CASE)

    fun cleanArtist(artist: String): String {
        var a = artist.trim()
        a = artistNoise.replace(a, "").trim()
        return a
    }

    /** Main artist only ("A, B & C" -> "A"), used as a fallback search. */
    fun primaryArtist(artist: String): String =
        artist.split(Regex("""\s*(,|&| x | and |;|/| feat\.? | ft\.? )\s*""", RegexOption.IGNORE_CASE))
            .firstOrNull()?.trim()?.ifEmpty { null } ?: artist

    /**
     * Returns (title, artist) cleaned. Handles YouTube-style "Artist - Title (Official Video)" titles
     * where the notification artist is a channel name.
     */
    fun clean(title: String, artist: String): Pair<String, String> {
        var t = title.trim()
        var a = cleanArtist(artist)
        t = noiseInBrackets.replace(t, "")
        t = featInBrackets.replace(t, "")
        t = dashSuffix.replace(t, "")
        val dash = t.indexOf(" - ")
        if (dash > 0) {
            val left = t.substring(0, dash).trim()
            val right = t.substring(dash + 3).trim()
            val nl = normalize(left)
            val na = normalize(a)
            // "Artist - Title": the left half names the artist (or the notification gave none).
            if (right.isNotEmpty() && (na.isEmpty() || (nl.isNotEmpty() && (nl.contains(na) || na.contains(nl))))) {
                a = left
                t = right
            }
        }
        t = featTail.replace(t, "")
        t = t.replace(Regex("""\s+"""), " ").trim().trim('"', '\'', '“', '”')
        return t to a
    }

    fun normalize(s: String): String {
        val n = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")
        return n.replace("&", "and").replace(Regex("""[^\p{L}\p{N}]+"""), "")
    }

    /** 0..1 similarity based on longest common subsequence over normalised strings. */
    fun similarity(a: String, b: String): Double {
        val x = normalize(a)
        val y = normalize(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        if (x.contains(y) || y.contains(x)) return 0.85 + 0.15 * minOf(x.length, y.length) / max(x.length, y.length)
        val dp = IntArray(y.length + 1)
        for (i in 1..x.length) {
            var prev = 0
            for (j in 1..y.length) {
                val tmp = dp[j]
                dp[j] = if (x[i - 1] == y[j - 1]) prev + 1 else max(dp[j], dp[j - 1])
                prev = tmp
            }
        }
        return 2.0 * dp[y.length] / (x.length + y.length)
    }

    /** Overall match score for a candidate. Returns -1 when the candidate is clearly a different song. */
    fun score(
        query: TrackQuery,
        title: String,
        artist: String,
        durationMs: Long?,
    ): Double {
        val ts = similarity(query.title, title)
        val artistScore = max(
            similarity(query.artist, artist),
            similarity(primaryArtist(query.artist), primaryArtist(artist)),
        )
        if (ts < 0.6 || artistScore < 0.45) return -1.0
        var s = ts * 0.6 + artistScore * 0.4
        val qd = query.durationMs
        if (qd != null && qd > 0 && durationMs != null && durationMs > 0) {
            val diff = abs(qd - durationMs)
            when {
                diff <= 2_500 -> s += 0.15
                diff <= 6_000 -> s += 0.0
                diff <= 15_000 -> s -= 0.2
                else -> return -1.0
            }
        }
        return s
    }
}
