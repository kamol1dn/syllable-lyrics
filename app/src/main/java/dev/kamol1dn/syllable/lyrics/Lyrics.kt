package dev.kamol1dn.syllable.lyrics

/** One sung word (or syllable). [text] includes any trailing space so a line can be rebuilt by concatenation. */
data class LyricWord(val text: String, val startMs: Long, val endMs: Long)

data class LyricLine(
    val startMs: Long,
    val endMs: Long,
    val words: List<LyricWord>,
) {
    val text: String get() = words.joinToString("") { it.text }.trim()
}

enum class SyncType {
    /** Real per-word (or per-syllable) timestamps from the provider. */
    WORD,
    /** Line timestamps from the provider; word timings are estimated inside each line. */
    LINE,
    /** No timing at all. */
    NONE,
}

data class Lyrics(
    val lines: List<LyricLine>,
    val sync: SyncType,
    val source: String,
) {
    val isEmpty: Boolean get() = lines.isEmpty()
}

/** What we know about the song we need lyrics for. */
data class TrackQuery(
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
    val spotifyTrackId: String? = null,
) {
    val cacheKey: String
        get() = "${TextMatch.normalize(artist)}|${TextMatch.normalize(title)}"
}
