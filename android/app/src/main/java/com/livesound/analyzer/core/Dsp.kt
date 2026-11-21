package com.livesound.analyzer.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** ISO 266 nominal 1/3-octave centres (20 Hz - 20 kHz): the 31-band graphic EQ sliders. */
val THIRD_OCTAVE_NOMINAL = doubleArrayOf(
    20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0, 200.0, 250.0, 315.0, 400.0,
    500.0, 630.0, 800.0, 1000.0, 1250.0, 1600.0, 2000.0, 2500.0, 3150.0, 4000.0, 5000.0, 6300.0,
    8000.0, 10000.0, 12500.0, 16000.0, 20000.0,
)
val THIRD_OCTAVE_EXACT = DoubleArray(31) { 1000.0 * 2.0.pow((it - 17) / 3.0) }

private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

fun log2(x: Double) = ln(x) / ln(2.0)
fun powerToDb(p: Double) = 10.0 * log10(p + 1e-20)
internal fun fmt(spec: String, vararg a: Any?) = String.format(Locale.US, spec, *a)

fun formatHz(f: Double): String {
    if (f >= 1000) {
        var s = fmt("%.2f", f / 1000)
        s = s.trimEnd('0').trimEnd('.')
        return "$s kHz"
    }
    return fmt("%.0f Hz", f)
}

/** Short axis label: 20, 100, 1k, 2.5k ... */
fun axisLabel(f: Double): String {
    if (f >= 1000) {
        val k = f / 1000
        return if (k == floor(k)) "${k.toInt()}k" else "${fmt("%.1f", k).trimEnd('0').trimEnd('.')}k"
    }
    return if (f == floor(f)) "${f.toInt()}" else fmt("%.1f", f)
}

fun nearestGeqBand(f: Double): String {
    var best = 0
    var bestD = Double.MAX_VALUE
    THIRD_OCTAVE_EXACT.forEachIndexed { i, c ->
        val d = abs(log2(c / f))
        if (d < bestD) { bestD = d; best = i }
    }
    return formatHz(THIRD_OCTAVE_NOMINAL[best])
}

/** Nearest musical note, e.g. "D#7 +28c" (A4 = 440 Hz). */
fun noteName(f: Double): String {
    if (f <= 0) return ""
    val midi = 69 + 12 * log2(f / 440)
    val n = midi.roundToInt()
    val cents = ((midi - n) * 100).roundToInt()
    val octave = Math.floorDiv(n, 12) - 1
    return "${NOTE_NAMES[Math.floorMod(n, 12)]}$octave ${if (cents >= 0) "+" else ""}${cents}c"
}

fun qFromOctaves(bw: Double): Double {
    val b = max(bw, 1e-3)
    return sqrt(2.0.pow(b)) / (2.0.pow(b) - 1)
}

internal fun median(xs: DoubleArray): Double {
    if (xs.isEmpty()) return Double.NaN
    val s = xs.sortedArray()
    val m = s.size / 2
    return if (s.size % 2 == 1) s[m] else 0.5 * (s[m - 1] + s[m])
}

internal fun median(xs: List<Double>) = median(xs.toDoubleArray())

/** Hann-windowed FFT. Levels are dBFS: a full-scale sine reads 0 dB at its frequency. */
class Spectrum(val sampleRate: Double, val fftSize: Int) {
    val freqs = DoubleArray(fftSize / 2 + 1) { it * sampleRate / fftSize }
    val binHz = sampleRate / fftSize
    private val window = DoubleArray(fftSize) { 0.5 - 0.5 * cos(2 * Math.PI * it / (fftSize - 1)) }
    private val scale: Double
    /** Equivalent noise bandwidth of the window in bins (1.5 for Hann). */
    val enbw: Double
    private val fft = Fft(fftSize)
    private val re = DoubleArray(fftSize)
    private val im = DoubleArray(fftSize)

    init {
        val wsum = window.sum()
        scale = 2.0 / wsum
        enbw = fftSize * window.sumOf { it * it } / (wsum * wsum)
    }

