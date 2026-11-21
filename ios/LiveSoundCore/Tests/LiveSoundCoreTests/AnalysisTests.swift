import XCTest
@testable import LiveSoundCore

private let SR = 48000.0
private let N = 8192
private let HOP = 1600  // ~30 analysis frames per second, like the app

/// Feed a signal through the analyzer frame by frame, the way the app does.
private func analyze(_ signal: [Float], target: TargetCurve = .flat,
                 sensitivity: FeedbackSensitivity = .medium) -> (LiveSoundAnalyzer, [Recommendation]) {
    let sp = Spectrum(sampleRate: SR, fftSize: N)
    let an = LiveSoundAnalyzer(sampleRate: SR, fftSize: N, enbw: sp.enbw, target: target,
                               sensitivity: sensitivity)
    var t = 0.0
    var end = N
    while end <= signal.count {
        let block = Array(signal[(end - N)..<end])
        let new = signal[(end - HOP)..<end]
        t += Double(HOP) / SR
        let peak = Double(new.map { abs($0) }.max()!)
        let ms = new.reduce(0.0) { $0 + Double($1) * Double($1) } / Double(HOP)
        an.update(power: sp.power(block), t: t, peakAbs: peak, rmsDBFS: powerToDB(ms))
        end += HOP
    }
    return (an, an.recommendations())
}

private func noise(_ seconds: Double, level: Float = 0.05, bumps: [Bump] = [], seed: UInt64 = 0) -> [Float] {
    pinkNoise(count: Int(SR * seconds), sampleRate: SR, seed: seed, bumps: bumps).map { level * $0 }
}

private func add(_ a: [Float], _ f: (Int) -> Double) -> [Float] {
    a.enumerated().map { $0.element + Float(f($0.offset)) }
}

final class DSPTests: XCTestCase {
    func testSineReadsCorrectDBFSAndFrequency() {
        let sp = Spectrum(sampleRate: SR, fftSize: N)
        let x = (0..<N).map { Float(0.5 * sin(2 * Double.pi * 1003.7 * Double($0) / SR)) }
        let d = sp.power(x).map(powerToDB)
        let i = d.indices.max { d[$0] < d[$1] }!
        XCTAssertLessThan(abs(sp.freqs[i] - 1003.7), sp.binHz)
        XCTAssertEqual(d[i], -6.02, accuracy: 1.5)
        let peaks = findNarrowPeaks(freqs: sp.freqs, specDB: d, minLevel: -100)
        XCTAssertLessThan(abs(peaks[0].freq - 1003.7), 1.0)
    }

    func testFFTRoundTrip() {
        let fft = FFT(size: 64)
        let orig = (0..<64).map { sin(Double($0) * 0.3) + 0.1 * Double($0 % 5) }
        var re = orig, im = [Double](repeating: 0, count: 64)
        fft.transform(&re, &im)
        fft.transform(&re, &im, inverse: true)
        for i in 0..<64 { XCTAssertEqual(re[i] / 64, orig[i], accuracy: 1e-9) }
    }

    func testPinkNoiseIsFlatInThirdOctaves() {
        let sp = Spectrum(sampleRate: SR, fftSize: N)
        let x = noise(6)
        var acc = [Double](repeating: 0, count: N / 2 + 1)
        var count = 0
        var i = 0
        while i + N <= x.count {
            let p = sp.power(Array(x[i..<(i + N)]))
            for k in acc.indices { acc[k] += p[k] }
            count += 1
            i += N / 2
        }
        let power = acc.map { $0 / Double(count) }
        let bands = bandLevels(freqs: sp.freqs, power: power, enbw: sp.enbw)
        let sel = thirdOctaveExact.indices.filter { thirdOctaveExact[$0] >= 50 && thirdOctaveExact[$0] <= 16000 }
        let vals = sel.map { bands[$0] }
        XCTAssertLessThan(vals.max()! - vals.min()!, 4.0)
    }

    func testHelpers() {
        XCTAssertEqual(noteName(440), "A4 +0c")
        XCTAssertEqual(nearestGEQBand(2530), "2.5 kHz")
        XCTAssertEqual(formatHz(3150), "3.15 kHz")
        XCTAssertEqual(formatHz(250), "250 Hz")
        XCTAssertEqual(qFromOctaves(1), 1.414, accuracy: 0.01)
        XCTAssertEqual(axisLabel(2500), "2.5k")
    }

    func testLogPoolerKeepsSingleBinSpikes() {
        let freqs = (0...4096).map { Double($0) * 48000 / 8192 }
        var db = [Double](repeating: -90, count: freqs.count)
        db[432] = -20   // ~2.53 kHz, one bin
        let pooler = LogFrequencyPooler(freqs: freqs, columns: 300)
        let cols = pooler.pool(db)
        XCTAssertEqual(cols.max(), -20)
        let c = cols.firstIndex(of: -20)!
        XCTAssertEqual(pooler.columnFreqs[c], freqs[432], accuracy: freqs[432] * 0.02)
        XCTAssertEqual(pooler.position(of: 20), 0, accuracy: 1e-12)
        XCTAssertEqual(pooler.position(of: 20000), 1, accuracy: 1e-12)
    }

