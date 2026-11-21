package com.livesound.analyzer.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

enum class Severity(val label: String) { INFO("Info"), ADVISORY("Advisory"), WARNING("Warning"), CRITICAL("Critical") }

data class Recommendation(
    val category: String,   // Feedback, Tonal, Level, Noise
    val severity: Severity,
    val title: String,
    val action: String,
    val detail: String = "",
    val freq: Double? = null,
    val gainDb: Double? = null,
    val q: Double? = null,
) {
    /** Stable identity so list rows don't jump while values update. */
    val id: String get() = "$category|$title|${freq?.let { (it / 10).roundToInt() } ?: 0}"
}

enum class TargetCurve(val label: String) {
    LIVE_MUSIC("Live music (house curve)"),
    FLAT("Flat (pink-noise system tuning)"),
    SPEECH("Speech / spoken word");

    operator fun invoke(f: Double): Double = when (this) {
        FLAT -> 0.0
        LIVE_MUSIC -> 6.0 * (log2(200.0 / f) / log2(200.0 / 50.0)).coerceIn(0.0, 1.0) -
            1.5 * max(0.0, log2(f / 1000))
        SPEECH -> -6.0 * max(0.0, log2(120.0 / f)) - 1.0 * max(0.0, log2(f / 5000))
    }
}

internal fun describeExcess(f: Double): Pair<String, String> = when {
    f < 40 -> "Sub-bass rumble" to "stage/HVAC rumble, handling noise or mic-stand vibration"
    f < 100 -> "Boomy low end" to "too much kick/bass energy or room bass build-up"
    f < 250 -> "Woolly / boomy" to "proximity effect, bass build-up, close-miked sources"
    f < 500 -> "Muddy" to "low-mid build-up from many open mics and room reflections"
    f < 1000 -> "Boxy" to "cardboard/boxy coloration, often from toms, guitars or vocals"
    f < 2000 -> "Honky / nasal" to "nasal midrange, typical of horns and some vocal mics"
    f < 5000 -> "Harsh / piercing" to "ear-fatiguing upper mids (cymbals, guitars, vocal edge)"
    f < 9000 -> "Sibilant / brittle" to "esses, cymbals and brittle top end"
    else -> "Fizzy / hissy" to "excess air or hiss"
}

internal fun describeDeficit(f: Double): Pair<String, String> = when {
    f < 100 -> "Thin low end" to "the mix lacks weight; check subs and kick/bass levels"
    f < 250 -> "Lacking warmth" to "low-mid body missing"
    f < 2000 -> "Hollow / scooped" to "midrange is scooped; instruments may lack body"
    f < 5000 -> "Lacking presence" to "vocals may sound distant or buried in the mix"
    else -> "Dull / lacking air" to "top end is rolled off; mix sounds veiled"
}

// ---------------------------------------------------------------- feedback

class FeedbackCandidate internal constructor(var freq: Double, t: Double) {
    var firstSeen = t; internal set
    var lastSeen = t; internal set
    internal var hits = 0
    internal var frames = 1
    internal val history = ArrayList<Triple<Double, Double, Double>>() // t, level, prominence
    var harmonic = false; internal set
    internal var harmonicScore = 0.0

    val duration get() = lastSeen - firstSeen

    fun slopeDbPerS(window: Double = 2.0): Double {
        val h = history.filter { it.first >= lastSeen - window }
        if (h.size < 4) return 0.0
        val t0 = h.first().first
        if (h.last().first - t0 < 0.3) return 0.0
        val mt = h.sumOf { it.first - t0 } / h.size
        val ml = h.sumOf { it.second } / h.size
        var num = 0.0
        var den = 0.0
        for (p in h) {
            val dt = p.first - t0 - mt
            num += dt * (p.second - ml)
            den += dt * dt
        }
        return if (den > 0) num / den else 0.0
    }

    val prominence get() = median(history.takeLast(10).map { it.third })
}

enum class FeedbackSensitivity(val label: String, val minProminence: Double) {
    LOW("Low", 18.0), MEDIUM("Medium", 14.0), HIGH("High", 10.0)
}

/**
 * Tracks narrow peaks over time; ones that persist (and especially grow) look like feedback.
 * Peaks with a harmonic series (2f, 3f, f/2) are treated as probable musical notes.
 */