    /** Linear power spectrum (sine amplitude^2 per bin), fftSize/2 + 1 values. */
    fun power(block: FloatArray): DoubleArray {
        val n = fftSize
        val offset = max(block.size - n, 0)
        val pad = max(n - block.size, 0)
        var mean = 0.0
        for (i in offset until block.size) mean += block[i]
        mean /= max(block.size - offset, 1)
        for (i in 0 until n) {
            val x = if (i < pad) 0.0 else block[offset + i - pad] - mean
            re[i] = x * window[i]
            im[i] = 0.0
        }
        fft.transform(re, im)
        val s2 = scale * scale
        return DoubleArray(n / 2 + 1) { (re[it] * re[it] + im[it] * im[it]) * s2 }
    }
}

/** Exponential averager on linear power with a time constant in seconds. */
class ExpAverager(var tau: Double) {
    var value: DoubleArray? = null
        private set
    private var lastT: Double? = null

    fun reset() { value = null; lastT = null }

    fun update(x: DoubleArray, t: Double): DoubleArray {
        val v = value
        if (v != null && v.size == x.size && tau > 0) {
            val dt = max(t - (lastT ?: t), 1e-3)
            val a = exp(-dt / tau)
            val b = 1 - a
            for (i in v.indices) v[i] = a * v[i] + b * x[i]
        } else {
            value = x.copyOf()
        }
        lastT = t
        return value!!
    }
}

/** Peak-hold trace in dB that decays after [holdS] seconds. */
class PeakHold(var holdS: Double = 2.0, var decay: Double = 12.0) {
    var value: DoubleArray? = null
        private set
    private var age = DoubleArray(0)
    private var lastT = 0.0

    fun reset() { value = null }

    fun update(xDb: DoubleArray, t: Double): DoubleArray {
        val v = value
        if (v == null || v.size != xDb.size) {
            value = xDb.copyOf()
            age = DoubleArray(xDb.size)
            lastT = t
            return value!!
        }
        val dt = max(t - lastT, 0.0)
        lastT = t
        for (i in v.indices) {
            age[i] += dt
            if (age[i] > holdS) v[i] -= decay * dt
            if (xDb[i] >= v[i]) { v[i] = xDb[i]; age[i] = 0.0 }
        }
        return v
    }
}

internal fun interpUniform(y: DoubleArray, df: Double, f: Double): Double {
    val pos = f / df
    if (pos <= 0) return y[0]
    val i = pos.toInt()
    if (i >= y.size - 1) return y[y.size - 1]
    val frac = pos - i
    return y[i] * (1 - frac) + y[i + 1] * frac
}

/** 1/3-octave band levels (dB) from a linear FFT power spectrum. */
fun bandLevels(freqs: DoubleArray, power: DoubleArray, enbw: Double = 1.5,
               centers: DoubleArray = THIRD_OCTAVE_EXACT): DoubleArray {
    val df = freqs[1] - freqs[0]
    val fmax = freqs[freqs.size - 1]
    val half = 2.0.pow(1.0 / 6.0)
    return DoubleArray(centers.size) { i ->
        val fc = centers[i]
        val lo = fc / half
        val hi = min(fc * half, fmax)
        if (lo >= fmax) {
            Double.NaN
        } else {
            val r = hi / lo
            var sum = 0.0
            for (k in 0 until 16) sum += interpUniform(power, df, lo * r.pow(k / 15.0))
            powerToDb(sum / 16 * (hi - lo) / df / enbw)
        }
    }
}

data class Peak(val freq: Double, val level: Double, val prominence: Double, val widthHz: Double)

