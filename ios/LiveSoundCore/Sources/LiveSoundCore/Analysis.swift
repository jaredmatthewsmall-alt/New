import Foundation

// MARK: - Recommendations

public enum Severity: Int, Comparable, CaseIterable, Sendable {
    case info = 0, advisory, warning, critical

    public var name: String {
        switch self {
        case .info: return "Info"
        case .advisory: return "Advisory"
        case .warning: return "Warning"
        case .critical: return "Critical"
        }
    }

    public static func < (a: Severity, b: Severity) -> Bool { a.rawValue < b.rawValue }
}

public struct Recommendation: Identifiable, Equatable, Sendable {
    public var category: String   // Feedback, Tonal, Level, Noise
    public var severity: Severity
    public var title: String
    public var action: String
    public var detail: String
    public var freq: Double?
    public var gainDB: Double?
    public var q: Double?

    /// Stable identity so list rows don't jump while values update.
    public var id: String {
        "\(category)|\(title)|\(freq.map { Int(($0 / 10).rounded()) } ?? 0)"
    }
}

// MARK: - Target curves

public enum TargetCurve: String, CaseIterable, Identifiable, Sendable {
    case liveMusic = "Live music (house curve)"
    case flat = "Flat (pink-noise system tuning)"
    case speech = "Speech / spoken word"

    public var id: String { rawValue }

    public func callAsFunction(_ f: Double) -> Double {
        switch self {
        case .flat:
            return 0
        case .liveMusic:
            let low = 6.0 * min(max(log2(200.0 / f) / log2(200.0 / 50.0), 0), 1)
            return low - 1.5 * max(0, log2(f / 1000))
        case .speech:
            return -6.0 * max(0, log2(120.0 / f)) - 1.0 * max(0, log2(f / 5000))
        }
    }
}

func describeExcess(_ f: Double) -> (String, String) {
    switch f {
    case ..<40: return ("Sub-bass rumble", "stage/HVAC rumble, handling noise or mic-stand vibration")
    case ..<100: return ("Boomy low end", "too much kick/bass energy or room bass build-up")
    case ..<250: return ("Woolly / boomy", "proximity effect, bass build-up, close-miked sources")
    case ..<500: return ("Muddy", "low-mid build-up from many open mics and room reflections")
    case ..<1000: return ("Boxy", "cardboard/boxy coloration, often from toms, guitars or vocals")
    case ..<2000: return ("Honky / nasal", "nasal midrange, typical of horns and some vocal mics")
    case ..<5000: return ("Harsh / piercing", "ear-fatiguing upper mids (cymbals, guitars, vocal edge)")
    case ..<9000: return ("Sibilant / brittle", "esses, cymbals and brittle top end")
    default: return ("Fizzy / hissy", "excess air or hiss")
    }
}

func describeDeficit(_ f: Double) -> (String, String) {
    switch f {
    case ..<100: return ("Thin low end", "the mix lacks weight; check subs and kick/bass levels")
    case ..<250: return ("Lacking warmth", "low-mid body missing")
    case ..<2000: return ("Hollow / scooped", "midrange is scooped; instruments may lack body")
    case ..<5000: return ("Lacking presence", "vocals may sound distant or buried in the mix")
    default: return ("Dull / lacking air", "top end is rolled off; mix sounds veiled")
    }
}

// MARK: - Feedback tracking

public final class FeedbackCandidate {
    public internal(set) var freq: Double
    public internal(set) var firstSeen: Double
    public internal(set) var lastSeen: Double
    var hits = 0
    var frames = 0
    var history: [(t: Double, level: Double, prominence: Double)] = []
    public internal(set) var harmonic = false
    var harmonicScore = 0.0

    init(freq: Double, t: Double) {
        self.freq = freq
        firstSeen = t
        lastSeen = t
        frames = 1
    }

    public var duration: Double { lastSeen - firstSeen }

    public func slopeDBPerS(window: Double = 2) -> Double {
        let h = history.filter { $0.t >= lastSeen - window }
        guard h.count >= 4, let t0 = h.first?.t, let t1 = h.last?.t, t1 - t0 >= 0.3 else { return 0 }
        let n = Double(h.count)
        let mt = h.reduce(0) { $0 + ($1.t - t0) } / n
        let ml = h.reduce(0) { $0 + $1.level } / n
        var num = 0.0, den = 0.0
        for p in h {
            let dt = p.t - t0 - mt
            num += dt * (p.level - ml)
            den += dt * dt
        }
        return den > 0 ? num / den : 0
    }

    public var prominence: Double { median(history.suffix(10).map { $0.prominence }) }
}

