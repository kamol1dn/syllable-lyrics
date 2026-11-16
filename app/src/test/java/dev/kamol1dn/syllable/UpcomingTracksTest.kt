package dev.kamol1dn.syllable

import dev.kamol1dn.syllable.media.QueuedTrack
import dev.kamol1dn.syllable.media.upcomingTracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpcomingTracksTest {
    private val queue = listOf(
        QueuedTrack(38, "Natural", "Imagine Dragons"),
        QueuedTrack(39, "Save Your Tears (Remix)", "The Weeknd, Ariana Grande"),
        QueuedTrack(40, "Faded", "Alan Walker"),
        QueuedTrack(41, "Love Story", "Indila"),
        QueuedTrack(42, "Bye Bye Bye", "*NSYNC"),
    )

    @Test
    fun followsTheActiveEntry() {
        assertEquals(listOf("Love Story", "Bye Bye Bye"), upcomingTracks(queue, 40, "Faded").map { it.title })
        assertEquals(listOf("Faded"), upcomingTracks(queue, 39, "anything", limit = 1).map { it.title })
    }

    @Test
    fun fallsBackToTheTitle() {
        assertEquals(listOf("Love Story", "Bye Bye Bye"), upcomingTracks(queue, -1, "Faded").map { it.title })
    }

    @Test
    fun nothingAfterTheLastSongOrWithoutAMatch() {
        assertTrue(upcomingTracks(queue, 42, "Bye Bye Bye").isEmpty())
        assertTrue(upcomingTracks(queue, -1, "Unknown").isEmpty())
        assertTrue(upcomingTracks(emptyList(), 40, "Faded").isEmpty())
    }
}
