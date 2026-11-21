import Foundation

/// Small deterministic RNG (SplitMix64) so demo/test signals are reproducible.
public struct SplitMix64: RandomNumberGenerator {
    private var state: UInt64
    public init(seed: UInt64) { state = seed }
    public mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }

    mutating func gaussian() -> Double {
        let u1 = max(Double.random(in: 0..<1, using: &self), 1e-300)
        let u2 = Double.random(in: 0..<1, using: &self)
        return sqrt(-2 * log(u1)) * cos(2 * Double.pi * u2)
    }
}

/// A bell-shaped EQ bump applied when shaping noise: centre Hz, gain dB, width in octaves.
public struct Bump: Sendable {
    public var freq: Double, gainDB: Double, widthOct: Double
    public init(_ freq: Double, _ gainDB: Double, _ widthOct: Double) {
        self.freq = freq; self.gainDB = gainDB; self.widthOct = widthOct
    }
}

/// Pink noise (equal energy per octave), unit RMS, optionally shaped by bumps and a tilt curve.
public func pinkNoise(count: Int, sampleRate: Double, seed: UInt64 = 0, bumps: [Bump] = [],
                      tilt: ((Double) -> Double)? = nil) -> [Float] {
    var n = 2
    while n < count { n <<= 1 }
    var rng = SplitMix64(seed: seed)
    var re = (0..<n).map { _ in rng.gaussian() }
    var im = [Double](repeating: 0, count: n)
    let fft = FFT(size: n)
    fft.transform(&re, &im)
    let df = sampleRate / Double(n)
    for k in 0...(n / 2) {
        let f = max(Double(k) * df, df)
        var g = 1 / sqrt(f)
        for b in bumps {
            let octs = log2(f / b.freq)
            let sigma = b.widthOct / 2.355
            g *= pow(10, b.gainDB * exp(-0.5 * (octs / sigma) * (octs / sigma)) / 20)
        }
        if let tilt { g *= pow(10, tilt(f) / 20) }
        if f < 15 { g *= (f / 15) * (f / 15) }
        re[k] *= g; im[k] *= g
        if k > 0 && k < n / 2 {  // keep Hermitian symmetry -> real output
            re[n - k] = re[k]; im[n - k] = -im[k]
        }
    }
    fft.transform(&re, &im, inverse: true)
    var out = Array(re[0..<count])
    let rms = sqrt(out.reduce(0) { $0 + $1 * $1 } / Double(count))
    out = out.map { $0 / max(rms, 1e-30) }
    return out.map { Float($0) }
}

/// Synthetic live-room signal so the analyzer can be tried without a microphone:
/// pink noise on the house curve with a muddy bump at 250 Hz and a presence dip at 3 kHz,
/// 60 Hz mains hum with harmonics, and a feedback tone at 2.53 kHz that builds for 8 s
/// then disappears for 6 s, repeating.
public final class DemoSignal {
    public let sampleRate: Double
    public var feedbackHz = 2530.0
    public var humHz = 60.0
    private let noise: [Float]
    private var noisePos = 0
    private var n = 0

    public init(sampleRate: Double = 48000, seed: UInt64 = 1) {
        self.sampleRate = sampleRate
        let count = 1 << 19  // ~11 s loop at 48 kHz
        noise = pinkNoise(count: count, sampleRate: sampleRate, seed: seed,
                          bumps: [Bump(250, 7, 1.0), Bump(3000, -6, 1.2)],
                          tilt: { TargetCurve.liveMusic($0) }).map { 0.06 * $0 }
    }

    public func generate(_ frames: Int) -> [Float] {
        var out = [Float](repeating: 0, count: frames)
        let w = 2 * Double.pi / sampleRate
        for i in 0..<frames {
            let t = Double(n + i) / sampleRate
            var x = Double(noise[(noisePos + i) % noise.count])
            x += 0.05 * sin(w * humHz * Double(n + i))
            x += 0.025 * sin(w * 2 * humHz * Double(n + i))
            x += 0.012 * sin(w * 3 * humHz * Double(n + i))
            let cyc = t.truncatingRemainder(dividingBy: 14)
            if cyc < 8 {
                let amp = min(0.002 * pow(10, cyc * 1.6 / 8), 0.12)
                x += amp * sin(w * feedbackHz * Double(n + i))
            }
            out[i] = Float(x)
        }
        noisePos = (noisePos + frames) % noise.count
        n += frames
        return out
    }
}
