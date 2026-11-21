import AVFoundation
import LiveSoundCore
import SwiftUI
import UIKit

enum InputSource: String, CaseIterable, Identifiable {
    case microphone = "Microphone"
    case demo = "Demo signal"
    var id: String { rawValue }
}

enum DisplayAveraging: String, CaseIterable, Identifiable {
    case fast = "Fast (0.1 s)", medium = "Medium (0.5 s)", slow = "Slow (2 s)", verySlow = "Very slow (5 s)"
    var id: String { rawValue }
    var tau: Double {
        switch self {
        case .fast: return 0.1
        case .medium: return 0.5
        case .slow: return 2
        case .verySlow: return 5
        }
    }
}

enum MainsSetting: String, CaseIterable, Identifiable {
    case auto = "Auto-detect", hz50 = "50 Hz", hz60 = "60 Hz"
    var id: String { rawValue }
    var hz: Double? {
        switch self {
        case .auto: return nil
        case .hz50: return 50
        case .hz60: return 60
        }
    }
}

struct FeedbackMarker: Identifiable, Equatable {
    let id: String
    let freq: Double
    let severity: Severity
}

/// Owns audio capture + analysis and publishes everything the views draw.
@MainActor
final class AnalyzerModel: ObservableObject {
    static let fftSizes = [4096, 8192, 16384]
    static let dbMin = -110.0
    static let dbMax = 0.0

    // MARK: Settings
    @Published var source: InputSource = .microphone { didSet { restartIfRunning() } }
    @Published var preferredInputUID: String? { didSet { restartIfRunning() } }
    @Published var fftSize = 8192 { didSet { restartIfRunning() } }
    @Published var averaging: DisplayAveraging = .medium { didSet { dispAvg.tau = averaging.tau } }
    @Published var target: TargetCurve = .liveMusic { didSet { analyzer?.target = target; lastRecRefresh = 0 } }
    @Published var sensitivity: FeedbackSensitivity = .medium { didSet { analyzer?.feedback.sensitivity = sensitivity } }
    @Published var mains: MainsSetting = .auto { didSet { analyzer?.mainsHz = mains.hz } }
    @Published var frozen = false

    // MARK: Published state
    @Published private(set) var running = false
    @Published var errorMessage: String?
    @Published var showPermissionAlert = false
    @Published var showSilenceAlert = false
    @Published private(set) var live: [Double] = []
    @Published private(set) var longTerm: [Double] = []
    @Published private(set) var peakHoldTrace: [Double] = []
    @Published private(set) var bands: [Double] = []
    @Published private(set) var bandDev: [Double] = []
    @Published private(set) var targetLine: [Double] = []
    @Published private(set) var spectrogram: CGImage?
    @Published private(set) var recommendations: [Recommendation] = []
    @Published private(set) var markers: [FeedbackMarker] = []
    @Published private(set) var rmsDB = -120.0
    @Published private(set) var peakDB = -120.0
    @Published private(set) var status = "Stopped"
    @Published private(set) var inputName = ""

    let displayColumns = 320
    private(set) var pooler: LogFrequencyPooler?

    // MARK: Internals
    private let capture = AudioCapture()
    private var spectrum: Spectrum?
    private(set) var analyzer: LiveSoundAnalyzer?
    private let dispAvg = ExpAverager(tau: 0.5)
    private let peakHold = PeakHold(holdS: 2, decayDBPerS: 12)
    private var spectrogramRenderer: SpectrogramRenderer?
    private var timer: Timer?
    private var lastTotal = 0
    private var lastRecRefresh = 0.0
    private var silentSince = 0.0
    private var silenceWarned = false
    private var observers: [NSObjectProtocol] = []