class FeedbackTracker(var sensitivity: FeedbackSensitivity = FeedbackSensitivity.MEDIUM,
                      val holdS: Double = 1.0, val forgetS: Double = 0.6, val minLevel: Double = -75.0) {
    val candidates = ArrayList<FeedbackCandidate>()

    fun reset() = candidates.clear()

    fun update(freqs: DoubleArray, specDb: DoubleArray, t: Double) {
        val minProm = sensitivity.minProminence
        val all = findNarrowPeaks(freqs, specDb, 9.0, minLevel - 15, 40.0, 20000.0, 60)
        val peaks = all.filter { it.prominence >= minProm && it.level >= minLevel && it.freq in 60.0..16000.0 }
        val df = freqs[1] - freqs[0]
        val peakFreqs = all.map { it.freq }
        candidates.forEach { it.frames++ }
        for (p in peaks) {
            val tol = max(2.5 * df, p.freq * 2.0.pow(1.0 / 24) - p.freq)
            val match = candidates.firstOrNull { abs(it.freq - p.freq) <= tol }
                ?: FeedbackCandidate(p.freq, t).also { candidates.add(it) }
            match.freq = 0.8 * match.freq + 0.2 * p.freq
            match.lastSeen = t
            match.hits++
            match.history.add(Triple(t, p.level, p.prominence))
            if (match.history.size > 200) repeat(50) { match.history.removeAt(0) }
            val hit = if (hasHarmonicPartner(p.freq, peakFreqs, df)) 1.0 else 0.0
            match.harmonicScore = 0.8 * match.harmonicScore + 0.2 * hit
            match.harmonic = match.harmonicScore > 0.5
        }
        candidates.removeAll { t - it.lastSeen > forgetS }
    }

    fun active(now: Double): List<FeedbackCandidate> = candidates.filter { c ->
        now - c.lastSeen <= 0.3 && c.duration >= holdS &&
            c.hits.toDouble() / max(c.frames, 1) >= 0.6 &&
            !(c.harmonic && c.duration < 4 * holdS && c.slopeDbPerS() < 3)
    }
}

internal fun hasHarmonicPartner(f: Double, others: List<Double>, df: Double): Boolean {
    for (ratio in doubleArrayOf(2.0, 3.0, 0.5, 1.0 / 3.0)) {
        val target = f * ratio
        val tol = max(2 * df, target * 0.015)
        if (others.any { abs(it - target) <= tol }) return true
    }
    return false
}

// ---------------------------------------------------------------- analyzer

data class HumInfo(val freq: Double, val prominence: List<Double>, internal val score: Double)