public enum FeedbackSensitivity: String, CaseIterable, Identifiable, Sendable {
    case low = "Low", medium = "Medium", high = "High"
    public var id: String { rawValue }
    var minProminence: Double {
        switch self {
        case .low: return 18
        case .medium: return 14
        case .high: return 10
        }
    }
}

/// Tracks narrow peaks over time; ones that persist (and especially grow) look like feedback.
/// Peaks with a harmonic series (2f, 3f, f/2) are treated as probable musical notes.
public final class FeedbackTracker {
    public var sensitivity: FeedbackSensitivity
    public var holdS: Double
    public var forgetS: Double
    public var minLevel: Double
    public private(set) var candidates: [FeedbackCandidate] = []

    public init(sensitivity: FeedbackSensitivity = .medium, holdS: Double = 1.0,
                forgetS: Double = 0.6, minLevel: Double = -75) {
        self.sensitivity = sensitivity
        self.holdS = holdS
        self.forgetS = forgetS
        self.minLevel = minLevel
    }

    public func reset() { candidates.removeAll() }

    public func update(freqs: [Double], specDB: [Double], t: Double) {
        let minProm = sensitivity.minProminence
        // Weaker peaks are still useful as evidence of a harmonic series.
        let all = findNarrowPeaks(freqs: freqs, specDB: specDB, minProminence: 9,
                                  minLevel: minLevel - 15, fmin: 40, fmax: 20000, maxPeaks: 60)
        let peaks = all.filter {
            $0.prominence >= minProm && $0.level >= minLevel && $0.freq >= 60 && $0.freq <= 16000
        }
        let df = freqs[1] - freqs[0]
        let peakFreqs = all.map { $0.freq }
        for c in candidates { c.frames += 1 }
        for p in peaks {
            let tol = max(2.5 * df, p.freq * pow(2, 1.0 / 24) - p.freq)
            let match: FeedbackCandidate
            if let m = candidates.first(where: { abs($0.freq - p.freq) <= tol }) {
                match = m
            } else {
                match = FeedbackCandidate(freq: p.freq, t: t)
                candidates.append(match)
            }
            match.freq = 0.8 * match.freq + 0.2 * p.freq
            match.lastSeen = t
            match.hits += 1
            match.history.append((t, p.level, p.prominence))
            if match.history.count > 200 { match.history.removeFirst(50) }
            let hit = hasHarmonicPartner(p.freq, peakFreqs, df) ? 1.0 : 0.0
            match.harmonicScore = 0.8 * match.harmonicScore + 0.2 * hit
            match.harmonic = match.harmonicScore > 0.5
        }
        candidates.removeAll { t - $0.lastSeen > forgetS }
    }

    public func active(now: Double) -> [FeedbackCandidate] {
        candidates.filter { c in
            if now - c.lastSeen > 0.3 { return false }
            if c.duration < holdS { return false }
            if Double(c.hits) / Double(max(c.frames, 1)) < 0.6 { return false }
            if c.harmonic && c.duration < 4 * holdS && c.slopeDBPerS() < 3 { return false }
            return true
        }
    }
}

func hasHarmonicPartner(_ f: Double, _ others: [Double], _ df: Double) -> Bool {
    for ratio in [2.0, 3.0, 0.5, 1.0 / 3.0] {
        let target = f * ratio
        let tol = max(2 * df, target * 0.015)
        if others.contains(where: { abs($0 - target) <= tol }) { return true }
    }
    return false
}

// MARK: - Analyzer

public struct HumInfo: Equatable, Sendable {
    public var freq: Double
    public var prominence: [Double]
    var score: Double
}

/// Feed raw FFT power frames and sample peaks; ask for recommendations.
public final class LiveSoundAnalyzer {
    public let sampleRate: Double
    public let fftSize: Int
    public let enbw: Double
    public let freqs: [Double]
    public var target: TargetCurve
    public var mainsHz: Double?   // nil = auto-detect 50 or 60
    public let fast = ExpAverager(tau: 0.15)
    public let slow = ExpAverager(tau: 4.0)
    public let feedback: FeedbackTracker

    public private(set) var bandDB: [Double]?
    public private(set) var bandDev: [Double]?
    public private(set) var bandOffset: Double = 0
    public private(set) var hum: HumInfo?

    private var peakHist: [(t: Double, v: Double)] = []
    private var rmsHist: [(t: Double, v: Double)] = []
    private var clipUntil = -1.0
    private var t0: Double?
    private var lastT = 0.0

