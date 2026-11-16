package dev.kamol1dn.syllable.spotify

import dev.kamol1dn.syllable.lyrics.Net
import dev.kamol1dn.syllable.lyrics.catching
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Album cover addresses for Spotify tracks, from Spotify's public embed service. No sign-in needed. */
object SpotifyCover {
    private val known = ConcurrentHashMap<String, String>()

    suspend fun url(trackId: String): String? {
        known[trackId]?.let { return it }
        val body = catching {
            Net.get("https://open.spotify.com/oembed?url=" + Net.enc("https://open.spotify.com/track/$trackId"))
        }.getOrNull() ?: return null
        val thumb = catching { JSONObject(body).optString("thumbnail_url") }.getOrNull()?.ifEmpty { null } ?: return null
        return larger(thumb).also { known[trackId] = it }
    }

    /** The embed service links the 300 px cover; the same image exists at 640 px under another prefix. */
    fun larger(url: String): String = url.replace("ab67616d00001e02", "ab67616d0000b273")
}
