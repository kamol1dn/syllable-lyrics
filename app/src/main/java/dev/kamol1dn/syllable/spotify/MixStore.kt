package dev.kamol1dn.syllable.spotify

import android.content.Context
import android.util.Log
import dev.kamol1dn.syllable.lyrics.catching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The transitions of the mixed Spotify playlists that have been played, kept on the phone and
 * looked up by the pair of songs. A playlist is read when it starts playing, since Spotify's
 * player says which one that is, so the rest of the library is never touched.
 */
class MixStore(context: Context) {
    enum class Problem { SIGNED_OUT, NO_ANSWER }

    data class Status(
        val transitions: Int = 0,
        /** Playlists that have at least one transition. */
        val playlists: Int = 0,
        val reading: Boolean = false,
        val problem: Problem? = null,
    )

    private class Playlist(val checkedAt: Long, val transitions: List<MixTransition>)

    private companion object {
        const val TAG = "SyllableMix"
        /** Transitions can be changed in Spotify at any time, so a playlist is read again after this long. */
        const val FRESH_MS = 30L * 60 * 1000
        const val MAX_PLAYLISTS = 20
    }

    private val file = File(context.filesDir, "mix-transitions.json")
    private val lock = Mutex()
    /** By playlist id, least recently read first. Only changed under [lock], and replaced rather than edited. */
    @Volatile private var playlists: Map<String, Playlist> = emptyMap()
    @Volatile private var byPair: Map<String, MixTransition> = emptyMap()

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    init {
        runCatching { if (file.exists()) put(load(JSONObject(file.readText()))) }
    }

    private fun key(a: String, b: String) = "$a>$b"

    /** The transition from the song [a] into the song [b] (Spotify track ids), if a playlist that was played has one. */
    fun find(a: String, b: String): MixTransition? = byPair[key(a, b)]

    private fun put(all: Map<String, Playlist>) {
        playlists = all
        byPair = all.values.flatMap { it.transitions }.associateBy { key(it.trackAId, it.trackBId) }
        _status.update { s -> s.copy(transitions = byPair.size, playlists = all.values.count { it.transitions.isNotEmpty() }) }
    }

    /**
     * Makes sure the transitions of the playlist [playlistId] are known and recent. With [force]
     * they are read again even if they were read a moment ago.
     */
    suspend fun read(playlistId: String, force: Boolean = false) = lock.withLock {
        val known = playlists[playlistId]
        if (!force && known != null && System.currentTimeMillis() - known.checkedAt < FRESH_MS) return@withLock
        _status.update { it.copy(reading = true, problem = null) }
        val token = SpotifyAuth.getToken()
        val list = if (token == null) null else catching { SpotifyMix.transitions(token, playlistId) }
            .onFailure { Log.w(TAG, "playlist failed: $it") }.getOrNull()
        if (list != null) {
            Log.d(TAG, "${list.size} transitions in the playing playlist")
            // Re-inserted at the end, so the playlists read longest ago are the ones dropped.
            val all = LinkedHashMap(playlists - playlistId)
            all[playlistId] = Playlist(System.currentTimeMillis(), list)
            while (all.size > MAX_PLAYLISTS) all.remove(all.keys.first())
            put(all)
            withContext(Dispatchers.IO) { runCatching { file.writeText(json(all).toString()) } }
        }
        // What was saved before stays in use when Spotify can't be reached.
        _status.update { it.copy(reading = false, problem = if (token == null) Problem.SIGNED_OUT else if (list == null) Problem.NO_ANSWER else null) }
    }

    /** Forgets everything, for when the feature is turned off or the account signs out. */
    fun clear() {
        playlists = emptyMap()
        byPair = emptyMap()
        _status.value = Status()
        runCatching { file.delete() }
    }

    private fun load(o: JSONObject): Map<String, Playlist> {
        val lists = o.optJSONObject("playlists") ?: return emptyMap()
        val out = LinkedHashMap<String, Playlist>()
        for (id in lists.keys()) {
            val p = lists.optJSONObject(id) ?: continue
            val arr = p.optJSONArray("transitions") ?: continue
            out[id] = Playlist(
                p.optLong("checkedAt"),
                (0 until arr.length()).mapNotNull { i ->
                    val t = arr.optJSONObject(i) ?: return@mapNotNull null
                    MixTransition(
                        t.getString("a"), t.getString("b"), t.getLong("sa"), t.getLong("sb"), t.getLong("d"),
                        t.optDouble("sp", 1.0).toFloat(), t.optBoolean("bm"),
                    )
                },
            )
        }
        return out
    }

    private fun json(all: Map<String, Playlist>) = JSONObject().put(
        "playlists",
        JSONObject().apply {
            all.forEach { (id, p) ->
                put(
                    id,
                    JSONObject().put("checkedAt", p.checkedAt).put(
                        "transitions",
                        JSONArray().apply {
                            p.transitions.forEach {
                                put(
                                    JSONObject().put("a", it.trackAId).put("b", it.trackBId).put("sa", it.startAMs)
                                        .put("sb", it.startBMs).put("d", it.durationMs).put("sp", it.speedB.toDouble())
                                        .put("bm", it.beatmatched),
                                )
                            }
                        },
                    ),
                )
            }
        },
    )
}