    init() {
        let nc = NotificationCenter.default
        observers.append(nc.addObserver(forName: AVAudioSession.interruptionNotification, object: nil,
                                        queue: .main) { [weak self] note in
            guard let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                  AVAudioSession.InterruptionType(rawValue: raw) == .began else { return }
            MainActor.assumeIsolated {
                guard let self, self.running, self.source == .microphone else { return }
                self.stop()
                self.status = "Interrupted (call or another app took the microphone). Press Start to resume."
            }
        })
        observers.append(nc.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil,
                                        queue: .main) { [weak self] note in
            guard let raw = note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
                  let reason = AVAudioSession.RouteChangeReason(rawValue: raw),
                  reason == .newDeviceAvailable || reason == .oldDeviceUnavailable else { return }
            MainActor.assumeIsolated {
                // An interface was plugged in or removed: reopen so the format matches.
                guard let self, self.running, self.source == .microphone else { return }
                self.begin()
            }
        })
    }

    // MARK: Control

    func toggle() { running ? stop() : start() }

    func start() {
        guard source == .microphone else { begin(); return }
        switch AVAudioApplication.shared.recordPermission {
        case .granted:
            begin()
        case .denied:
            showPermissionAlert = true
        default:
            AVAudioApplication.requestRecordPermission { granted in
                Task { @MainActor in
                    if granted { self.begin() } else { self.showPermissionAlert = true }
                }
            }
        }
    }

    private func begin() {
        timer?.invalidate()
        do {
            switch source {
            case .microphone: try capture.startMicrophone(preferredInputUID: preferredInputUID)
            case .demo: capture.startDemo()
            }
        } catch {
            capture.stop()
            running = false
            errorMessage = error.localizedDescription
            return
        }
        let sr = capture.sampleRate
        let sp = Spectrum(sampleRate: sr, fftSize: fftSize)
        spectrum = sp
        let an = LiveSoundAnalyzer(sampleRate: sr, fftSize: fftSize, enbw: sp.enbw, target: target,
                                   sensitivity: sensitivity, mainsHz: mains.hz)
        analyzer = an
        pooler = LogFrequencyPooler(freqs: sp.freqs, columns: displayColumns)
        spectrogramRenderer = SpectrogramRenderer(columns: displayColumns, rows: 150)
        dispAvg.reset()
        peakHold.reset()
        lastTotal = 0
        lastRecRefresh = 0
        silentSince = CACurrentMediaTime()
        silenceWarned = false
        inputName = capture.inputName
        running = true
        status = "Listening to \(capture.inputName) at \(Int(sr)) Hz, FFT \(fftSize) "
            + String(format: "(%.1f Hz resolution)", sr / Double(fftSize))
        UIApplication.shared.isIdleTimerDisabled = true  // keep the screen on at the gig
        let t = Timer(timeInterval: 1.0 / 30.0, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.tick() }
        }
        RunLoop.main.add(t, forMode: .common)
        timer = t
    }

    func stop() {
        timer?.invalidate()
        timer = nil
        capture.stop()
        running = false
        status = "Stopped"
        UIApplication.shared.isIdleTimerDisabled = false
    }

    private func restartIfRunning() {
        if running { start() }
    }

    func reset() {
        dispAvg.reset()
        peakHold.reset()
        analyzer?.reset()
        spectrogramRenderer?.clear()
        spectrogram = spectrogramRenderer?.image()
        recommendations = []
        markers = []
    }

    func reportText() -> String {
        analyzer?.report(inputName: inputName) ?? "Live Sound Analyzer: no analysis has been run yet.\n"
    }

    // MARK: Processing loop (30 fps)

    private func tick() {
        guard let sp = spectrum, let an = analyzer else { return }
        let ring = capture.buffer
        let total = ring.total
        let new = total - lastTotal
        guard new > 0 else { return }
        lastTotal = total
        let (block, _) = ring.readLatest(sp.fftSize)
        let recentCount = min(new, block.count)
        var ms = 0.0
        for v in block[(block.count - recentCount)...] { ms += Double(v) * Double(v) }
        let rms = powerToDB(ms / Double(max(recentCount, 1)))
        let peak = Double(ring.popPeak())
        let now = CACurrentMediaTime()
        let power = sp.power(block)
        an.update(power: power, t: now, peakAbs: peak, rmsDBFS: rms)
        rmsDB = rms
        peakDB = powerToDB(peak * peak)
        checkSilence(peak: peak, now: now)

        if !frozen, let pooler {
            let avg = dispAvg.update(power, t: now)
            let rawDB = power.map(powerToDB)
            live = pooler.pool(avg.map(powerToDB))
            peakHoldTrace = pooler.pool(peakHold.update(rawDB, t: now))
            if let slow = an.slow.value { longTerm = pooler.pool(slow.map(powerToDB)) }
            bands = bandLevels(freqs: sp.freqs, power: avg, enbw: sp.enbw).map { $0.isFinite ? $0 : Self.dbMin }
            bandDev = an.bandDev ?? []
            if an.bandDB != nil {
                targetLine = thirdOctaveExact.map { target($0) + an.bandOffset }
            }
            if let r = spectrogramRenderer {
                r.push(pooler.pool(rawDB))
                spectrogram = r.image()
            }
        }

        if now - lastRecRefresh > 0.5 {
            lastRecRefresh = now
            let recs = an.recommendations()
            if recs != recommendations { recommendations = recs }
            let m = recs.compactMap { r -> FeedbackMarker? in
                guard r.category == "Feedback", let f = r.freq else { return nil }
                return FeedbackMarker(id: r.id, freq: f, severity: r.severity)
            }
            if m != markers { markers = m }
        }
    }

    /// iOS delivers exact digital silence when something else owns or blocks the mic.
    private func checkSilence(peak: Double, now: Double) {
        guard source == .microphone, peak == 0 else { silentSince = now; return }
        if !silenceWarned && now - silentSince > 2.5 {
            silenceWarned = true
            showSilenceAlert = true
        }
    }
}
