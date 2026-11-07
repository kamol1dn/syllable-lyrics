package dev.kamol1dn.syllable.recognition

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

/**
 * Audio fingerprint in the format Shazam's recognition service accepts, computed from 16 kHz mono
 * 16-bit PCM. A Kotlin port of the open-source reverse-engineered algorithm (SongRec / shazamio):
 * a 2048-point Hann-windowed FFT every 128 samples, spectral peak picking, and peaks grouped into
 * four frequency bands.
 */
class ShazamSignature(
    /** SongRec includes the 3.5–5.5 kHz band; shazamio's Python port drops it (a typo). Off only for tests. */
    private val includeHighBand: Boolean = true,
) {

    data class Peak(val fftPassNumber: Int, val magnitude: Int, val correctedBin: Int)

    private val samplesRing = ShortArray(2048)
    private var samplesPos = 0

    private val fftOutputs = Array(256) { DoubleArray(1025) }
    private var fftPos = 0
    private var fftWritten = 0

    private val spread = Array(256) { DoubleArray(1025) }
    private var spreadPos = 0
    private var spreadWritten = 0

    private val bands = sortedMapOf<Int, MutableList<Peak>>()
    var numberSamples = 0
        private set

    private val re = DoubleArray(2048)
    private val im = DoubleArray(2048)

    /** Feed samples; any trailing partial chunk of < 128 samples is ignored (as the reference does). */
    fun feed(samples: ShortArray, count: Int = samples.size) {
        var off = 0
        while (count - off >= 128) {
            processChunk(samples, off)
            off += 128
        }
    }

    private fun processChunk(s: ShortArray, off: Int) {
        numberSamples += 128
        System.arraycopy(s, off, samplesRing, samplesPos, 128)
        samplesPos = (samplesPos + 128) % 2048
        doFft()
        doPeakSpreading()
        if (spreadWritten >= 46) doPeakRecognition()
    }

    private fun doFft() {
        // Oldest sample first.
        for (i in 0 until 2048) {
            re[i] = samplesRing[(samplesPos + i) % 2048] * HANNING[i]
            im[i] = 0.0
        }
        fft(re, im)
        val out = fftOutputs[fftPos]
        for (k in 0..1024) {
            val v = (re[k] * re[k] + im[k] * im[k]) / (1 shl 17)
            out[k] = max(v, 1e-10)
        }
        fftPos = (fftPos + 1) % 256
        fftWritten++
    }

    private fun doPeakSpreading() {
        val last = fftOutputs[(fftPos - 1 + 256) % 256]
        val spreadLast = last.copyOf()
        for (p in 0 until 1025) {
            if (p < 1022) spreadLast[p] = max(spreadLast[p], max(spreadLast[p + 1], spreadLast[p + 2]))
            var maxValue = spreadLast[p]
            for (former in intArrayOf(-1, -3, -6)) {
                val f = spread[((spreadPos + former) % 256 + 256) % 256]
                maxValue = max(f[p], maxValue)
                f[p] = maxValue
            }
        }
        System.arraycopy(spreadLast, 0, spread[spreadPos], 0, 1025)
        spreadPos = (spreadPos + 1) % 256
        spreadWritten++
    }

    private fun doPeakRecognition() {
        val fft46 = fftOutputs[((fftPos - 46) % 256 + 256) % 256]
        val spread49 = spread[((spreadPos - 49) % 256 + 256) % 256]
        for (bin in 10 until 1015) {
            val v = fft46[bin]
            if (v < 1.0 / 64 || v < spread49[bin - 1]) continue
            var maxNeighbor = 0.0
            for (o in NEIGHBORS) maxNeighbor = max(spread49[bin + o], maxNeighbor)
            if (v <= maxNeighbor) continue
            var maxOther = maxNeighbor
            for (o in OTHER_OFFSETS) {
                maxOther = max(spread[((spreadPos + o) % 256 + 256) % 256][bin - 1], maxOther)
            }
            if (v <= maxOther) continue

            val fftNumber = spreadWritten - 46
            val mag = ln(max(1.0 / 64, v)) * 1477.3 + 6144
            val magBefore = ln(max(1.0 / 64, fft46[bin - 1])) * 1477.3 + 6144
            val magAfter = ln(max(1.0 / 64, fft46[bin + 1])) * 1477.3 + 6144
            val variation1 = mag * 2 - magBefore - magAfter
            if (variation1 <= 0) continue
            val variation2 = (magAfter - magBefore) * 32 / variation1
            val correctedBin = bin * 64 + variation2
            val freqHz = correctedBin * (16000.0 / 2 / 1024 / 64)
            val band = when {
                freqHz > 250 && freqHz < 520 -> 0
                freqHz > 520 && freqHz < 1450 -> 1
                freqHz > 1450 && freqHz < 3500 -> 2
                includeHighBand && freqHz > 3500 && freqHz <= 5500 -> 3
                else -> continue
            }
            bands.getOrPut(band) { mutableListOf() }
                .add(Peak(fftNumber, mag.toInt(), correctedBin.toInt()))
        }
    }

    val peakCount: Int get() = bands.values.sumOf { it.size }
    val sampleMs: Long get() = numberSamples * 1000L / SAMPLE_RATE

    /** Binary signature. */
    fun encode(): ByteArray {
        val contents = ByteArrayOutputStream()
        for ((band, peaks) in bands) {
            val pb = ByteArrayOutputStream()
            var pass = 0
            for (p in peaks) {
                if (p.fftPassNumber - pass >= 255) {
                    pb.write(0xff)
                    pb.write(le32(p.fftPassNumber))
                    pass = p.fftPassNumber
                }
                pb.write(p.fftPassNumber - pass)
                pb.write(le16(p.magnitude))
                pb.write(le16(p.correctedBin))
                pass = p.fftPassNumber
            }
            val bytes = pb.toByteArray()
            contents.write(le32(0x60030040 + band))
            contents.write(le32(bytes.size))
            contents.write(bytes)
            contents.write(ByteArray((4 - bytes.size % 4) % 4))
        }
        val body = contents.toByteArray()

        val buf = ByteBuffer.allocate(48 + 8 + body.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0xcafe2580.toInt())          // magic1
        buf.putInt(0)                           // crc32, filled below
        buf.putInt(body.size + 8)               // size minus header
        buf.putInt(0x94119c00.toInt())          // magic2
        buf.putInt(0); buf.putInt(0); buf.putInt(0)
        buf.putInt(3 shl 27)                    // sample rate id (16000 Hz = 3)
        buf.putInt(0); buf.putInt(0)
        buf.putInt((numberSamples + SAMPLE_RATE * 0.24).toInt())
        buf.putInt((15 shl 19) + 0x40000)
        buf.putInt(0x40000000)
        buf.putInt(body.size + 8)
        buf.put(body)
        val out = buf.array()
        val crc = CRC32().apply { update(out, 8, out.size - 8) }.value
        ByteBuffer.wrap(out, 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(crc.toInt())
        return out
    }

    fun uri(): String = "data:audio/vnd.shazam.sig;base64," + Base64.getEncoder().encodeToString(encode())

    companion object {
        const val SAMPLE_RATE = 16000

        /** numpy.hanning(2050)[1:-1] */
        val HANNING = DoubleArray(2048) { i -> 0.5 - 0.5 * cos(2 * PI * (i + 1) / 2049) }
        private val NEIGHBORS = intArrayOf(-10, -7, -4, -3, 1, 2, 5, 8)
        private val OTHER_OFFSETS = intArrayOf(-53, -45, 165, 172, 179, 186, 193, 200, 214, 221, 228, 235, 242, 249)

        private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())

        private val COS = DoubleArray(1024) { cos(-2 * PI * it / 2048) }
        private val SIN = DoubleArray(1024) { sin(-2 * PI * it / 2048) }

        /** In-place iterative radix-2 FFT, n = 2048. */
        fun fft(re: DoubleArray, im: DoubleArray) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                j = j xor bit
                if (i < j) {
                    var t = re[i]; re[i] = re[j]; re[j] = t
                    t = im[i]; im[i] = im[j]; im[j] = t
                }
            }
            var len = 2
            while (len <= n) {
                val half = len / 2
                val step = n / len
                var i = 0
                while (i < n) {
                    for (k in 0 until half) {
                        val wr = COS[k * step]
                        val wi = SIN[k * step]
                        val a = i + k
                        val b = a + half
                        val xr = re[b] * wr - im[b] * wi
                        val xi = re[b] * wi + im[b] * wr
                        re[b] = re[a] - xr; im[b] = im[a] - xi
                        re[a] += xr; im[a] += xi
                    }
                    i += len
                }
                len = len shl 1
            }
        }
    }
}