    func testRingBufferWraps() {
        let rb = RingBuffer(size: 10)
        rb.write((0..<7).map(Float.init))
        rb.write((7..<15).map(Float.init))
        let (data, total) = rb.readLatest(10)
        XCTAssertEqual(total, 15)
        XCTAssertEqual(data, (5..<15).map(Float.init))
        XCTAssertEqual(rb.popPeak(), 14)
        XCTAssertEqual(rb.popPeak(), 0)
    }
}

final class AnalysisTests: XCTestCase {
    func testCleanPinkNoiseGivesNoRecommendations() {
        let (_, recs) = analyze(noise(6))
        XCTAssertEqual(recs.map(\.title), [])
    }

    func testGrowingFeedbackIsDetectedWithNotchAdvice() {
        let sig = add(noise(6)) { (i: Int) -> Double in
            0.003 * pow(10, Double(i) / SR * 0.3) * sin(2 * Double.pi * 3150 * Double(i) / SR)
        }
        let (_, recs) = analyze(sig)
        let fb = recs.filter { $0.category == "Feedback" }
        XCTAssertEqual(fb.count, 1)
        guard let r = fb.first else { return }
        XCTAssertEqual(r.severity, .critical)
        XCTAssertLessThan(abs(r.freq! - 3150), 5)
        XCTAssertLessThan(r.gainDB!, 0)
        XCTAssertTrue(r.action.contains("3.15 kHz"), r.action)
    }

    func testShortMusicalNoteWithHarmonicsIsNotFeedback() {
        let sig = add(noise(2.5)) { (i: Int) -> Double in
            var v = 0.0
            for k in 1...4 {
                let h = Double(k)
                v += 0.02 / h * sin(2 * Double.pi * 440 * h * Double(i) / SR)
            }
            return v
        }
        let (_, recs) = analyze(sig)
        XCTAssertTrue(recs.filter { $0.category == "Feedback" }.isEmpty)
    }

    func testLowMidBuildupSuggestsCutAndIsCalledMuddy() {
        let (_, recs) = analyze(noise(6, bumps: [Bump(400, 8, 1.0)]))
        let tonal = recs.filter { $0.category == "Tonal" }
        XCTAssertFalse(tonal.isEmpty)
        guard let r = tonal.first else { return }
        XCTAssertEqual(r.title, "Muddy")
        XCTAssert(r.freq! > 300 && r.freq! < 520)
        XCTAssertLessThan(r.gainDB!, 0)
    }

    func testMissingPresenceSuggestsBoost() {
        let (_, recs) = analyze(noise(6, bumps: [Bump(3000, -8, 1.2)]))
        let r = recs.first { $0.title == "Lacking presence" }
        XCTAssertNotNil(r)
        XCTAssertGreaterThan(r?.gainDB ?? 0, 0)
    }

    func testMainsHumDetectedAndNotReportedAsFeedback() {
        let sig = add(noise(6)) { (i: Int) -> Double in
            let t = Double(i) / SR
            return 0.05 * sin(2 * Double.pi * 50 * t) + 0.025 * sin(2 * Double.pi * 100 * t)
        }
        let (an, recs) = analyze(sig)
        XCTAssertEqual(an.hum?.freq, 50)
        XCTAssertTrue(recs.contains { $0.title == "Mains hum at 50 Hz" })
        XCTAssertFalse(recs.contains { $0.category == "Feedback" && abs(($0.freq ?? 0) - 100) < 10 })
    }

    func testClippingAndLowLevel() {
        let loud = noise(3, level: 0.8).map { min(max($0, -1), 1) }
        let (_, recs) = analyze(loud)
        XCTAssertEqual(recs.first?.title, "Input clipping")
        XCTAssertEqual(recs.first?.severity, .critical)
        let (_, quiet) = analyze(noise(3, level: 0.0003))
        XCTAssertTrue(quiet.contains { $0.title == "Input level very low" })
    }

    func testDemoSignalExercisesEveryDetector() {
        let demo = DemoSignal(sampleRate: SR)
        let (an, recs) = analyze(demo.generate(Int(SR * 6)), target: .liveMusic)
        let titles = Set(recs.map(\.title))
        XCTAssertTrue(titles.contains("Feedback"), "\(titles)")
        XCTAssertTrue(titles.contains("Mains hum at 60 Hz"), "\(titles)")
        let muddyCut = recs.contains { (r: Recommendation) -> Bool in
            let f = r.freq ?? 0
            let g = r.gainDB ?? 0
            return r.category == "Tonal" && g < 0 && f > 150 && f < 350
        }
        XCTAssertTrue(muddyCut, "\(titles)")
        let report = an.report(inputName: "Demo")
        XCTAssertTrue(report.contains("RECOMMENDATIONS"))
        XCTAssertTrue(report.contains("2.5 kHz"))
    }
}
