package dev.kamol1dn.syllable

import dev.kamol1dn.syllable.lyrics.LyricWord
import dev.kamol1dn.syllable.lyrics.wordGroups
import org.junit.Assert.assertEquals
import org.junit.Test

class WordGroupsTest {
    private fun words(vararg t: String) = t.mapIndexed { i, s -> LyricWord(s, i * 100L, i * 100L + 90) }
    private fun texts(g: List<List<LyricWord>>) = g.map { w -> w.map { it.text } }

    @Test
    fun syllablesOfOneWordStayTogether() {
        assertEquals(listOf(listOf("Wan", "na "), listOf("dance")), texts(wordGroups(words("Wan", "na ", "dance"))))
    }

    @Test
    fun leadingSpacesSplitWords() {
        assertEquals(2, wordGroups(words("hello", " world")).size)
    }

    @Test
    fun cjkCharactersStandAlone() {
        assertEquals(3, wordGroups(words("君", "の", "名")).size)
    }
}
