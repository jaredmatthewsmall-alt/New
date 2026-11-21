import Foundation

// MARK: - Constants and helpers

/// ISO 266 nominal 1/3-octave centres (20 Hz ... 20 kHz): the 31-band graphic EQ sliders.
public let thirdOctaveNominal: [Double] = [
    20, 25, 31.5, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630,
    800, 1000, 1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000, 10000,
    12500, 16000, 20000,
]
public let thirdOctaveExact: [Double] = (-17...13).map { 1000.0 * pow(2.0, Double($0) / 3.0) }

private let noteNames = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]

@inline(__always) public func powerToDB(_ p: Double) -> Double { 10.0 * log10(p + 1e-20) }

public func formatHz(_ f: Double) -> String {
    if f >= 1000 {
        var s = String(format: "%.2f", f / 1000)
        while s.hasSuffix("0") { s.removeLast() }
        if s.hasSuffix(".") { s.removeLast() }
        return "\(s) kHz"
    }
    return String(format: "%.0f Hz", f)
}

/// Short axis label: 20, 100, 1k, 2.5k ...
public func axisLabel(_ f: Double) -> String {
    if f >= 1000 {
        let k = f / 1000
        return k == k.rounded() ? "\(Int(k))k" : String(format: "%gk", k)
    }
    return f == f.rounded() ? "\(Int(f))" : String(format: "%g", f)
}

/// Nearest 31-band graphic-EQ slider label.
public func nearestGEQBand(_ f: Double) -> String {
    var best = 0
    var bestD = Double.infinity
    for (i, c) in thirdOctaveExact.enumerated() {
        let d = abs(log2(c / f))
        if d < bestD { bestD = d; best = i }
    }
    return formatHz(thirdOctaveNominal[best])
}

/// Nearest musical note, e.g. "D#7 +28c" (A4 = 440 Hz).
public func noteName(_ f: Double) -> String {
    guard f > 0 else { return "" }
    let midi = 69 + 12 * log2(f / 440)
    let n = Int(midi.rounded())
    let cents = Int(((midi - Double(n)) * 100).rounded())
    let octave = Int(floor(Double(n) / 12)) - 1
    let idx = ((n % 12) + 12) % 12
    return "\(noteNames[idx])\(octave) \(cents >= 0 ? "+" : "")\(cents)c"
}

/// Bandwidth in octaves -> parametric EQ Q.
public func qFromOctaves(_ bw: Double) -> Double {
    let b = max(bw, 1e-3)
    return sqrt(pow(2, b)) / (pow(2, b) - 1)
}

func median(_ xs: [Double]) -> Double {
    guard !xs.isEmpty else { return .nan }
    let s = xs.sorted()
    let m = s.count / 2
    return s.count % 2 == 1 ? s[m] : 0.5 * (s[m - 1] + s[m])
}

// MARK: - Spectrum

/// Hann-windowed FFT. Levels are dBFS: a full-scale sine reads 0 dB at its frequency.
public final class Spectrum {
    public let sampleRate: Double
    public let fftSize: Int
    public let freqs: [Double]
    public let binHz: Double
    /// Equivalent noise bandwidth of the window in bins (1.5 for Hann).
    public let enbw: Double
    private let window: [Double]
    private let scale: Double
    private let fft: FFT
    private var re: [Double]
    private var im: [Double]

    public init(sampleRate: Double, fftSize: Int) {
        self.sampleRate = sampleRate
        self.fftSize = fftSize
        fft = FFT(size: fftSize)
        // numpy.hanning (symmetric) for parity with the desktop version.
        window = (0..<fftSize).map { 0.5 - 0.5 * cos(2 * Double.pi * Double($0) / Double(fftSize - 1)) }
        let wsum = window.reduce(0, +)
        scale = 2.0 / wsum
        enbw = Double(fftSize) * window.reduce(0) { $0 + $1 * $1 } / (wsum * wsum)
        binHz = sampleRate / Double(fftSize)
        freqs = (0...(fftSize / 2)).map { Double($0) * sampleRate / Double(fftSize) }
        re = [Double](repeating: 0, count: fftSize)
        im = [Double](repeating: 0, count: fftSize)
    }

    /// Linear power spectrum (sine amplitude^2 per bin), fftSize/2 + 1 values.
    public func power(_ block: [Float]) -> [Double] {
        let n = fftSize
        let offset = max(block.count - n, 0)
        let pad = max(n - block.count, 0)
        var mean = 0.0
        for i in offset..<block.count { mean += Double(block[i]) }
        mean /= Double(max(block.count - offset, 1))
        for i in 0..<n {
            let x = i < pad ? 0.0 : Double(block[offset + i - pad]) - mean
            re[i] = x * window[i]
            im[i] = 0
        }
        fft.transform(&re, &im)
        var out = [Double](repeating: 0, count: n / 2 + 1)
        let s2 = scale * scale
        for k in 0...(n / 2) { out[k] = (re[k] * re[k] + im[k] * im[k]) * s2 }
        return out
    }
}

