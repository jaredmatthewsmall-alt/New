package com.livesound.analyzer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

private const val SR = 48000.0
private const val N = 8192
private const val HOP = 1600 // ~30 analysis frames per second, like the app

/** Feed a signal through the analyzer frame by frame, the way the app does. */
private fun analyze(signal: FloatArray, target: TargetCurve = TargetCurve.FLAT):
    Pair<LiveSoundAnalyzer, List<Recommendation>> {
    val sp = Spectrum(SR, N)
    val an = LiveSoundAnalyzer(SR, N, sp.enbw, target)
    var t = 0.0
    var end = N
    while (end <= signal.size) {
        val block = signal.copyOfRange(end - N, end)
        var peak = 0.0
        var ms = 0.0
        for (i in end - HOP until end) {
            peak = maxOf(peak, abs(signal[i].toDouble()))
            ms += signal[i].toDouble() * signal[i]
        }
        t += HOP / SR
        an.update(sp.power(block), t, peak, powerToDb(ms / HOP))
        end += HOP
    }
    return an to an.recommendations()
}

private fun noise(seconds: Double, level: Float = 0.05f, bumps: List<Bump> = emptyList()) =
    pinkNoise((SR * seconds).toInt(), SR, 0, bumps).also { for (i in it.indices) it[i] *= level }

private fun FloatArray.plus(f: (Int) -> Double) = FloatArray(size) { this[it] + f(it).toFloat() }

class DspTest {
    @Test fun sineReadsCorrectDbfsAndFrequency() {
        val sp = Spectrum(SR, N)
        val x = FloatArray(N) { (0.5 * sin(2 * PI * 1003.7 * it / SR)).toFloat() }
        val d = sp.power(x).map(::powerToDb).toDoubleArray()
        val i = d.indices.maxBy { d[it] }
        assertTrue(abs(sp.freqs[i] - 1003.7) < sp.binHz)
        assertEquals(-6.02, d[i], 1.5)
        val peaks = findNarrowPeaks(sp.freqs, d, minLevel = -100.0)
        assertTrue(abs(peaks[0].freq - 1003.7) < 1.0)
    }

    @Test fun fftRoundTrip() {
        val fft = Fft(64)
        val orig = DoubleArray(64) { sin(it * 0.3) + 0.1 * (it % 5) }
        val re = orig.copyOf()
        val im = DoubleArray(64)
        fft.transform(re, im)
        fft.transform(re, im, inverse = true)
        for (i in 0 until 64) assertEquals(orig[i], re[i] / 64, 1e-9)
    }

    @Test fun pinkNoiseIsFlatInThirdOctaves() {
        val sp = Spectrum(SR, N)
        val x = noise(6.0)
        val acc = DoubleArray(N / 2 + 1)
        var count = 0
        var i = 0
        while (i + N <= x.size) {
            val p = sp.power(x.copyOfRange(i, i + N))
            for (k in acc.indices) acc[k] += p[k]
            count++
            i += N / 2
        }
        val bands = bandLevels(sp.freqs, DoubleArray(acc.size) { acc[it] / count }, sp.enbw)
        val vals = THIRD_OCTAVE_EXACT.indices.filter { THIRD_OCTAVE_EXACT[it] in 50.0..16000.0 }.map { bands[it] }
        assertTrue(vals.max() - vals.min() < 4.0)
    }

    @Test fun helpers() {
        assertEquals("A4 +0c", noteName(440.0))
        assertEquals("2.5 kHz", nearestGeqBand(2530.0))
        assertEquals("3.15 kHz", formatHz(3150.0))
        assertEquals("250 Hz", formatHz(250.0))
        assertEquals(1.414, qFromOctaves(1.0), 0.01)
        assertEquals("2.5k", axisLabel(2500.0))
        assertEquals("31.5", axisLabel(31.5))
    }

    @Test fun logPoolerKeepsSingleBinSpikes() {
        val freqs = DoubleArray(4097) { it * 48000.0 / 8192 }
        val db = DoubleArray(freqs.size) { -90.0 }
        db[432] = -20.0
        val pooler = LogFrequencyPooler(freqs, 300)
        val cols = pooler.pool(db)
        assertEquals(-20.0, cols.max(), 0.0)
        val c = cols.indexOfFirst { it == -20.0 }
        assertEquals(freqs[432], pooler.columnFreqs[c], freqs[432] * 0.02)
        assertEquals(0.0, pooler.position(20.0), 1e-12)
        assertEquals(1.0, pooler.position(20000.0), 1e-12)
    }

