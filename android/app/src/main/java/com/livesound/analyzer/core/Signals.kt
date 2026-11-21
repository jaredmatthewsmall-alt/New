package com.livesound.analyzer.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Deterministic RNG (SplitMix64) so demo/test signals are reproducible. */
class SplitMix64(private var state: Long) {
    fun nextLong(): Long {
        state += -0x61c8864680b583ebL
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    fun nextDouble(): Double = (nextLong() ushr 11) * (1.0 / (1L shl 53))

    fun gaussian(): Double {
        val u1 = max(nextDouble(), 1e-300)
        val u2 = nextDouble()
        return sqrt(-2 * ln(u1)) * cos(2 * PI * u2)
    }
}

/** Bell-shaped EQ bump for shaping noise: centre Hz, gain dB, width in octaves. */
data class Bump(val freq: Double, val gainDb: Double, val widthOct: Double)

/** Pink noise (equal energy per octave), unit RMS, optionally shaped by bumps and a tilt curve. */
fun pinkNoise(count: Int, sampleRate: Double, seed: Long = 0, bumps: List<Bump> = emptyList(),
              tilt: ((Double) -> Double)? = null): FloatArray {
    var n = 2
    while (n < count) n = n shl 1
    val rng = SplitMix64(seed)
    val re = DoubleArray(n) { rng.gaussian() }
    val im = DoubleArray(n)
    val fft = Fft(n)
    fft.transform(re, im)
    val df = sampleRate / n
    for (k in 0..n / 2) {
        val f = max(k * df, df)
        var g = 1 / sqrt(f)
        for (b in bumps) {
            val octs = log2(f / b.freq)
            val sigma = b.widthOct / 2.355
            g *= 10.0.pow(b.gainDb * exp(-0.5 * (octs / sigma) * (octs / sigma)) / 20)
        }
        if (tilt != null) g *= 10.0.pow(tilt(f) / 20)
        if (f < 15) g *= (f / 15) * (f / 15)
        re[k] *= g; im[k] *= g
        if (k in 1 until n / 2) { re[n - k] = re[k]; im[n - k] = -im[k] }
    }
    fft.transform(re, im, inverse = true)
    var ss = 0.0
    for (i in 0 until count) ss += re[i] * re[i]
    val rms = max(sqrt(ss / count), 1e-30)
    return FloatArray(count) { (re[it] / rms).toFloat() }
}

/**
 * Synthetic live-room signal so the analyzer can be tried without a microphone: pink noise on
 * the house curve with a muddy bump at 250 Hz and a presence dip at 3 kHz, 60 Hz mains hum with
 * harmonics, and a feedback tone at 2.53 kHz that builds for 8 s then stops for 6 s, repeating.
 */
class DemoSignal(val sampleRate: Double = 48000.0, seed: Long = 1) {
    var feedbackHz = 2530.0
    var humHz = 60.0
    private val noise: FloatArray = pinkNoise(1 shl 19, sampleRate, seed,
        listOf(Bump(250.0, 7.0, 1.0), Bump(3000.0, -6.0, 1.2)), TargetCurve.LIVE_MUSIC::invoke)
        .also { for (i in it.indices) it[i] *= 0.06f }
    private var noisePos = 0
    private var n = 0L

    fun generate(frames: Int): FloatArray {
        val w = 2 * PI / sampleRate
        val out = FloatArray(frames) { i ->
            val idx = n + i
            val t = idx / sampleRate
            var x = noise[(noisePos + i) % noise.size].toDouble()
            x += 0.08 * sin(w * humHz * idx)
            x += 0.04 * sin(w * 2 * humHz * idx)
            x += 0.02 * sin(w * 3 * humHz * idx)
            val cyc = t % 14.0
            if (cyc < 8) x += min(0.002 * 10.0.pow(cyc * 1.6 / 8), 0.12) * sin(w * feedbackHz * idx)
            x.toFloat()
        }
        noisePos = (noisePos + frames) % noise.size
        n += frames
        return out
    }
}
