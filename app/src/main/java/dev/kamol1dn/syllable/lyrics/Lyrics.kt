package dev.kamol1dn.syllable.lyrics

/** One sung word (or syllable). [text] includes any trailing space so a line can be rebuilt by concatenation. */
data class LyricWord(val text: String, val startMs: Long, val endMs: Long)

/**
 * Joins the syllables of one word ("Wan" + "na ") so a line can only wrap between words.
 * CJK characters each stand on their own, since those lines can wrap anywhere.
 */
fun wordGroups(ws: List<LyricWord>): List<List<LyricWord>> {
    val out = mutableListOf<List<LyricWord>>()
    var cur = mutableListOf<LyricWord>()
    ws.forEachIndexed { i, w ->
        cur += w
        val last = w.text.lastOrNull()
        val next = ws.getOrNull(i + 1)?.text?.firstOrNull()
        val boundary = last == null || last.isWhitespace() || LrcParser.isCjk(last) ||
            next == null || next.isWhitespace() || LrcParser.isCjk(next)
        if (boundary) {
            out += cur
            cur = mutableListOf()
        }
    }
    if (cur.isNotEmpty()) out += cur
    return out
}

data class LyricLine(
    val startMs: Long,
    val endMs: Long,
    val words: List<LyricWord>,
    /** Background vocals sung over this line, drawn smaller underneath. */
    val background: List<LyricWord> = emptyList(),
    /** Sung by a second singer, so drawn on the right. */
    val oppositeAligned: Boolean = false,
) {
    val text: String get() = words.joinToString("") { it.text }.trim()
    val backgroundText: String get() = background.joinToString("") { it.text }.trim()
}

/** A person or service to credit under the lyrics. */
data class Credit(val label: String, val name: String, val url: String? = null)

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
    /** Shown at the end of the lyrics, as the source asks. */
    val credits: List<Credit> = emptyList(),
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