    @Test fun ringBufferWraps() {
        val rb = RingBuffer(10)
        rb.write(FloatArray(7) { it.toFloat() })
        rb.write(FloatArray(8) { (it + 7).toFloat() })
        val (data, total) = rb.readLatest(10)
        assertEquals(15L, total)
        assertEquals((5 until 15).map { it.toFloat() }, data.toList())
        assertEquals(14f, rb.popPeak())
        assertEquals(0f, rb.popPeak())
    }
}

class AnalysisTest {
    @Test fun cleanPinkNoiseGivesNoRecommendations() {
        val (_, recs) = analyze(noise(6.0))
        assertEquals(emptyList<String>(), recs.map { it.title })
    }

    @Test fun growingFeedbackIsDetectedWithNotchAdvice() {
        val sig = noise(6.0).plus { 0.003 * 10.0.pow(it / SR * 0.3) * sin(2 * PI * 3150 * it / SR) }
        val (_, recs) = analyze(sig)
        val fb = recs.filter { it.category == "Feedback" }
        assertEquals(1, fb.size)
        val r = fb[0]
        assertEquals(Severity.CRITICAL, r.severity)
        assertTrue(abs(r.freq!! - 3150) < 5)
        assertTrue(r.gainDb!! < 0)
        assertTrue(r.action, r.action.contains("3.15 kHz"))
    }

    @Test fun shortMusicalNoteWithHarmonicsIsNotFeedback() {
        val sig = noise(2.5).plus { i -> (1..4).sumOf { k -> 0.02 / k * sin(2 * PI * 440 * k * i / SR) } }
        val (_, recs) = analyze(sig)
        assertTrue(recs.none { it.category == "Feedback" })
    }

    @Test fun lowMidBuildupSuggestsCutAndIsCalledMuddy() {
        val (_, recs) = analyze(noise(6.0, bumps = listOf(Bump(400.0, 8.0, 1.0))))
        val r = recs.first { it.category == "Tonal" }
        assertEquals("Muddy", r.title)
        assertTrue(r.freq!! in 300.0..520.0)
        assertTrue(r.gainDb!! < 0)
    }

    @Test fun missingPresenceSuggestsBoost() {
        val (_, recs) = analyze(noise(6.0, bumps = listOf(Bump(3000.0, -8.0, 1.2))))
        val r = recs.firstOrNull { it.title == "Lacking presence" }
        assertNotNull(r)
        assertTrue(r!!.gainDb!! > 0)
    }

    @Test fun mainsHumDetectedAndNotReportedAsFeedback() {
        val sig = noise(6.0).plus {
            val t = it / SR
            0.05 * sin(2 * PI * 50 * t) + 0.025 * sin(2 * PI * 100 * t)
        }
        val (an, recs) = analyze(sig)
        assertEquals(50.0, an.hum?.freq)
        assertTrue(recs.any { it.title == "Mains hum at 50 Hz" })
        assertFalse(recs.any { it.category == "Feedback" && abs((it.freq ?: 0.0) - 100) < 10 })
    }

    @Test fun clippingAndLowLevel() {
        val loud = noise(3.0, 0.8f).map { it.coerceIn(-1f, 1f) }.toFloatArray()
        val (_, recs) = analyze(loud)
        assertEquals("Input clipping", recs.first().title)
        assertEquals(Severity.CRITICAL, recs.first().severity)
        val (_, quiet) = analyze(noise(3.0, 0.0003f))
        assertTrue(quiet.any { it.title == "Input level very low" })
    }

    @Test fun demoSignalExercisesEveryDetector() {
        val demo = DemoSignal(SR)
        val (an, recs) = analyze(demo.generate((SR * 6).toInt()), TargetCurve.LIVE_MUSIC)
        val titles = recs.map { it.title }.toSet()
        assertTrue("$titles", "Feedback" in titles)
        assertTrue("$titles", "Mains hum at 60 Hz" in titles)
        assertTrue("$titles", recs.any { it.category == "Tonal" && (it.gainDb ?: 0.0) < 0 && (it.freq ?: 0.0) in 150.0..350.0 })
        val report = an.report("Demo", "now")
        assertTrue(report.contains("RECOMMENDATIONS"))
        assertTrue(report.contains("2.5 kHz"))
    }
}
