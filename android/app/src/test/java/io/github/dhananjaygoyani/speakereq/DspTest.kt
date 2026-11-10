package io.github.dhananjaygoyani.speakereq

import io.github.dhananjaygoyani.speakereq.Dsp.Filter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.*

/** Same checks as speaker_eq.py --selftest, on the Kotlin port. */
class DspTest {
    private fun lfilter(b: DoubleArray, a: DoubleArray, x: DoubleArray): DoubleArray {
        val y = DoubleArray(x.size)
        for (n in x.indices) {
            var v = b[0] * x[n]
            if (n >= 1) v += b[1] * x[n - 1] - a[1] * y[n - 1]
            if (n >= 2) v += b[2] * x[n - 2] - a[2] * y[n - 2]
            y[n] = v / a[0]
        }
        return y
    }

    private fun highpass(f0: Double): Pair<DoubleArray, DoubleArray> { // 2nd-order Butterworth (RBJ, Q=1/sqrt2)
        val w = 2 * PI * f0 / Dsp.FS
        val al = sin(w) / (2 / sqrt(2.0))
        val c = cos(w)
        return doubleArrayOf((1 + c) / 2, -(1 + c), (1 + c) / 2) to doubleArrayOf(1 + al, -2 * c, 1 - al)
    }

    /** Simulated small speaker: 120 Hz high-pass, +6 dB @ 800 Hz, -5 dB @ 3 kHz, 250 ms latency, noise. */
    private fun fakeSpeaker(nl: Double, peaks: List<Triple<Double, Double, Double>>, hp: Boolean = true): (DoubleArray) -> DoubleArray {
        val rng = Random(0)
        return { x ->
            var y = x
            if (hp) { val (b, a) = highpass(120.0); y = lfilter(b, a, y) }
            for ((f0, g, q) in peaks) { val (b, a) = Dsp.biquad(f0, g, q); y = lfilter(b, a, y) }
            val out = DoubleArray(Dsp.ms(250.0) + y.size + Dsp.FS)
            for (i in y.indices) out[Dsp.ms(250.0) + i] = 0.3 * y[i] + nl * y[i].pow(3)
            for (i in out.indices) out[i] += 1e-4 * rng.nextGaussian()
            out
        }
    }

    private val selftestSpeaker = listOf(Triple(800.0, 6.0, 2.0), Triple(3000.0, -5.0, 1.5))
    private fun afterRms(text: String) = Regex("RMS before, ([0-9.]+) dB RMS after").find(text)!!.groupValues[1].toDouble()
    private fun at(curve: DoubleArray, f: Double) = curve[Dsp.GRID.indexOfFirst { it >= f }]

    @Test fun fftMatchesDft() {
        val n = 16
        val x = DoubleArray(n) { sin(it * 1.3) + 0.2 * it }
        val re = x.copyOf(); val im = DoubleArray(n)
        Dsp.fft(re, im, false)
        for (k in 0 until n) {
            var r = 0.0; var i = 0.0
            for (t in 0 until n) { r += x[t] * cos(2 * PI * k * t / n); i -= x[t] * sin(2 * PI * k * t / n) }
            assertEquals(r, re[k], 1e-9); assertEquals(i, im[k], 1e-9)
        }
        Dsp.fft(re, im, true)
        for (t in 0 until n) assertEquals(x[t], re[t], 1e-9)
    }

    @Test fun findsPeaksAndDipsOfLinearSpeaker() {
        val r = Dsp.run(fakeSpeaker(0.0, selftestSpeaker), positions = 1, fixed = false, tiltDb = 0.0)
        assertTrue("residual " + afterRms(r.text), afterRms(r.text) < 1.0)
        assertTrue("missed the 800 Hz peak: ${r.filters}", r.filters.any { it.f in 600.0..1000.0 && it.g < -3 })
        assertTrue("missed the 3 kHz dip: ${r.filters}", r.filters.any { it.f in 2000.0..4500.0 && it.g > 2 })
        assertTrue(r.text, r.text.contains("peak") && r.text.contains("Usable range"))
    }

    @Test fun detectsDistortion() {
        val clean = Dsp.run(fakeSpeaker(0.0, selftestSpeaker), 1, false, tiltDb = 0.0)
        assertTrue(clean.text, !clean.text.contains("Distortion:"))
        val dirty = Dsp.run(fakeSpeaker(0.2, selftestSpeaker), 1, false, tiltDb = 0.0)
        assertTrue(dirty.text, dirty.text.contains("Distortion:"))
    }

    @Test fun stillModeIsGentlerInBass() {
        val spk = listOf(Triple(150.0, 8.0, 3.0), Triple(2000.0, 6.0, 2.0)) // room-mode-like peak + mid peak
        val moving = Dsp.peqDb(Dsp.run(fakeSpeaker(0.0, spk, hp = false), 1, false, tiltDb = 0.0).filters)
        val still = Dsp.peqDb(Dsp.run(fakeSpeaker(0.0, spk, hp = false), 1, true, tiltDb = 0.0).filters)
        assertTrue("moving cut ${at(moving, 150.0)}", at(moving, 150.0) < -5)
        assertTrue("still ${at(still, 150.0)} vs moving ${at(moving, 150.0)}", at(still, 150.0) > at(moving, 150.0) + 1.5)
        assertTrue(abs(at(still, 2000.0) - at(moving, 2000.0)) < 1.0)
    }

    @Test fun shelfFiltersAndExport() {
        val lo = Dsp.peqDb(listOf(Filter(100.0, 6.0, 0.71, "LSC")))
        val hi = Dsp.peqDb(listOf(Filter(8000.0, 6.0, 0.71, "HSC")))
        assertEquals(6.0, lo.first(), 0.3); assertEquals(0.0, lo.last(), 0.3)
        assertEquals(0.0, hi.first(), 0.3); assertEquals(6.0, hi.last(), 0.5)
        val txt = Dsp.apoText(listOf(Filter(100.0, 4.0, 0.71, "LSC"), Filter(800.0, -3.0, 2.0)))
        assertTrue(txt, txt.startsWith("Preamp: -4.") && txt.contains("Filter 1: ON LSC Fc 100 Hz Gain 4.0 dB Q 0.71"))
        assertEquals("Preamp: 0.0 dB\n", Dsp.apoText(emptyList()))
    }

    @Test fun loudnessGainKeepsLevel() {
        assertEquals(0.0, Dsp.loudnessGain(emptyList()), 1e-9)
        assertTrue(Dsp.loudnessGain(listOf(Filter(1000.0, 6.0, 0.7))) < -1) // boosts -> turn down a little
    }

    @Test fun importReadsOwnExportAndAutoEq() {
        val f = listOf(Filter(100.0, 4.0, 0.71, "LSC"), Filter(812.0, -5.5, 2.16), Filter(8000.0, -2.0, 0.71, "HSC"))
        assertEquals(f, Dsp.parseApo(Dsp.apoText(f)))
        val autoEq = "Preamp: -6.2 dB\nFilter 1: ON LSC Fc 105 Hz Gain 5.9 dB Q 0.70\nFilter 2: ON PK Fc 2400 Hz Gain -3.1 dB Q 1.41\n"
        assertEquals(listOf(Filter(105.0, 5.9, 0.70, "LSC"), Filter(2400.0, -3.1, 1.41)), Dsp.parseApo(autoEq))
        assertTrue(Dsp.parseApo("hello\nFilter 1: ON PK Fc 0 Hz Gain 99 dB Q 1").isEmpty()) // junk rejected
    }
}
