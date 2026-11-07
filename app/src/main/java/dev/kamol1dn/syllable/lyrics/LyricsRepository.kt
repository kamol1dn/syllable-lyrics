package dev.kamol1dn.syllable.lyrics

import android.content.Context
import dev.kamol1dn.syllable.Settings
import dev.kamol1dn.syllable.spotify.SpotifyLyricsProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Asks every enabled provider in parallel and emits progressively better lyrics:
 * real word timing beats line timing beats plain text; ties go to provider priority.
 */
class LyricsRepository(context: Context, private val settings: Settings) {
    private companion object { const val TAG = "SyllableLyrics" }

    private val cacheDir = File(context.cacheDir, "lyrics").apply { mkdirs() }
    private val memory = object : LinkedHashMap<String, Lyrics>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Lyrics>?) = size > 30
    }

    private val spotify = SpotifyLyricsProvider()
    private val musixmatch = MusixmatchProvider(context.getSharedPreferences("mxm", Context.MODE_PRIVATE))
    private val netease = NetEaseProvider()
    private val lrclib = LrclibProvider()

    /** Priority order (first = preferred). */
    private fun providers(): List<LyricsProvider> = buildList {
        if (settings.useSpotify) add(spotify)
        if (settings.useMusixmatch) add(musixmatch)
        if (settings.useNetEase) add(netease)
        add(lrclib)
    }

    private fun rank(l: Lyrics, priority: Int): Int {
        val sync = when (l.sync) { SyncType.WORD -> 3; SyncType.LINE -> 2; SyncType.NONE -> 1 }
        return sync * 100 - priority
    }

    sealed interface Progress {
        data class Found(val lyrics: Lyrics, val final: Boolean) : Progress
        data object NotFound : Progress
    }

    fun load(rawQuery: TrackQuery, skipCache: Boolean = false): Flow<Progress> = channelFlow {
        val (title, artist) = TextMatch.clean(rawQuery.title, rawQuery.artist)
        val query = rawQuery.copy(title = title, artist = artist)
        val key = query.cacheKey

        if (!skipCache) {
            val cached = synchronized(memory) { memory[key] } ?: readDisk(key)
            if (cached != null) {
                synchronized(memory) { memory[key] = cached }
                send(Progress.Found(cached, final = true))
                return@channelFlow
            }
        }

        val list = providers()
        val results = Channel<Pair<Int, Lyrics?>>(Channel.UNLIMITED)
        list.forEachIndexed { i, p ->
            launch(Dispatchers.IO) {
                val l = withTimeoutOrNull(30_000) {
                    runCatching { p.fetch(query) }
                        .onFailure { android.util.Log.w(TAG, "${p.name} failed: $it") }
                        .getOrNull()
                }
                android.util.Log.d(TAG, "${p.name}: ${l?.sync ?: "none"} (${l?.lines?.size ?: 0} lines) for ${query.artist} - ${query.title}")
                results.send(i to l?.takeIf { !it.isEmpty })
            }
        }
        var best: Lyrics? = null
        var bestRank = Int.MIN_VALUE
        repeat(list.size) { n ->
            val (i, l) = results.receive()
            val done = n == list.size - 1
            if (l != null) {
                val r = rank(l, i)
                if (r > bestRank) {
                    best = l; bestRank = r
                    // Real word timing from the top-priority provider can't be beaten.
                    send(Progress.Found(l, final = done))
                } else if (done) {
                    send(Progress.Found(best!!, final = true))
                }
            } else if (done) {
                best?.let { send(Progress.Found(it, final = true)) }
            }
        }
        val b = best
        if (b == null) {
            send(Progress.NotFound)
        } else {
            synchronized(memory) { memory[key] = b }
            if (b.sync != SyncType.NONE) withContext(Dispatchers.IO) { writeDisk(key, b) }
        }
    }

    fun clearCache() {
        synchronized(memory) { memory.clear() }
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    private fun fileFor(key: String): File {
        val h = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(cacheDir, "$h.json")
    }

    private suspend fun readDisk(key: String): Lyrics? = withContext(Dispatchers.IO) {
        runCatching {
            val f = fileFor(key)
            if (!f.exists()) return@runCatching null
            val o = JSONObject(f.readText())
            val lines = o.getJSONArray("lines")
            Lyrics(
                (0 until lines.length()).map { i ->
                    val l = lines.getJSONObject(i)
                    val ws = l.getJSONArray("w")
                    LyricLine(
                        l.getLong("s"), l.getLong("e"),
                        (0 until ws.length()).map { j ->
                            val w = ws.getJSONArray(j)
                            LyricWord(w.getString(0), w.getLong(1), w.getLong(2))
                        },
                    )
                },
                SyncType.valueOf(o.getString("sync")),
                o.getString("source"),
            )
        }.getOrNull()
    }

    private fun writeDisk(key: String, l: Lyrics) {
        runCatching {
            val lines = JSONArray()
            l.lines.forEach { line ->
                val ws = JSONArray()
                line.words.forEach { w -> ws.put(JSONArray().put(w.text).put(w.startMs).put(w.endMs)) }
                lines.put(JSONObject().put("s", line.startMs).put("e", line.endMs).put("w", ws))
            }
            fileFor(key).writeText(
                JSONObject().put("sync", l.sync.name).put("source", l.source).put("lines", lines).toString(),
            )
        }
    }
}
