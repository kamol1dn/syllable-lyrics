package dev.kamol1dn.syllable.lyrics

import org.json.JSONObject

/**
 * NetEase Cloud Music. Many songs (including western ones) carry "YRC" lyrics with per-word timing:
 *   [lineStartMs,lineDurMs](wordStartMs,wordDurMs,0)word(wordStartMs,wordDurMs,0)word ...
 */
class NetEaseProvider : LyricsProvider {
    override val name = "NetEase"

    private val headers = arrayOf(
        "Referer" to "https://music.163.com/",
        "User-Agent" to Net.BROWSER_UA,
    )

    override suspend fun fetch(query: TrackQuery): Lyrics? {
        val id = findSongId(query) ?: return null
        val body = Net.get(
            "https://music.163.com/api/song/lyric/v1?id=$id&lv=1&kv=0&tv=0&rv=0&yv=1&ytv=0&yrv=0",
            *headers,
        ) ?: return null
        val o = JSONObject(body)
        val yrc = o.optJSONObject("yrc")?.optString("lyric").orEmpty()
        val lrc = o.optJSONObject("lrc")?.optString("lyric").orEmpty()
        if (yrc.isNotBlank()) parseYrc(yrc, lrc)?.let { return it }
        if (lrc.isNotBlank()) {
            // NetEase pads instrumental songs with "纯音乐，请欣赏" — treat as no lyrics.
            if (lrc.contains("纯音乐")) return null
            return LrcParser.parse(stripCredits(lrc), name)
        }
        return null
    }

    private suspend fun findSongId(query: TrackQuery): Long? {
        val q = Net.enc("${query.title} ${TextMatch.primaryArtist(query.artist)}")
        val body = Net.get("https://music.163.com/api/search/get?s=$q&type=1&limit=10", *headers) ?: return null
        val songs = JSONObject(body).optJSONObject("result")?.optJSONArray("songs") ?: return null
        var bestId: Long? = null
        var bestScore = 0.0
        for (i in 0 until songs.length()) {
            val s = songs.getJSONObject(i)
            val artists = s.optJSONArray("artists")
            val artistNames = buildList {
                if (artists != null) for (j in 0 until artists.length()) add(artists.getJSONObject(j).optString("name"))
            }.joinToString(", ")
            val score = TextMatch.score(query, s.optString("name"), artistNames, s.optLong("duration"))
            if (score > bestScore) { bestScore = score; bestId = s.optLong("id") }
        }
        return bestId
    }

    private val yrcLine = Regex("""^\[(\d+),(\d+)](.*)$""")
    private val yrcWord = Regex("""\((\d+),(\d+),\d+\)(.*?)(?=\(\d+,\d+,\d+\)|$)""")

    /** [lrc] (same song, line-level) is used to restore spaces that YRC sometimes drops between words. */
    fun parseYrc(yrc: String, lrc: String = ""): Lyrics? {
        val lrcLines = if (lrc.isNotBlank()) LrcParser.parse(lrc, name)?.lines.orEmpty() else emptyList()
        val lines = mutableListOf<LyricLine>()
        for (raw in yrc.lines()) {
            val m = yrcLine.find(raw.trim()) ?: continue // JSON credit lines start with "{"
            val start = m.groupValues[1].toLong()
            val dur = m.groupValues[2].toLong()
            var rawWords = yrcWord.findAll(m.groupValues[3]).map {
                val ws = it.groupValues[1].toLong()
                LyricWord(it.groupValues[3], ws, ws + it.groupValues[2].toLong())
            }.filter { it.text.isNotEmpty() }.toList()
            if (rawWords.isEmpty()) continue
            // Latin lyrics on NetEase often use full-width punctuation like "（Oh）"; map it to ASCII.
            val latin = rawWords.any { w -> w.text.any { it in 'A'..'z' } }
            if (latin) rawWords = rawWords.map { it.copy(text = halfWidth(it.text)) }
            // Leading spaces belong to the gap before a word, not to the word itself.
            val words = rawWords.mapIndexed { i, w ->
                val next = rawWords.getOrNull(i + 1)
                var t = w.text.trimStart()
                if (t.isNotEmpty() && next != null && next.text.first().isWhitespace() && !t.last().isWhitespace()) t += " "
                w.copy(text = t.replace(Regex("""\s+"""), " "))
            }.filter { it.text.isNotBlank() }
            if (words.isEmpty()) continue
            val text = words.joinToString("") { it.text }
            if (isCredit(text)) continue
            lines += LyricLine(start, start + dur, respace(words, start, lrcLines))
        }
        if (lines.isEmpty()) return null
        return Lyrics(lines.sortedBy { it.startMs }, SyncType.WORD, name)
    }

    private fun halfWidth(t: String) = buildString {
        for (c in t) when (c) {
            in '！'..'～' -> append(c - 0xFEE0)
            '　' -> append(' ')
            else -> append(c)
        }
    }

    private fun squash(s: String) = s.filterNot { it.isWhitespace() }.lowercase()

    private fun respace(words: List<LyricWord>, start: Long, lrcLines: List<LyricLine>): List<LyricWord> {
        val joined = squash(words.joinToString("") { it.text })
        val ref = lrcLines.firstOrNull { kotlin.math.abs(it.startMs - start) < 1_500 && squash(it.text) == joined }
            ?.text ?: return words
        // Walk the reference text, giving each word its characters plus any following spaces.
        var r = 0
        return words.map { w ->
            val sb = StringBuilder()
            var need = w.text.count { !it.isWhitespace() }
            while (r < ref.length && ref[r].isWhitespace() && sb.isEmpty()) r++
            while (r < ref.length && need > 0) {
                if (!ref[r].isWhitespace()) need--
                sb.append(ref[r]); r++
            }
            while (r < ref.length && ref[r].isWhitespace()) { sb.append(' '); r++ }
            w.copy(text = sb.toString().replace(Regex(" +"), " "))
        }
    }

    private fun isCredit(text: String): Boolean {
        val t = text.trim()
        return Regex("""^(作词|作曲|编曲|制作人|词|曲|Lyricist|Composer|Producer|Arranger|Written by)\s*[:：]""",
            RegexOption.IGNORE_CASE).containsMatchIn(t)
    }

    private fun stripCredits(lrc: String): String =
        lrc.lines().filterNot { line ->
            val text = line.replace(Regex("""\[[^]]*]"""), "")
            isCredit(text)
        }.joinToString("\n")
}
