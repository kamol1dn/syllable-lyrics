package dev.kamol1dn.syllable.spotify

import android.content.Context
import android.util.Log
import dev.kamol1dn.syllable.lyrics.Net
import dev.kamol1dn.syllable.lyrics.catching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Finds and downloads a song's Spotify Canvas, the short looping clip Spotify shows behind its own
 * player. Videos are kept in the app's cache folder.
 */
class SpotifyCanvas(context: Context) {
    private val dir = File(context.cacheDir, "canvas")
    /** Songs that were looked up, with their clip or null for none. */
    private val known = object : LinkedHashMap<String, File?>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, File?>?) = size > 100
    }

    private val _status = MutableStateFlow<String?>(null)
    /** What the last lookup came to, in words for the settings page. */
    val status: StateFlow<String?> = _status.asStateFlow()

    /** Whether [trackId] has been looked up, so [find] answers without asking anyone. */
    fun isKnown(trackId: String): Boolean = synchronized(known) { trackId in known && known[trackId]?.exists() != false }

    /** The Canvas of [trackId] as a downloaded file. Null when the song has none or it couldn't be had. */
    suspend fun find(trackId: String): File? {
        if (isKnown(trackId)) {
            return synchronized(known) { known[trackId] }.also { _status.value = if (it != null) PLAYING else NONE }
        }
        _status.value = "Asking Spotify…"
        val token = SpotifyAuth.getToken() ?: return null.also { _status.value = "Couldn't get the Spotify sign-in" }
        val url = catching { canvasUrl(token, trackId) }.getOrElse {
            Log.d(TAG, "canvas lookup failed: $it")
            _status.value = "Spotify didn't give this song's Canvas (${it.message ?: it.javaClass.simpleName})"
            return null
        }
        val file = url?.let {
            download(it, sha(it.substringBefore('?')) + ".mp4") ?: return null.also { _status.value = "Couldn't download this song's Canvas" }
        }
        Log.d(TAG, "song $trackId: " + if (file != null) "canvas, ${file.length() / 1024} kB" else "no canvas")
        synchronized(known) { known[trackId] = file }
        _status.value = if (file != null) PLAYING else NONE
        return file
    }

    /** The address of the song's Canvas video, or null when it has none. */
    private suspend fun canvasUrl(token: String, trackId: String): String? {
        val uri = "spotify:track:$trackId".toByteArray()
        // EntityCanvazRequest { entities { entity_uri } }
        val entity = ByteArrayOutputStream().apply { write(0x0A); write(uri.size); write(uri) }.toByteArray()
        val body = ByteArrayOutputStream().apply { write(0x0A); write(entity.size); write(entity) }.toByteArray()
        val request = Request.Builder().url(CANVAS_URL)
            .header("Authorization", "Bearer $token")
            .header("User-Agent", Net.BROWSER_UA)
            .header("Accept", "application/protobuf")
            .header("app-platform", "WebPlayer")
            .post(body.toRequestBody("application/x-protobuf".toMediaType()))
            .build()
        val bytes = withContext(Dispatchers.IO) {
            Net.client.newCall(request).execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                r.body.bytes()
            }
        }
        return parseCanvas(bytes)
    }

    /** Downloads [url] to the file [name] in the cache, or returns the copy that is there. Null when it can't be had. */
    private suspend fun download(url: String, name: String): File? = withContext(Dispatchers.IO) {
        val file = File(dir, name)
        if (file.length() > 0) {
            file.setLastModified(System.currentTimeMillis())
            return@withContext file
        }
        catching {
            dir.mkdirs()
            val part = File(dir, "$name.part")
            downloads.newCall(Request.Builder().url(url).header("User-Agent", Net.BROWSER_UA).build()).execute().use { r ->
                if (!r.isSuccessful) return@catching null
                if (r.body.contentLength() > MAX_BYTES) return@catching null
                part.outputStream().use { out -> r.body.byteStream().copyTo(out) }
            }
            if (part.length() == 0L || part.length() > MAX_BYTES || !part.renameTo(file)) {
                part.delete()
                return@catching null
            }
            trim()
            file
        }.onFailure { Log.d(TAG, "download failed: $it") }.getOrNull()
    }

    /** Keeps the cache folder to its newest files. */
    private fun trim() {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        files.forEachIndexed { i, f ->
            total += f.length()
            if (i >= MAX_FILES || total > MAX_TOTAL_BYTES) f.delete()
        }
    }

    companion object {
        private const val TAG = "SyllableCanvas"
        private const val PLAYING = "This song's Canvas is playing"
        private const val NONE = "This song has no Canvas"
        private const val CANVAS_URL = "https://spclient.wg.spotify.com/canvaz-cache/v0/canvases"
        private const val MAX_BYTES = 40L * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 200L * 1024 * 1024
        private const val MAX_FILES = 30

        private val downloads = Net.client.newBuilder().callTimeout(90, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

        /** The video address in an EntityCanvazResponse: the first canvas that is a video. */
        fun parseCanvas(bytes: ByteArray): String? = runCatching {
            fun varint(data: ByteArray, at: IntArray): Long {
                var result = 0L
                var shift = 0
                while (true) {
                    val b = data[at[0]++].toInt()
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                }
            }
            /** Calls [each] with every field of the message [data]: its number, its varint value or its bytes. */
            fun fields(data: ByteArray, each: (Int, Long, ByteArray?) -> Unit) {
                val at = intArrayOf(0)
                while (at[0] < data.size) {
                    val tag = varint(data, at)
                    when ((tag and 7).toInt()) {
                        0 -> each((tag ushr 3).toInt(), varint(data, at), null)
                        1 -> at[0] += 8
                        5 -> at[0] += 4
                        2 -> {
                            val n = varint(data, at).toInt()
                            each((tag ushr 3).toInt(), 0, data.copyOfRange(at[0], at[0] + n))
                            at[0] += n
                        }
                        else -> return
                    }
                }
            }
            var found: String? = null
            fields(bytes) { field, _, canvas ->
                if (field != 1 || canvas == null || found != null) return@fields
                var url: String? = null
                var type = 0L
                fields(canvas) { f, number, data ->
                    if (f == 2 && data != null) url = data.toString(Charsets.UTF_8)
                    if (f == 4 && data == null) type = number
                }
                // 1 to 3 are the video kinds. 0 is a still image and 4 a GIF.
                if (type in 1..3 && url?.startsWith("https://") == true) found = url
            }
            found
        }.getOrNull()

        private fun sha(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
    }
}