    public init(sampleRate: Double, fftSize: Int, enbw: Double = 1.5,
                target: TargetCurve = .liveMusic, sensitivity: FeedbackSensitivity = .medium,
                mainsHz: Double? = nil) {
        self.sampleRate = sampleRate
        self.fftSize = fftSize
        self.enbw = enbw
        self.target = target
        self.mainsHz = mainsHz
        freqs = (0...(fftSize / 2)).map { Double($0) * sampleRate / Double(fftSize) }
        feedback = FeedbackTracker(sensitivity: sensitivity)
    }

    public var elapsed: Double { t0.map { lastT - $0 } ?? 0 }

    public func reset() {
        fast.reset(); slow.reset(); feedback.reset()
        peakHist.removeAll(); rmsHist.removeAll()
        t0 = nil
        bandDB = nil; bandDev = nil; hum = nil
    }

    public func update(power: [Double], t: Double, peakAbs: Double? = nil, rmsDBFS: Double? = nil) {
        if t0 == nil { t0 = t }
        lastT = t
        let fastP = fast.update(power, t: t)
        let slowP = slow.update(power, t: t)
        feedback.update(freqs: freqs, specDB: fastP.map(powerToDB), t: t)
        if let p = peakAbs {
            peakHist.append((t, p))
            if p >= 0.989 { clipUntil = t + 3 }
        }
        if let r = rmsDBFS { rmsHist.append((t, r)) }
        let cutoff = t - 3
        peakHist.removeAll { $0.t < cutoff }
        rmsHist.removeAll { $0.t < cutoff }
        let bands = bandLevels(freqs: freqs, power: slowP, enbw: enbw)
        bandDB = bands
        computeBandDeviation(bands)
        hum = detectHum(slowP.map(powerToDB))
    }

    /// (peak dBFS, median RMS dBFS) over the last 3 seconds.
    public func levelStats() -> (peak: Double, rms: Double) {
        let pk = peakHist.map { $0.v }.max() ?? 0
        let rms = rmsHist.isEmpty ? -120 : median(rmsHist.map { $0.v })
        return (powerToDB(pk * pk), rms)
    }

    private func computeBandDeviation(_ bands: [Double]) {
        let f = thirdOctaveExact
        var dev = zip(bands, f).map { $0 - target($1) }
        let ref = dev.indices.filter { f[$0] >= 100 && f[$0] <= 10000 && dev[$0].isFinite }.map { dev[$0] }
        let offset = ref.isEmpty ? 0 : median(ref)
        dev = dev.map { $0 - offset }
        var sm = dev
        for i in 1..<(dev.count - 1) { sm[i] = 0.25 * dev[i - 1] + 0.5 * dev[i] + 0.25 * dev[i + 1] }
        bandDev = sm
        bandOffset = offset
    }

    private func detectHum(_ s: [Double]) -> HumInfo? {
        let df = freqs[1] - freqs[0]
        var best: HumInfo?
        for base in mainsHz.map({ [$0] }) ?? [50, 60] {
            var proms: [Double] = []
            for k in 1...3 {
                let fk = base * Double(k)
                let i = Int((fk / df).rounded())
                if i < 3 || i + 3 >= s.count { proms.append(0); continue }
                let pk = s[max(i - 1, 0)...(i + 1)].max()!
                var lo = Int(fk / pow(2, 1.0 / 6) / df)
                var hi = Int((fk * pow(2, 1.0 / 6) / df).rounded(.up)) + 1
                lo = max(min(lo, i - 6), 0)
                hi = min(max(hi, i + 7), s.count)
                var around: [Double] = []
                if lo < i - 2 { around += s[lo..<(i - 2)] }
                if i + 3 < hi { around += s[(i + 3)..<hi] }
                proms.append(pk - median(around))
            }
            let score = proms[0] + 0.5 * (proms[1] + proms[2])
            let found = proms[0] >= 15 || (proms[0] >= 8 && max(proms[1], proms[2]) >= 8)
            if found && (best == nil || score > best!.score) {
                best = HumInfo(freq: base, prominence: proms, score: score)
            }
        }
        return best
    }

    // MARK: Recommendations

    public func recommendations() -> [Recommendation] {
        var recs = levelRecs() + feedbackRecs() + humRecs() + tonalRecs()
        recs.sort { a, b in
            a.severity != b.severity ? a.severity > b.severity : (a.freq ?? 0) < (b.freq ?? 0)
        }
        return recs
    }

