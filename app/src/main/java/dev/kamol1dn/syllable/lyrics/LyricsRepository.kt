package dev.kamol1dn.syllable.lyrics

import android.content.Context
import android.os.SystemClock
import dev.kamol1dn.syllable.Settings
import dev.kamol1dn.syllable.spotify.SpotifyLyricsProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Sources in order: Spicy Lyrics, Spotify, Musixmatch, NetEase, LRCLIB. All are asked at once for
 * speed, but a result is only shown once no source still answering could beat it: word timing
 * beats line timing beats plain text, and within the same kind the earlier source wins.
 */
class LyricsRepository(context: Context, private val settings: Settings) {
    private companion object {
        const val TAG = "SyllableLyrics"
        /** Show the best result so far if a better source is this slow. */
        const val GRACE_MS = 3_500L
        /** Spicy Lyrics' terms allow keeping a response for at most 30 days. */
        const val CACHE_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
    }

    // v2: lines carry background vocals, duet sides and credits.
    private val cacheDir = File(context.cacheDir, "lyrics-v2").apply { mkdirs() }
    private val memory = object : LinkedHashMap<String, Lyrics>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Lyrics>?) = size > 30
    }

    private val spotify = SpotifyLyricsProvider()
    private val spicy = SpicyLyricsProvider(
        apiKey = { settings.spicyKey },
        spotifyIdLookup = { q -> if (settings.useSpotify) spotify.findMatch(q)?.trackId else null },
    )
    private val musixmatch = MusixmatchProvider(context.getSharedPreferences("mxm", Context.MODE_PRIVATE))
    private val netease = NetEaseProvider()
    private val lrclib = LrclibProvider()

    init {
        File(context.cacheDir, "lyrics").deleteRecursively()
        cacheDir.listFiles()?.forEach { if (isExpired(it)) it.delete() }
    }

    private fun isExpired(f: File) = System.currentTimeMillis() - f.lastModified() > CACHE_MAX_AGE_MS

    /** Priority order (first = preferred). */
    private fun providers(): List<LyricsProvider> = buildList {
        if (settings.useSpicy && settings.spicyKey != null) add(spicy)
        if (settings.useSpotify) add(spotify)
        if (settings.useMusixmatch) add(musixmatch)
        if (settings.useNetEase) add(netease)
        add(lrclib)
    }

    private fun rank(l: Lyrics, priority: Int): Int {
        val sync = when (l.sync) { SyncType.WORD -> 3; SyncType.LINE -> 2; SyncType.NONE -> 1 }
        return sync * 100 - priority
    }

    /** The best a source that hasn't answered yet could still give. */
    private fun ceiling(priority: Int) = 300 - priority

    sealed interface Progress {
        data class Found(val lyrics: Lyrics, val final: Boolean) : Progress
        data object NotFound : Progress
        /** Every source that answered failed with an error, so the phone is probably offline. */
        data object Offline : Progress
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
        // index, result, and whether the source failed with an error
        val results = Channel<Triple<Int, Lyrics?, Boolean>>(Channel.UNLIMITED)
        val jobs = list.mapIndexed { i, p ->
            launch(Dispatchers.IO) {
                var error: Throwable? = null
                val l = withTimeoutOrNull(30_000) {
                    runCatching { p.fetch(query) }
                        .onFailure { error = it; android.util.Log.w(TAG, "${p.name} failed: $it") }
                        .getOrNull()
                }
                android.util.Log.d(TAG, "${p.name}: ${l?.sync ?: "none"} (${l?.lines?.size ?: 0} lines) for ${query.artist} - ${query.title}")
                results.send(Triple(i, l?.takeIf { !it.isEmpty }, error != null))
            }
        }
        var failures = 0
        var misses = 0
        val pending = list.indices.toMutableSet()
        var best: Lyrics? = null
        var bestRank = Int.MIN_VALUE
        var shown: Lyrics? = null
        val deadline = SystemClock.elapsedRealtime() + GRACE_MS
        while (pending.isNotEmpty()) {
            val current = best
            val waiting = current != null && current !== shown
            val left = deadline - SystemClock.elapsedRealtime()
            val msg = when {
                !waiting -> results.receive()
                left > 0 -> withTimeoutOrNull(left) { results.receive() }
                else -> null
            }
            if (msg == null) {
                // A better source is slow: show what we have and keep listening.
                shown = current
                send(Progress.Found(current!!, final = false))
                continue
            }
            val (i, l, failed) = msg
            pending -= i
            // Spotify also returns nothing when signed out, so its empty answer says nothing about the network.
            if (failed) failures++ else if (l == null && list[i] !== spotify) misses++
            if (l != null && rank(l, i) > bestRank) { best = l; bestRank = rank(l, i) }
            if (best != null && pending.all { ceiling(it) < bestRank }) break
        }
        jobs.forEach { it.cancel() }
        best?.let { send(Progress.Found(it, final = true)) }
        val b = best
        if (b == null) {
            send(if (failures > 0 && misses == 0) Progress.Offline else Progress.NotFound)
        } else {
            synchronized(memory) { memory[key] = b }
            if (b.sync != SyncType.NONE) withContext(Dispatchers.IO) { writeDisk(key, b) }
        }
    }.flowOn(Dispatchers.Default) // keep source picking off the busy main thread

    /** Saved songs and their total size. Reads the disk, so call it off the main thread. */
    fun cacheStats(): Pair<Int, Long> {
        val files = cacheDir.listFiles()?.filter { it.isFile && !isExpired(it) }.orEmpty()
        return files.size to files.sumOf { it.length() }
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
            if (isExpired(f)) { f.delete(); return@runCatching null }
            val o = JSONObject(f.readText())
            val lines = o.getJSONArray("lines")
            Lyrics(
                (0 until lines.length()).map { i ->
                    val l = lines.getJSONObject(i)
                    val ws = l.getJSONArray("w")
                    LyricLine(
                        l.getLong("s"), l.getLong("e"),
                        words(ws),
                        l.optJSONArray("b")?.let(::words).orEmpty(),
                        l.optBoolean("o", false),
                    )
                },
                SyncType.valueOf(o.getString("sync")),
                o.getString("source"),
                o.optJSONArray("credits")?.let { c ->
                    (0 until c.length()).map { i ->
                        val x = c.getJSONObject(i)
                        Credit(x.getString("label"), x.getString("name"), x.optString("url").ifEmpty { null })
                    }
                }.orEmpty(),
            )
        }.getOrNull()
    }

    private fun words(ws: JSONArray): List<LyricWord> = (0 until ws.length()).map { j ->
        val w = ws.getJSONArray(j)
        LyricWord(w.getString(0), w.getLong(1), w.getLong(2))
    }

    private fun wordsJson(words: List<LyricWord>) = JSONArray().apply {
        words.forEach { w -> put(JSONArray().put(w.text).put(w.startMs).put(w.endMs)) }
    }

    private fun writeDisk(key: String, l: Lyrics) {
        runCatching {
            val lines = JSONArray()
            l.lines.forEach { line ->
                val o = JSONObject().put("s", line.startMs).put("e", line.endMs).put("w", wordsJson(line.words))
                if (line.background.isNotEmpty()) o.put("b", wordsJson(line.background))
                if (line.oppositeAligned) o.put("o", true)
                lines.put(o)
            }
            val credits = JSONArray()
            l.credits.forEach { c -> credits.put(JSONObject().put("label", c.label).put("name", c.name).put("url", c.url ?: "")) }
            fileFor(key).writeText(
                JSONObject().put("sync", l.sync.name).put("source", l.source).put("lines", lines)
                    .put("credits", credits).toString(),
            )
        }
    }
}
