package dev.kamol1dn.syllable.recognition

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import dev.kamol1dn.syllable.lyrics.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt

data class RecognizedTrack(
    val title: String,
    val artist: String,
    val album: String?,
    val coverUrl: String?,
    /** Song position (ms) at [anchorRealtime]. */
    val positionMs: Long,
    val anchorRealtime: Long,
    val shazamUrl: String?,
)

sealed interface RecognitionEvent {
    /** Recording; [level] is 0..1 input loudness for the animation. */
    data class Listening(val level: Float, val elapsedMs: Long) : RecognitionEvent
    data object Matching : RecognitionEvent
    data class Found(val track: RecognizedTrack) : RecognitionEvent
    data object NoMatch : RecognitionEvent
    data class Failed(val message: String) : RecognitionEvent
}

/** Records from the microphone and asks Shazam's service, retrying with longer samples (4s, 8s, 12s). */
object Recognizer {

    private const val RATE = ShazamSignature.SAMPLE_RATE
    private val attemptsAtMs = longArrayOf(4_000, 8_000, 12_000)

    @SuppressLint("MissingPermission")
    fun listen(): Flow<RecognitionEvent> = flow {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, RATE),
            )
        } catch (e: Exception) {
            emit(RecognitionEvent.Failed("Microphone unavailable")); return@flow
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release(); emit(RecognitionEvent.Failed("Microphone unavailable")); return@flow
        }
        val total = ShortArray(RATE * 13)
        var n = 0
        val chunk = ShortArray(RATE / 10)
        try {
            rec.startRecording()
            val start = SystemClock.elapsedRealtime()
            var attempt = 0
            while (attempt < attemptsAtMs.size) {
                currentCoroutineContext().ensureActive()
                val read = rec.read(chunk, 0, chunk.size)
                if (read <= 0) continue
                val copy = minOf(read, total.size - n)
                System.arraycopy(chunk, 0, total, n, copy)
                n += copy
                var sum = 0.0
                for (i in 0 until read) sum += chunk[i].toDouble() * chunk[i]
                val rms = sqrt(sum / read) / 32768.0
                val sampleMs = n * 1000L / RATE
                emit(RecognitionEvent.Listening((rms * 6).toFloat().coerceIn(0f, 1f), sampleMs))
                if (sampleMs >= attemptsAtMs[attempt]) {
                    attempt++
                    val sig = ShazamSignature().apply { feed(total, n) }
                    if (sig.peakCount < 10) continue // silence; keep listening
                    val result = runCatching { ShazamApi.recognize(sig) }
                    result.exceptionOrNull()?.let {
                        if (attempt == attemptsAtMs.size) emit(RecognitionEvent.Failed(it.message ?: "Network error"))
                    }
                    val match = result.getOrNull()
                    if (match != null) {
                        // The match offset is the song position at the first recorded sample.
                        emit(RecognitionEvent.Found(match.copy(anchorRealtime = start)))
                        return@flow
                    }
                    if (attempt < attemptsAtMs.size) emit(RecognitionEvent.Listening(0f, sampleMs))
                }
            }
            emit(RecognitionEvent.NoMatch)
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }.flowOn(Dispatchers.IO)
}

object ShazamApi {
    private val installId = UUID.randomUUID().toString().uppercase()

    suspend fun recognize(sig: ShazamSignature): RecognizedTrack? {
        val now = System.currentTimeMillis()
        val body = JSONObject()
            .put("geolocation", JSONObject().put("altitude", 300).put("latitude", 45).put("longitude", 2))
            .put(
                "signature",
                JSONObject().put("samplems", sig.sampleMs).put("timestamp", now).put("uri", sig.uri()),
            )
            .put("timestamp", now)
            .put("timezone", TimeZone.getDefault().id)
        val lang = Locale.getDefault().language.ifEmpty { "en" }
        val url = "https://amp.shazam.com/discovery/v5/$lang/US/android/-/tag/$installId/${UUID.randomUUID().toString().uppercase()}" +
            "?sync=true&webv3=true&sampling=true&connected=&shazamapiversion=v3&sharehub=true&video=v3"
        val req = Request.Builder().url(url)
            .header("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 14; Pixel 8 Build/AP2A.240805.005)")
            .header("Content-Language", "en_US")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val text = Net.fetch(req) ?: return null
        val o = JSONObject(text)
        val matches = o.optJSONArray("matches")
        val track = o.optJSONObject("track")
        if (matches == null || matches.length() == 0 || track == null) return null
        val m = matches.getJSONObject(0)
        val offsetSec = m.optDouble("offset", 0.0)
        val images = track.optJSONObject("images")
        var album: String? = null
        val sections = track.optJSONArray("sections")
        if (sections != null) {
            for (i in 0 until sections.length()) {
                val meta = sections.getJSONObject(i).optJSONArray("metadata") ?: continue
                for (j in 0 until meta.length()) {
                    val e = meta.getJSONObject(j)
                    if (e.optString("title").equals("Album", true)) album = e.optString("text")
                }
            }
        }
        return RecognizedTrack(
            title = track.optString("title"),
            artist = track.optString("subtitle"),
            album = album,
            coverUrl = images?.optString("coverarthq")?.ifEmpty { null } ?: images?.optString("coverart")?.ifEmpty { null },
            positionMs = (abs(offsetSec) * 1000).toLong(),
            anchorRealtime = 0,
            shazamUrl = track.optString("url").ifEmpty { null },
        )
    }
}