    private func levelRecs() -> [Recommendation] {
        var recs: [Recommendation] = []
        let (peak, rms) = levelStats()
        if lastT <= clipUntil {
            recs.append(Recommendation(
                category: "Level", severity: .critical, title: "Input clipping",
                action: "Lower the input gain (or move the phone away from the PA) until peaks stay below -6 dBFS.",
                detail: "The input is hitting full scale. Clipping adds false harmonics, so the "
                    + "spectrum and recommendations are unreliable until fixed."))
        } else if peak > -3 {
            recs.append(Recommendation(
                category: "Level", severity: .warning, title: "Very little headroom",
                action: "Lower the input gain a few dB; aim for peaks around -12 to -6 dBFS.",
                detail: String(format: "Peaks are reaching %.1f dBFS.", peak)))
        }
        if !rmsHist.isEmpty && rms < -60 {
            recs.append(Recommendation(
                category: "Level", severity: .advisory, title: "Input level very low",
                action: "Raise the input gain or move the mic closer to the PA/listening area.",
                detail: String(format: "Average level is %.0f dBFS; tonal analysis needs roughly "
                    + "-50 dBFS or more to stay above the noise floor.", rms)))
        }
        return recs
    }

    private func feedbackRecs() -> [Recommendation] {
        var recs: [Recommendation] = []
        let humFreqs = hum.map { h in (1...5).map { h.freq * Double($0) } } ?? []
        for c in feedback.active(now: lastT) {
            if humFreqs.contains(where: { abs(c.freq - $0) < max(3, freqs[1] * 1.5) }) { continue }
            let slope = c.slopeDBPerS()
            let prom = c.prominence
            let rising = slope > 1.5
            let sev: Severity
            let title: String
            if rising || prom >= 25 {
                (sev, title) = (.critical, "Feedback")
            } else if c.harmonic {
                (sev, title) = (.advisory, "Sustained tone / possible ring")
            } else {
                (sev, title) = (.warning, "Ringing / feedback risk")
            }
            let cut = min(max(prom.rounded() / 2, 3), 12)
            let q = 10.0  // typical feedback-notch width (~1/7 octave)
            let trend = rising ? String(format: "rising %+.1f dB/s", slope)
                : slope < -1.5 ? String(format: "falling %+.1f dB/s", slope) : "steady"
            let action = "Notch \(formatHz(c.freq)): cut \(fmt(cut, "%.1f")) dB, Q \(fmt(q, "%.0f")) "
                + "(31-band GEQ: pull \(nearestGEQBand(c.freq)))"
            var detail = "Narrow peak \(fmt(prom, "%.0f")) dB above its surroundings for "
                + "\(fmt(c.duration, "%.1f")) s, \(trend). Nearest note \(noteName(c.freq)). "
                + "Also consider lowering the offending mic/monitor send, repositioning the mic "
                + "or wedge, or reducing gain."
            if c.harmonic {
                detail += " It has a harmonic partner, so it may be a held musical note; "
                    + "confirm by ear before cutting."
            }
            recs.append(Recommendation(category: "Feedback", severity: sev, title: title,
                                       action: action, detail: detail,
                                       freq: c.freq, gainDB: -cut, q: q))
        }
        return recs
    }

    private func humRecs() -> [Recommendation] {
        guard let h = hum else { return [] }
        let f = h.freq, p = h.prominence
        return [Recommendation(
            category: "Noise", severity: .warning, title: String(format: "Mains hum at %.0f Hz", f),
            action: String(format: "Check for ground loops: use balanced cables, a DI ground-lift, "
                + "keep audio away from power/lighting cables. As a stopgap, HPF or notch at "
                + "%.0f/%.0f/%.0f Hz on the affected channel.", f, 2 * f, 3 * f),
            detail: String(format: "Steady peaks at %.0f Hz (+%.0f dB) and harmonics (+%.0f/+%.0f dB).",
                           f, p[0], p[1], p[2]),
            freq: f)]
    }