/// Exponential averager on linear power with a time constant in seconds.
public final class ExpAverager {
    public var tau: Double
    public private(set) var value: [Double]?
    private var lastT: Double?

    public init(tau: Double) { self.tau = tau }

    public func reset() { value = nil; lastT = nil }

    @discardableResult
    public func update(_ x: [Double], t: Double) -> [Double] {
        if var v = value, v.count == x.count, tau > 0 {
            let dt = max(t - (lastT ?? t), 1e-3)
            let a = exp(-dt / tau)
            let b = 1 - a
            for i in 0..<v.count { v[i] = a * v[i] + b * x[i] }
            value = v
        } else {
            value = x
        }
        lastT = t
        return value!
    }
}

/// Peak-hold trace in dB that decays after `holdS` seconds.
public final class PeakHold {
    public var holdS: Double
    public var decay: Double
    public private(set) var value: [Double]?
    private var age: [Double] = []
    private var lastT = 0.0

    public init(holdS: Double = 2, decayDBPerS: Double = 12) {
        self.holdS = holdS
        decay = decayDBPerS
    }

    public func reset() { value = nil }

    @discardableResult
    public func update(_ xdb: [Double], t: Double) -> [Double] {
        guard var v = value, v.count == xdb.count else {
            value = xdb
            age = [Double](repeating: 0, count: xdb.count)
            lastT = t
            return xdb
        }
        let dt = max(t - lastT, 0)
        lastT = t
        for i in 0..<v.count {
            age[i] += dt
            if age[i] > holdS { v[i] -= decay * dt }
            if xdb[i] >= v[i] { v[i] = xdb[i]; age[i] = 0 }
        }
        value = v
        return v
    }
}

/// Linear interpolation of a uniformly sampled spectrum (bin k at k*df).
@inline(__always) func interpUniform(_ y: [Double], df: Double, at f: Double) -> Double {
    let pos = f / df
    if pos <= 0 { return y[0] }
    let i = Int(pos)
    if i >= y.count - 1 { return y[y.count - 1] }
    let frac = pos - Double(i)
    return y[i] * (1 - frac) + y[i + 1] * frac
}

/// 1/3-octave band levels (dB) from a linear FFT power spectrum.
/// Bands narrower than a bin are handled by interpolating the power density.
public func bandLevels(freqs: [Double], power: [Double], enbw: Double = 1.5,
                       centers: [Double] = thirdOctaveExact) -> [Double] {
    let df = freqs[1] - freqs[0]
    let fmax = freqs[freqs.count - 1]
    let half = pow(2.0, 1.0 / 6.0)
    return centers.map { fc in
        let lo = fc / half
        let hi = min(fc * half, fmax)
        if lo >= fmax { return .nan }
        var sum = 0.0
        let r = hi / lo
        for k in 0..<16 {
            let f = lo * pow(r, Double(k) / 15.0)
            sum += interpUniform(power, df: df, at: f)
        }
        let dens = sum / 16
        return powerToDB(dens * (hi - lo) / df / enbw)
    }
}

// MARK: - Narrow peak detection

public struct Peak: Equatable {
    public var freq: Double
    public var level: Double
    public var prominence: Double
    public var widthHz: Double
}

/// Narrow, prominent spectral peaks (feedback / ringing / hum candidates).
/// Prominence is measured against the median of the surrounding 1/3 octave.
public func findNarrowPeaks(freqs: [Double], specDB s: [Double], minProminence: Double = 12,
                            minLevel: Double = -80, fmin: Double = 40, fmax: Double = 16000,
                            maxPeaks: Int = 20) -> [Peak] {
    let n = s.count
    let df = freqs[1] - freqs[0]
    let i0 = max(Int((fmin / df).rounded(.up)), 2)
    let i1 = min(Int((fmax / df).rounded(.up)), n - 3)
    guard i1 > i0 else { return [] }
    var cand: [Int] = []
    let pre = 0.5 * minProminence
    for i in i0..<i1 {
        let v = s[i]
        guard v > s[i - 1], v >= s[i + 1], v > minLevel else { continue }
        // Pre-screen: a narrow peak stands well above the bins 6 away (Hann main lobe is +-2 bins).
        let side = max(s[max(i - 6, 0)], s[min(i + 6, n - 1)])
        if v - side >= pre { cand.append(i) }
    }
    if cand.isEmpty { return [] }
    cand.sort { s[$0] > s[$1] }
    if cand.count > 60 { cand = Array(cand[0..<60]) }

    let half = pow(2.0, 1.0 / 6.0)
    var peaks: [Peak] = []
    for i in cand {
        let f = freqs[i]
        var lo = Int((f / half / df).rounded(.up))
        var hi = Int((f * half / df).rounded(.up))
        lo = max(min(lo, i - 8), 0)
        hi = min(max(hi, i + 9), n)
        var around: [Double] = []
        around.reserveCapacity(hi - lo)
        if lo < i - 3 { around += s[lo..<(i - 3)] }
        if i + 4 < hi { around += s[(i + 4)..<hi] }
        if around.isEmpty { continue }
        let prom = s[i] - median(around)
        if prom < minProminence { continue }
        let thr = s[i] - 6
        var a = i
        while a > 0 && s[a] > thr && i - a < 64 { a -= 1 }
        var b = i
        while b < n - 1 && s[b] > thr && b - i < 64 { b += 1 }
        let width = Double(b - a) * df
        if width > max(5 * df, f * 0.06) { continue }
        let y0 = s[i - 1], y1 = s[i], y2 = s[i + 1]
        let denom = y0 - 2 * y1 + y2
        var delta = denom != 0 ? 0.5 * (y0 - y2) / denom : 0
        delta = min(max(delta, -0.5), 0.5)
        peaks.append(Peak(freq: f + delta * df, level: y1 - 0.25 * (y0 - y2) * delta,
                          prominence: prom, widthHz: width))
    }
    peaks.sort { $0.prominence > $1.prominence }
    return Array(peaks.prefix(maxPeaks))
}

