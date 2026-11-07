package dev.kamol1dn.syllable.lyrics

import kotlin.math.max
import kotlin.math.min

/** Parses plain and "enhanced" (A2 / word-tagged) LRC, and estimates word timing for line-only lyrics. */
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
                lines += estimateLine(text, r.start, nextStart)
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

    /** Spreads a line's words over a plausible sung duration, weighted by word length. */
    fun estimateLine(text: String, start: Long, nextStart: Long): LyricLine {
        val tokens = tokenize(text)
        val available = max(300L, nextStart - start)
        val wordCount = tokens.count { it.isNotBlank() }
        // ~3 words/second, never stretched across a long instrumental gap.
        val sung = min(available - 150, max(900L, wordCount * 330L + text.length * 12L)).coerceAtLeast(250L)
        val weights = tokens.map { w -> w.trim().count { it.isLetterOrDigit() } + 2.0 }
        val total = weights.sum()
        var t = start.toDouble()
        val words = tokens.mapIndexed { i, w ->
            val d = sung * weights[i] / total
            val word = LyricWord(w, t.toLong(), (t + d).toLong())
            t += d
            word
        }
        return LyricLine(start, start + sung, words)
    }

    /** Splits into words keeping trailing spaces; CJK text is split per character. */
    fun tokenize(text: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (ch in text) {
            if (isCjk(ch)) {
                if (sb.isNotEmpty()) { out += sb.toString(); sb.clear() }
                out += ch.toString()
            } else if (ch == ' ') {
                sb.append(ch)
                out += sb.toString(); sb.clear()
            } else {
                sb.append(ch)
            }
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out.filter { it.isNotEmpty() }
    }

    private fun isCjk(c: Char): Boolean {
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
