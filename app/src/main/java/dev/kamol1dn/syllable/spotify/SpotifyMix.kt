package dev.kamol1dn.syllable.spotify

import dev.kamol1dn.syllable.lyrics.Net
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * How one song runs into the next in a mixed Spotify playlist: from [startAMs] in the first song,
 * the second plays over it from its own [startBMs], for [durationMs].
 */
data class MixTransition(
    val trackAId: String,
    val trackBId: String,
    val startAMs: Long,
    val startBMs: Long,
    val durationMs: Long,
    /** The second song's tempo while the two are matched, 1 for unchanged. */
    val speedB: Float,
    val beatmatched: Boolean,
) {
    /** Names the two songs, to tell one transition from another. */
    val pair: String get() = "$trackAId>$trackBId"
}

/**
 * Reads the transitions of a mixed playlist. Spotify keeps each one on the playlist entry of the
 * song it leads out of, as a base64 protobuf under [RECIPE], and only shows them when the playlist
 * is asked for through its "mix" lens. The layout was read from the desktop client, which has no
 * public description of it, so everything here is tolerant of surprises.
 *
 * The requests throw on network and HTTP errors, so a caller can tell a refusal from an empty answer.
 */
object SpotifyMix {
    /** A transition the listener set by hand. */
    const val RECIPE = "automix.auto_transition_recipe"
    /** One Spotify worked out itself. */
    const val BACKEND = "automix.backend_auto_transition"

    private const val SPCLIENT = "https://spclient.wg.spotify.com/playlist/v2"
    private val TRACK_ID = Regex("""spotify:track:([A-Za-z0-9]{22})""")

    private suspend fun get(url: String, token: String, lens: Boolean = false): String? {
        val b = Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .header("User-Agent", Net.BROWSER_UA)
            .header("Accept", "application/json")
            .header("app-platform", "WebPlayer")
        // Transitions are only part of the playlist as seen through its "mix" lens.
        if (lens) b.header("spotify-apply-lenses", "mix")
        return Net.fetch(b.build())
    }

    /** The transitions of the playlist [playlistId]. Empty for a playlist that isn't mixed. */
    suspend fun transitions(token: String, playlistId: String): List<MixTransition> =
        get("$SPCLIENT/playlist/$playlistId", token, lens = true)?.let { parsePlaylist(JSONObject(it)) }.orEmpty()

    fun parsePlaylist(root: JSONObject): List<MixTransition> {
        val items = root.optJSONObject("contents")?.optJSONArray("items") ?: return emptyList()
        return buildList {
            for (i in 0 until items.length()) {
                val attrs = items.optJSONObject(i)?.optJSONObject("attributes") ?: continue
                val list = attrs.optJSONArray("formatAttributes") ?: attrs.optJSONArray("format_attributes") ?: continue
                // A hand-made transition wins over Spotify's own for the same pair.
                (value(list, RECIPE) ?: value(list, BACKEND))?.let(::decode)?.let { add(it) }
            }
        }
    }

    private fun value(list: JSONArray, key: String): String? {
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            if (o.optString("key") == key) return o.optString("value").ifEmpty { null }
        }
        return null
    }

    /** Decodes one base64 transition. Null when it isn't one, or names no pair of tracks. */
    fun decode(base64: String): MixTransition? = runCatching {
        val bytes = Base64.getDecoder().decode(base64.trim())
        val overlap = Proto(bytes).fields()[1]?.bytes ?: return null
        val f = Proto(overlap).fields()
        val a = TRACK_ID.find(f[10]?.text().orEmpty())?.groupValues?.get(1) ?: return null
        val b = TRACK_ID.find(f[11]?.text().orEmpty())?.groupValues?.get(1) ?: return null
        val duration = f[5]?.number ?: 0
        if (duration <= 0) return null
        MixTransition(
            trackAId = a,
            trackBId = b,
            startAMs = f[3]?.number ?: 0,
            startBMs = f[4]?.number ?: 0,
            durationMs = duration,
            speedB = f[7]?.let { Float.fromBits(it.number.toInt()) }?.takeIf { it > 0.5f && it < 2f } ?: 1f,
            beatmatched = f[9]?.number == 1L,
        )
    }.getOrNull()

    /** One protobuf field: a number (varint or fixed), or the bytes of a string or nested message. */
    private class Field(val number: Long, val bytes: ByteArray?) {
        fun text() = bytes?.toString(Charsets.UTF_8)
    }

    /** Just enough of the protobuf wire format to pick fields out by number. */
    private class Proto(private val data: ByteArray) {
        private var pos = 0

        fun fields(): Map<Int, Field> {
            val out = HashMap<Int, Field>()
            while (pos < data.size) {
                val tag = varint()
                val field = (tag ushr 3).toInt()
                when ((tag and 7).toInt()) {
                    0 -> out[field] = Field(varint(), null)
                    1 -> out[field] = Field(fixed(8), null)
                    5 -> out[field] = Field(fixed(4), null)
                    2 -> {
                        val n = varint().toInt()
                        require(n >= 0 && pos + n <= data.size)
                        out[field] = Field(0, data.copyOfRange(pos, pos + n))
                        pos += n
                    }
                    else -> throw IllegalArgumentException("unsupported wire type")
                }
            }
            return out
        }

        private fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = data[pos++].toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                require(shift < 64)
            }
        }

        private fun fixed(n: Int): Long {
            var result = 0L
            for (i in 0 until n) result = result or ((data[pos++].toLong() and 0xFF) shl (8 * i))
            return result
        }
    }
}
