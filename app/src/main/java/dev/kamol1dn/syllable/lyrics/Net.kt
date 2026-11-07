package dev.kamol1dn.syllable.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

object Net {
    const val USER_AGENT = "Syllable/1.0 (https://github.com/kamol1dn/syllable-lyrics)"
    const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** Executes and returns the body for 2xx, null for 404, throws on other errors. */
    suspend fun fetch(request: Request): String? = withContext(Dispatchers.IO) {
        client.newCall(request).await().use { r ->
            when {
                r.isSuccessful -> r.body.string()
                r.code == 404 -> null
                else -> throw IOException("HTTP ${r.code} for ${request.url.host}")
            }
        }
    }

    suspend fun get(url: String, vararg headers: Pair<String, String>): String? {
        val b = Request.Builder().url(url).header("User-Agent", USER_AGENT)
        headers.forEach { (k, v) -> b.header(k, v) }
        return fetch(b.build())
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response) { _, r, _ -> r.close() }
            }
        })
        cont.invokeOnCancellation { runCatching { cancel() } }
    }
}

interface LyricsProvider {
    val name: String
    suspend fun fetch(query: TrackQuery): Lyrics?
}