// MARK: - Ring buffer

/// Thread-safe circular buffer of mono samples, written by the audio thread.
public final class RingBuffer: @unchecked Sendable {
    public let size: Int
    private var buf: [Float]
    private var pos = 0
    private var _total = 0
    private var _peak: Float = 0
    private let lock = NSLock()

    public init(size: Int) {
        self.size = size
        buf = [Float](repeating: 0, count: size)
    }

    public func write(_ x: UnsafeBufferPointer<Float>) {
        lock.lock(); defer { lock.unlock() }
        var p: Float = 0
        for v in x { p = max(p, abs(v)) }
        _peak = max(_peak, p)
        let n = x.count
        if n >= size {
            for i in 0..<size { buf[i] = x[n - size + i] }
            pos = 0
        } else {
            for i in 0..<n { buf[(pos + i) % size] = x[i] }
            pos = (pos + n) % size
        }
        _total += n
    }

    public func write(_ x: [Float]) { x.withUnsafeBufferPointer { write($0) } }

    /// Newest `n` samples (oldest first) and the running sample count.
    public func readLatest(_ count: Int) -> ([Float], Int) {
        lock.lock(); defer { lock.unlock() }
        let n = min(count, size)
        var out = [Float](repeating: 0, count: n)
        let start = (pos - n + size) % size
        for i in 0..<n { out[i] = buf[(start + i) % size] }
        return (out, _total)
    }

    public var total: Int { lock.lock(); defer { lock.unlock() }; return _total }

    /// Absolute sample peak since the last call, so short clips are never missed.
    public func popPeak() -> Float {
        lock.lock(); defer { lock.unlock() }
        let p = _peak
        _peak = 0
        return p
    }
}

// MARK: - Display helper

/// Resamples a linear-frequency dB spectrum onto `columns` log-spaced points for drawing.
/// Columns wider than an FFT bin take the maximum of their bins, so a single-bin
/// feedback spike is never lost; narrower columns (low end) are interpolated.
public struct LogFrequencyPooler {
    public let columns: Int
    public let fmin: Double
    public let fmax: Double
    /// Centre frequency of each column.
    public let columnFreqs: [Double]
    private let starts: [Int]
    private let ends: [Int]
    private let df: Double

    public init(freqs: [Double], columns: Int, fmin: Double = 20, fmax: Double = 20000) {
        self.columns = columns
        self.fmin = fmin
        self.fmax = fmax
        df = freqs[1] - freqs[0]
        let l0 = log10(fmin), l1 = log10(fmax)
        let step = (l1 - l0) / Double(columns - 1)
        columnFreqs = (0..<columns).map { pow(10, l0 + Double($0) * step) }
        var s: [Int] = [], e: [Int] = []
        let last = freqs.count - 1
        for c in 0..<columns {
            let lo = pow(10, l0 + (Double(c) - 0.5) * step)
            let hi = pow(10, l0 + (Double(c) + 0.5) * step)
            s.append(min(Int((lo / df).rounded(.up)), last))
            e.append(min(Int((hi / df).rounded(.up)), last + 1))
        }
        starts = s
        ends = e
    }

    public func pool(_ db: [Double]) -> [Double] {
        (0..<columns).map { c in
            if ends[c] > starts[c] {
                var m = -Double.infinity
                for i in starts[c]..<ends[c] { m = max(m, db[i]) }
                return m
            }
            return interpUniform(db, df: df, at: columnFreqs[c])
        }
    }

    /// Horizontal position (0...1) of frequency `f` on the log axis.
    public func position(of f: Double) -> Double {
        (log10(f) - log10(fmin)) / (log10(fmax) - log10(fmin))
    }
}