/** Feed raw FFT power frames and sample peaks; ask for recommendations. */
class LiveSoundAnalyzer(
    val sampleRate: Double,
    val fftSize: Int,
    val enbw: Double = 1.5,
    var target: TargetCurve = TargetCurve.LIVE_MUSIC,
    sensitivity: FeedbackSensitivity = FeedbackSensitivity.MEDIUM,
    var mainsHz: Double? = null,   // null = auto-detect 50 or 60
) {
    val freqs = DoubleArray(fftSize / 2 + 1) { it * sampleRate / fftSize }
    val fast = ExpAverager(0.15)
    val slow = ExpAverager(4.0)
    val feedback = FeedbackTracker(sensitivity)

    var bandDb: DoubleArray? = null; private set
    var bandDev: DoubleArray? = null; private set
    var bandOffset = 0.0; private set
    var hum: HumInfo? = null; private set

    private val peakHist = ArrayDeque<Pair<Double, Double>>()
    private val rmsHist = ArrayDeque<Pair<Double, Double>>()
    private var clipUntil = -1.0
    private var t0: Double? = null
    private var lastT = 0.0

    val elapsed get() = t0?.let { lastT - it } ?: 0.0

    fun reset() {
        fast.reset(); slow.reset(); feedback.reset()
        peakHist.clear(); rmsHist.clear()
        t0 = null
        bandDb = null; bandDev = null; hum = null
    }

    fun update(power: DoubleArray, t: Double, peakAbs: Double? = null, rmsDbfs: Double? = null) {
        if (t0 == null) t0 = t
        lastT = t
        val fastP = fast.update(power, t)
        val slowP = slow.update(power, t)
        feedback.update(freqs, DoubleArray(fastP.size) { powerToDb(fastP[it]) }, t)
        if (peakAbs != null) {
            peakHist.addLast(t to peakAbs)
            if (peakAbs >= 0.989) clipUntil = t + 3
        }
        if (rmsDbfs != null) rmsHist.addLast(t to rmsDbfs)
        val cutoff = t - 3
        while (peakHist.isNotEmpty() && peakHist.first().first < cutoff) peakHist.removeFirst()
        while (rmsHist.isNotEmpty() && rmsHist.first().first < cutoff) rmsHist.removeFirst()
        val bands = bandLevels(freqs, slowP, enbw)
        bandDb = bands
        computeBandDeviation(bands)
        hum = detectHum(DoubleArray(slowP.size) { powerToDb(slowP[it]) })
    }

    /** (peak dBFS, median RMS dBFS) over the last 3 seconds. */
    fun levelStats(): Pair<Double, Double> {
        val pk = peakHist.maxOfOrNull { it.second } ?: 0.0
        val rms = if (rmsHist.isEmpty()) -120.0 else median(rmsHist.map { it.second })
        return powerToDb(pk * pk) to rms
    }

    private fun computeBandDeviation(bands: DoubleArray) {
        val f = THIRD_OCTAVE_EXACT
        val dev = DoubleArray(bands.size) { bands[it] - target(f[it]) }
        val ref = dev.indices.filter { f[it] in 100.0..10000.0 && dev[it].isFinite() }.map { dev[it] }
        val offset = if (ref.isEmpty()) 0.0 else median(ref)
        for (i in dev.indices) dev[i] -= offset
        val sm = dev.copyOf()
        for (i in 1 until dev.size - 1) sm[i] = 0.25 * dev[i - 1] + 0.5 * dev[i] + 0.25 * dev[i + 1]
        bandDev = sm
        bandOffset = offset
    }

    private fun detectHum(s: DoubleArray): HumInfo? {
        val df = freqs[1] - freqs[0]
        var best: HumInfo? = null
        for (base in mainsHz?.let { listOf(it) } ?: listOf(50.0, 60.0)) {
            val proms = ArrayList<Double>()
            for (k in 1..3) {
                val fk = base * k
                val i = (fk / df).roundToInt()
                if (i < 3 || i + 3 >= s.size) { proms.add(0.0); continue }
                val pk = maxOf(s[i - 1], s[i], s[i + 1])
                val lo = max(min((fk / 2.0.pow(1.0 / 6) / df).toInt(), i - 6), 0)
                val hi = min(max(kotlin.math.ceil(fk * 2.0.pow(1.0 / 6) / df).toInt() + 1, i + 7), s.size)
                val around = ArrayList<Double>()
                for (j in lo until i - 2) around.add(s[j])
                for (j in i + 3 until hi) around.add(s[j])
                proms.add(pk - median(around))
            }
            val score = proms[0] + 0.5 * (proms[1] + proms[2])
            val found = proms[0] >= 15 || (proms[0] >= 8 && max(proms[1], proms[2]) >= 8)
            if (found && (best == null || score > best.score)) best = HumInfo(base, proms, score)
        }
        return best
    }

    // ------------------------------------------------------------ recommendations

    fun recommendations(): List<Recommendation> =
        (levelRecs() + feedbackRecs() + humRecs() + tonalRecs())
            .sortedWith(compareByDescending<Recommendation> { it.severity }.thenBy { it.freq ?: 0.0 })

    private fun levelRecs(): List<Recommendation> {
        val recs = ArrayList<Recommendation>()
        val (peak, rms) = levelStats()
        if (lastT <= clipUntil) {
            recs += Recommendation("Level", Severity.CRITICAL, "Input clipping",
                "Lower the input gain (or move the tablet away from the PA) until peaks stay below -6 dBFS.",
                "The input is hitting full scale. Clipping adds false harmonics, so the spectrum and " +
                    "recommendations are unreliable until fixed.")
        } else if (peak > -3) {
            recs += Recommendation("Level", Severity.WARNING, "Very little headroom",
                "Lower the input gain a few dB; aim for peaks around -12 to -6 dBFS.",
                fmt("Peaks are reaching %.1f dBFS.", peak))
        }
        if (rmsHist.isNotEmpty() && rms < -60) {
            recs += Recommendation("Level", Severity.ADVISORY, "Input level very low",
                "Raise the input gain or move the mic closer to the PA/listening area.",
                fmt("Average level is %.0f dBFS; tonal analysis needs roughly -50 dBFS or more to " +
                    "stay above the noise floor.", rms))
        }
        return recs
    }

    private fun feedbackRecs(): List<Recommendation> {
        val humFreqs = hum?.let { h -> (1..5).map { h.freq * it } } ?: emptyList()
        return feedback.active(lastT).filter { c ->
            humFreqs.none { abs(c.freq - it) < max(3.0, freqs[1] * 1.5) }
        }.map { c ->
            val slope = c.slopeDbPerS()
            val prom = c.prominence
            val rising = slope > 1.5
            val (sev, title) = when {
                rising || prom >= 25 -> Severity.CRITICAL to "Feedback"
                c.harmonic -> Severity.ADVISORY to "Sustained tone / possible ring"
                else -> Severity.WARNING to "Ringing / feedback risk"
            }
            val cut = (Math.round(prom) / 2.0).coerceIn(3.0, 12.0)
            val q = 10.0 // typical feedback-notch width (~1/7 octave)
            val trend = when {
                rising -> fmt("rising %+.1f dB/s", slope)
                slope < -1.5 -> fmt("falling %+.1f dB/s", slope)
                else -> "steady"
            }
            var detail = "Narrow peak ${fmt("%.0f", prom)} dB above its surroundings for " +
                "${fmt("%.1f", c.duration)} s, $trend. Nearest note ${noteName(c.freq)}. Also consider " +
                "lowering the offending mic/monitor send, repositioning the mic or wedge, or reducing gain."
            if (c.harmonic) detail += " It has a harmonic partner, so it may be a held musical note; " +
                "confirm by ear before cutting."
            Recommendation("Feedback", sev, title,
                "Notch ${formatHz(c.freq)}: cut ${fmt("%.1f", cut)} dB, Q ${fmt("%.0f", q)} " +
                    "(31-band GEQ: pull ${nearestGeqBand(c.freq)})",
                detail, c.freq, -cut, q)
        }
    }

    private fun humRecs(): List<Recommendation> {
        val h = hum ?: return emptyList()
        val f = h.freq
        val p = h.prominence
        return listOf(Recommendation("Noise", Severity.WARNING, fmt("Mains hum at %.0f Hz", f),
            fmt("Check for ground loops: use balanced cables, a DI ground-lift, keep audio away from " +
                "power/lighting cables. As a stopgap, HPF or notch at %.0f/%.0f/%.0f Hz on the affected " +
                "channel.", f, 2 * f, 3 * f),
            fmt("Steady peaks at %.0f Hz (+%.0f dB) and harmonics (+%.0f/+%.0f dB).", f, p[0], p[1], p[2]),
            f))
    }

    private fun tonalRecs(): List<Recommendation> {
        val dev = bandDev ?: return emptyList()
        if (elapsed < 2) return emptyList()
        val (_, rms) = levelStats()
        if (rmsHist.isNotEmpty() && rms < -60) return emptyList()
        val f = THIRD_OCTAVE_EXACT
        val recs = ArrayList<Recommendation>()
        val sub = f.indices.filter { f[it] in 20.0..40.0 && dev[it].isFinite() }.map { dev[it] }
        val subMax = sub.maxOrNull()
        if (subMax != null && subMax > 6) {
            recs += Recommendation("Tonal", Severity.ADVISORY, "Low-frequency rumble",
                "Engage high-pass filters (80-100 Hz on vocals/guitars, 30-40 Hz on the main bus).",
                fmt("Energy below 40 Hz is %.0f dB above the target. This wastes amplifier power and " +
                    "muddies the low end.", subMax), 40.0)
        }
        for ((sign, thresh) in listOf(1.0 to 3.0, -1.0 to 4.0)) {
            val mask = BooleanArray(f.size) { f[it] in 45.0..13000.0 && dev[it].isFinite() && sign * dev[it] > thresh }
            for ((lo, hi) in runs(mask)) {
                val region = lo..hi
                val k = region.maxBy { sign * dev[it] }
                val amount = sign * dev[k]
                val bwOct = max((hi - lo + 1) / 3.0, 1.0 / 3)
                val wsum = region.sumOf { abs(dev[it]) }
                val fc = exp(region.sumOf { ln(f[it]) * abs(dev[it]) } / wsum)
                val q = Math.round(qFromOctaves(bwOct) * 10) / 10.0
                val gain: Double
                val nameWhy: Pair<String, String>
                val sev: Severity
                val action: String
                if (sign > 0) {
                    gain = -min(Math.round(amount * 0.7 * 2) / 2.0, 9.0)
                    nameWhy = describeExcess(fc)
                    sev = if (amount >= 6) Severity.WARNING else Severity.ADVISORY
                    action = "Cut ${fmt("%.1f", abs(gain))} dB at ${formatHz(fc)}, Q ${fmt("%.1f", q)}"
                } else {
                    gain = min(Math.round(amount * 0.5 * 2) / 2.0, 4.0)
                    nameWhy = describeDeficit(fc)
                    sev = Severity.ADVISORY
                    action = "Boost ${fmt("%.1f", gain)} dB at ${formatHz(fc)}, Q ${fmt("%.1f", q)} " +
                        "(or cut the surrounding bands instead)"
                }
                var detail = "${formatHz(THIRD_OCTAVE_NOMINAL[lo])}-${formatHz(THIRD_OCTAVE_NOMINAL[hi])} is " +
                    "${fmt("%.1f", abs(amount))} dB ${if (sign > 0) "above" else "below"} the " +
                    "'${target.label}' target: ${nameWhy.second}."
                if (sign > 0 && fc < 500) detail += " Also try HPFs on channels that don't need low end."
                val h = hum
                if (sign > 0 && h != null && (1..3).any { h.freq * it in (f[lo] / 1.13)..(f[hi] * 1.13) }) {
                    detail += " Part of this is the mains hum - fix that first and re-check."
                }
                if (sign < 0 && fc < 100) {
                    detail += " Boosting lows costs a lot of amp power; check sub level/polarity first."
                }
                recs += Recommendation("Tonal", sev, nameWhy.first, action, detail, fc, gain, q)
            }
        }
        return recs
    }

    /** Plain-text report for sharing. */
    fun report(inputName: String, date: String): String {
        val (peak, rms) = levelStats()
        val lines = arrayListOf(
            "Live Sound Analyzer report - $date", "",
            "Input:        $inputName",
            "Sample rate:  ${sampleRate.toInt()} Hz   FFT: $fftSize",
            "Target curve: ${target.label}",
            fmt("Levels:       avg %.1f dBFS, peak %.1f dBFS", rms, peak),
            "Mains hum:    ${hum?.let { "${it.freq.toInt()} Hz" } ?: "not detected"}",
            "", "RECOMMENDATIONS", "-".repeat(60),
        )
        val recs = recommendations()
        if (recs.isEmpty()) lines += "No issues detected."
        for (r in recs) {
            lines += listOf("[${r.severity.label}] ${r.category}: ${r.title}", "    Do:  ${r.action}",
                "    Why: ${r.detail}", "")
        }
        val b = bandDb
        val d = bandDev
        if (b != null && d != null) {
            lines += listOf("", "1/3-OCTAVE BANDS (long-term average)", "-".repeat(60),
                "      Band  Level dBFS  vs target dB")
            for (i in b.indices) {
                lines += formatHz(THIRD_OCTAVE_NOMINAL[i]).padStart(10) + fmt(" %11.1f %+13.1f", b[i], d[i])
            }
        }
        return lines.joinToString("\n") + "\n"
    }
}

internal fun runs(mask: BooleanArray): List<Pair<Int, Int>> {
    val out = ArrayList<Pair<Int, Int>>()
    var start = -1
    for (i in mask.indices) {
        if (mask[i] && start < 0) start = i
        if (!mask[i] && start >= 0) { out += start to i - 1; start = -1 }
    }
    if (start >= 0) out += start to mask.size - 1
    return out
}
