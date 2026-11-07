package dev.kamol1dn.syllable

import dev.kamol1dn.syllable.recognition.ShazamSignature
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * sig_reference.bin was produced by the reference Python implementation (shazamio 0.4 algorithm.py)
 * from sig_signal.raw (10 s of synthetic tones + noise, 16 kHz s16le).
 */
class ShazamSignatureTest {
    private fun res(name: String) = javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes()

    @Test
    fun matchesReferenceImplementation() {
        val raw = res("sig_signal.raw")
        val samples = ShortArray(raw.size / 2)
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
        val sig = ShazamSignature(includeHighBand = false).apply { feed(samples) }
        val ours = sig.encode()
        val ref = res("sig_reference.bin")
        val diffs = ours.indices.count { it < ref.size && ours[it] != ref[it] }
        println("ours=${ours.size} ref=${ref.size} peaks=${sig.peakCount} differingBytes=$diffs")
        assertEquals(ref.size, ours.size)
        assertArrayEquals(ref, ours)
    }
}