    private func tonalRecs() -> [Recommendation] {
        guard let dev = bandDev, elapsed >= 2 else { return [] }
        let (_, rms) = levelStats()
        if !rmsHist.isEmpty && rms < -60 { return [] }
        let f = thirdOctaveExact
        var recs: [Recommendation] = []
        let sub = f.indices.filter { f[$0] >= 20 && f[$0] <= 40 }.map { dev[$0] }.filter { $0.isFinite }
        if let m = sub.max(), m > 6 {
            recs.append(Recommendation(
                category: "Tonal", severity: .advisory, title: "Low-frequency rumble",
                action: "Engage high-pass filters (80-100 Hz on vocals/guitars, 30-40 Hz on the main bus).",
                detail: String(format: "Energy below 40 Hz is %.0f dB above the target. This wastes "
                    + "amplifier power and muddies the low end.", m),
                freq: 40))
        }
        for (sign, thresh) in [(1.0, 3.0), (-1.0, 4.0)] {
            let mask = f.indices.map { f[$0] >= 45 && f[$0] <= 13000 && dev[$0].isFinite && sign * dev[$0] > thresh }
            for (lo, hi) in runs(mask) {
                let region = Array(lo...hi)
                let k = region.max { sign * dev[$0] < sign * dev[$1] }!
                let amount = sign * dev[k]
                let bwOct = max(Double(hi - lo + 1) / 3, 1.0 / 3)
                let wsum = region.reduce(0) { $0 + abs(dev[$1]) }
                let fc = exp(region.reduce(0) { $0 + log(f[$1]) * abs(dev[$1]) } / wsum)
                let q = (qFromOctaves(bwOct) * 10).rounded() / 10
                let gain: Double
                let name: String, why: String
                let sev: Severity
                let action: String
                if sign > 0 {
                    gain = -min((amount * 0.7 * 2).rounded() / 2, 9)
                    (name, why) = describeExcess(fc)
                    sev = amount >= 6 ? .warning : .advisory
                    action = "Cut \(fmt(abs(gain), "%.1f")) dB at \(formatHz(fc)), Q \(fmt(q, "%.1f"))"
                } else {
                    gain = min((amount * 0.5 * 2).rounded() / 2, 4)
                    (name, why) = describeDeficit(fc)
                    sev = .advisory
                    action = "Boost \(fmt(gain, "%.1f")) dB at \(formatHz(fc)), Q \(fmt(q, "%.1f")) "
                        + "(or cut the surrounding bands instead)"
                }
                var detail = "\(formatHz(thirdOctaveNominal[lo]))-\(formatHz(thirdOctaveNominal[hi])) is "
                    + "\(fmt(abs(amount), "%.1f")) dB \(sign > 0 ? "above" : "below") the "
                    + "'\(target.rawValue)' target: \(why)."
                if sign > 0 && fc < 500 { detail += " Also try HPFs on channels that don't need low end." }
                if sign > 0, let h = hum,
                   (1...3).contains(where: { f[lo] / 1.13 <= h.freq * Double($0) && h.freq * Double($0) <= f[hi] * 1.13 }) {
                    detail += " Part of this is the mains hum - fix that first and re-check."
                }
                if sign < 0 && fc < 100 {
                    detail += " Boosting lows costs a lot of amp power; check sub level/polarity first."
                }
                recs.append(Recommendation(category: "Tonal", severity: sev, title: name, action: action,
                                           detail: detail, freq: fc, gainDB: gain, q: q))
            }
        }
        return recs
    }

    /// Plain-text report for sharing.
    public func report(inputName: String, date: Date = Date()) -> String {
        let (peak, rms) = levelStats()
        var lines = ["Live Sound Analyzer report - \(date)", "",
                     "Input:        \(inputName)",
                     "Sample rate:  \(Int(sampleRate)) Hz   FFT: \(fftSize)",
                     "Target curve: \(target.rawValue)",
                     String(format: "Levels:       avg %.1f dBFS, peak %.1f dBFS", rms, peak),
                     "Mains hum:    \(hum.map { "\(Int($0.freq)) Hz" } ?? "not detected")",
                     "", "RECOMMENDATIONS", String(repeating: "-", count: 60)]
        let recs = recommendations()
        if recs.isEmpty { lines.append("No issues detected.") }
        for r in recs {
            lines += ["[\(r.severity.name)] \(r.category): \(r.title)", "    Do:  \(r.action)",
                      "    Why: \(r.detail)", ""]
        }
        if let b = bandDB, let d = bandDev {
            lines += ["", "1/3-OCTAVE BANDS (long-term average)", String(repeating: "-", count: 60),
                      "      Band  Level dBFS  vs target dB"]
            for i in b.indices {
                let name = formatHz(thirdOctaveNominal[i])
                lines.append(String(repeating: " ", count: max(10 - name.count, 0)) + name
                             + String(format: " %11.1f %+13.1f", b[i], d[i]))
            }
        }
        return lines.joined(separator: "\n") + "\n"
    }
}

@inline(__always) func fmt(_ v: Double, _ spec: String) -> String { String(format: spec, v) }

func runs(_ mask: [Bool]) -> [(Int, Int)] {
    var out: [(Int, Int)] = []
    var start: Int?
    for (i, m) in mask.enumerated() {
        if m && start == nil { start = i }
        if !m, let s = start { out.append((s, i - 1)); start = nil }
    }
    if let s = start { out.append((s, mask.count - 1)) }
    return out
}