/** Narrow, prominent spectral peaks (feedback / ringing / hum candidates). */
fun findNarrowPeaks(freqs: DoubleArray, s: DoubleArray, minProminence: Double = 12.0,
                    minLevel: Double = -80.0, fmin: Double = 40.0, fmax: Double = 16000.0,
                    maxPeaks: Int = 20): List<Peak> {
    val n = s.size
    val df = freqs[1] - freqs[0]
    val i0 = max(ceil(fmin / df).toInt(), 2)
    val i1 = min(ceil(fmax / df).toInt(), n - 3)
    if (i1 <= i0) return emptyList()
    val pre = 0.5 * minProminence
    var cand = ArrayList<Int>()
    for (i in i0 until i1) {
        val v = s[i]
        if (!(v > s[i - 1] && v >= s[i + 1] && v > minLevel)) continue
        val side = max(s[max(i - 6, 0)], s[min(i + 6, n - 1)])
        if (v - side >= pre) cand.add(i)
    }
    if (cand.isEmpty()) return emptyList()
    cand.sortByDescending { s[it] }
    if (cand.size > 60) cand = ArrayList(cand.subList(0, 60))

    val half = 2.0.pow(1.0 / 6.0)
    val peaks = ArrayList<Peak>()
    for (i in cand) {
        val f = freqs[i]
        val lo = max(min(ceil(f / half / df).toInt(), i - 8), 0)
        val hi = min(max(ceil(f * half / df).toInt(), i + 9), n)
        val around = ArrayList<Double>(hi - lo)
        for (k in lo until i - 3) around.add(s[k])
        for (k in i + 4 until hi) around.add(s[k])
        if (around.isEmpty()) continue
        val prom = s[i] - median(around)
        if (prom < minProminence) continue
        val thr = s[i] - 6
        var a = i
        while (a > 0 && s[a] > thr && i - a < 64) a--
        var b = i
        while (b < n - 1 && s[b] > thr && b - i < 64) b++
        val width = (b - a) * df
        if (width > max(5 * df, f * 0.06)) continue
        val y0 = s[i - 1]; val y1 = s[i]; val y2 = s[i + 1]
        val denom = y0 - 2 * y1 + y2
        val delta = (if (denom != 0.0) 0.5 * (y0 - y2) / denom else 0.0).coerceIn(-0.5, 0.5)
        peaks.add(Peak(f + delta * df, y1 - 0.25 * (y0 - y2) * delta, prom, width))
    }
    peaks.sortByDescending { it.prominence }
    return peaks.take(maxPeaks)
}

/**
 * Resamples a linear-frequency dB spectrum onto [columns] log-spaced points for drawing.
 * Wide columns take the max of their bins so single-bin feedback spikes are never lost.
 */
class LogFrequencyPooler(freqs: DoubleArray, val columns: Int, val fmin: Double = 20.0,
                         val fmax: Double = 20000.0) {
    private val df = freqs[1] - freqs[0]
    val columnFreqs: DoubleArray
    private val starts = IntArray(columns)
    private val ends = IntArray(columns)

    init {
        val l0 = log10(fmin)
        val step = (log10(fmax) - l0) / (columns - 1)
        columnFreqs = DoubleArray(columns) { 10.0.pow(l0 + it * step) }
        val last = freqs.size - 1
        for (c in 0 until columns) {
            val lo = 10.0.pow(l0 + (c - 0.5) * step)
            val hi = 10.0.pow(l0 + (c + 0.5) * step)
            starts[c] = min(ceil(lo / df).toInt(), last)
            ends[c] = min(ceil(hi / df).toInt(), last + 1)
        }
    }

    fun pool(db: DoubleArray): DoubleArray = DoubleArray(columns) { c ->
        if (ends[c] > starts[c]) {
            var m = Double.NEGATIVE_INFINITY
            for (i in starts[c] until ends[c]) m = max(m, db[i])
            m
        } else {
            interpUniform(db, df, columnFreqs[c])
        }
    }

    /** Horizontal position (0..1) of [f] on the log axis. */
    fun position(f: Double) = (log10(f) - log10(fmin)) / (log10(fmax) - log10(fmin))
}

/** Thread-safe circular buffer of mono samples, written by the audio thread. */
class RingBuffer(val size: Int) {
    private val buf = FloatArray(size)
    private var pos = 0
    private var totalCount = 0L
    private var peak = 0f
    private val lock = Any()

    fun write(x: FloatArray, count: Int = x.size) = synchronized(lock) {
        var p = 0f
        for (i in 0 until count) p = max(p, abs(x[i]))
        peak = max(peak, p)
        if (count >= size) {
            System.arraycopy(x, count - size, buf, 0, size)
            pos = 0
        } else {
            for (i in 0 until count) buf[(pos + i) % size] = x[i]
            pos = (pos + count) % size
        }
        totalCount += count
    }

    /** Newest [count] samples (oldest first) and the running sample total. */
    fun readLatest(count: Int): Pair<FloatArray, Long> = synchronized(lock) {
        val n = min(count, size)
        val start = (pos - n + size) % size
        FloatArray(n) { buf[(start + it) % size] } to totalCount
    }

    val total: Long get() = synchronized(lock) { totalCount }

    /** Absolute sample peak since the last call, so short clips are never missed. */
    fun popPeak(): Float = synchronized(lock) { val p = peak; peak = 0f; p }
}
