package dev.kamol1dn.syllable.lyrics

import kotlin.math.max
import kotlin.math.min

/** Parses plain and "enhanced" (A2 / word-tagged) LRC. Line-only lyrics stay line-only: no word timing is made up. */
object LrcParser {

    private val lineTag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val wordTag = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")
    private val offsetTag = Regex("""\[offset:\s*([+-]?\d+)]""", RegexOption.IGNORE_CASE)

    private fun toMs(m: String, s: String, frac: String?): Long {
        val f = when {
            frac == null -> 0L
            frac.length == 1 -> frac.toLong() * 100
            frac.length == 2 -> frac.toLong() * 10
            else -> frac.take(3).toLong()
        }
        return m.toLong() * 60_000 + s.toLong() * 1000 + f
    }

    private data class RawLine(val start: Long, val body: String)

    /** Returns null if the text has no timestamps. */
    fun parse(lrc: String, source: String): Lyrics? {
        val offset = offsetTag.find(lrc)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val raw = mutableListOf<RawLine>()
        for (lineText in lrc.lines()) {
            val tags = mutableListOf<Long>()
            var rest = lineText.trim()
            while (true) {
                val m = lineTag.matchAt(rest, 0) ?: break
                tags += toMs(m.groupValues[1], m.groupValues[2], m.groupValues[3].ifEmpty { null })
                rest = rest.substring(m.range.last + 1)
            }
            if (tags.isEmpty()) continue
            for (t in tags) raw += RawLine(max(0, t - offset), rest.trim())
        }
        if (raw.isEmpty()) return null
        raw.sortBy { it.start }

        val hasWordTags = raw.any { wordTag.containsMatchIn(it.body) }
        val lines = mutableListOf<LyricLine>()
        for ((i, r) in raw.withIndex()) {
            val nextStart = raw.getOrNull(i + 1)?.start ?: (r.start + 6_000)
            if (hasWordTags && wordTag.containsMatchIn(r.body)) {
                val words = parseWordTagged(r.body, r.start, nextStart, offset)
                if (words.isNotEmpty()) {
                    lines += LyricLine(words.first().startMs, max(words.last().endMs, words.first().startMs + 1), words)
                }
            } else {
                val text = r.body.replace(wordTag, "").trim()
                if (text.isEmpty()) continue
                lines += wholeLine(text, r.start, nextStart)
            }
        }
        if (lines.isEmpty()) return null
        return Lyrics(lines, if (hasWordTags) SyncType.WORD else SyncType.LINE, source)
    }

    private fun parseWordTagged(body: String, lineStart: Long, nextStart: Long, offset: Long): List<LyricWord> {
        val matches = wordTag.findAll(body).toList()
        val out = mutableListOf<LyricWord>()
        // Text before the first tag belongs to the line start.
        val lead = body.substring(0, matches.first().range.first)
        if (lead.isNotBlank()) out += LyricWord(lead, lineStart, lineStart)
        for ((i, m) in matches.withIndex()) {
            val t = max(0, toMs(m.groupValues[1], m.groupValues[2], m.groupValues[3].ifEmpty { null }) - offset)
            val textEnd = matches.getOrNull(i + 1)?.range?.first ?: body.length
            val text = body.substring(m.range.last + 1, textEnd)
            if (text.isEmpty()) {
                // A trailing tag marks the end of the previous word.
                if (out.isNotEmpty()) out[out.lastIndex] = out.last().copy(endMs = t)
                continue
            }
            if (out.isNotEmpty() && out.last().endMs <= out.last().startMs) {
                out[out.lastIndex] = out.last().copy(endMs = t)
            }
            out += LyricWord(text, t, t) // end filled by the next word
        }
        if (out.isNotEmpty() && out.last().endMs <= out.last().startMs) {
            val last = out.last()
            out[out.lastIndex] = last.copy(endMs = min(nextStart, last.startMs + 1_000 + last.text.length * 40L))
        }
        return out.filter { it.text.isNotBlank() || it.text.isNotEmpty() }
    }

    /**
     * A line with line timing only: one piece of text lit as a whole from [start] to [end].
     * [end] is the provider's end time, or the start of the next line when it gives none.
     */
    fun wholeLine(text: String, start: Long, end: Long): LyricLine {
        val e = max(end, start + 1)
        return LyricLine(start, e, listOf(LyricWord(text, start, e)))
    }

    internal fun isCjk(c: Char): Boolean {
        val block = Character.UnicodeBlock.of(c)
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.HIRAGANA ||
            block == Character.UnicodeBlock.KATAKANA ||
            block == Character.UnicodeBlock.HANGUL_SYLLABLES
    }

    fun plain(text: String, source: String): Lyrics? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        return Lyrics(
            lines.map { LyricLine(-1, -1, listOf(LyricWord(it, -1, -1))) },
            SyncType.NONE,
            source,
        )
    }
}
