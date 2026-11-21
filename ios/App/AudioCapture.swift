import AVFoundation
import LiveSoundCore

/// Captures mono audio into a RingBuffer from the microphone (AVAudioEngine) or the demo generator.
final class AudioCapture {
    enum CaptureError: LocalizedError {
        case noInput
        var errorDescription: String? {
            "No audio input is available. Check that a microphone is connected and not in use by another app."
        }
    }

    private(set) var sampleRate: Double = 48000
    private(set) var buffer = RingBuffer(size: 48000 * 4)
    private(set) var inputName = ""
    private(set) var isRunning = false

    private let engine = AVAudioEngine()
    private var tapInstalled = false
    private var demoTimer: DispatchSourceTimer?
    private let demoQueue = DispatchQueue(label: "livesound.demo")

    // MARK: Inputs

    /// Inputs the user can pick (built-in mic, wired headset, USB audio interface ...).
    static func availableInputs() -> [AVAudioSessionPortDescription] {
        let session = AVAudioSession.sharedInstance()
        // availableInputs is only populated once a record-capable category is set.
        try? session.setCategory(.record, mode: .measurement)
        return session.availableInputs ?? []
    }

    // MARK: Start / stop

    /// Start capturing from the microphone. `preferredInputUID` selects a specific port.
    func startMicrophone(preferredInputUID: String?) throws {
        stop()
        let session = AVAudioSession.sharedInstance()
        // .measurement turns off iOS's automatic gain control, noise suppression and
        // voice EQ, so the analyzer sees what the mic actually hears.
        try session.setCategory(.record, mode: .measurement, options: [])
        try? session.setPreferredSampleRate(48000)
        if let uid = preferredInputUID,
           let port = session.availableInputs?.first(where: { $0.uid == uid }) {
            try? session.setPreferredInput(port)
        } else {
            try? session.setPreferredInput(nil)
        }
        try session.setActive(true)

        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0 else { throw CaptureError.noInput }
        sampleRate = format.sampleRate
        let ring = RingBuffer(size: Int(format.sampleRate * 4))
        buffer = ring
        input.installTap(onBus: 0, bufferSize: 1024, format: format) { pcm, _ in
            guard let data = pcm.floatChannelData else { return }
            ring.write(UnsafeBufferPointer(start: data[0], count: Int(pcm.frameLength)))
        }
        tapInstalled = true
        engine.prepare()
        do {
            try engine.start()
        } catch {
            stop()
            throw error
        }
        inputName = session.currentRoute.inputs.first?.portName ?? "Microphone"
        isRunning = true
    }

    /// Start the synthetic demo signal (pink noise + hum + building feedback).
    func startDemo() {
        stop()
        sampleRate = 48000
        let ring = RingBuffer(size: 48000 * 4)
        buffer = ring
        inputName = "Demo signal"
        let demo = DemoSignal(sampleRate: sampleRate)
        let sr = sampleRate
        let start = DispatchTime.now().uptimeNanoseconds
        var generated = 0
        let timer = DispatchSource.makeTimerSource(queue: demoQueue)
        timer.schedule(deadline: .now(), repeating: .milliseconds(10))
        timer.setEventHandler {
            let elapsed = Double(DispatchTime.now().uptimeNanoseconds - start) / 1e9
            let due = Int(elapsed * sr)
            var todo = due - generated
            if todo > Int(sr) { generated = due - Int(sr); todo = Int(sr) }  // resync after a stall
            if todo > 0 {
                ring.write(demo.generate(todo))
                generated += todo
            }
        }
        timer.resume()
        demoTimer = timer
        isRunning = true
    }

    func stop() {
        demoTimer?.cancel()
        demoTimer = nil
        if tapInstalled {
            engine.inputNode.removeTap(onBus: 0)
            tapInstalled = false
        }
        if engine.isRunning { engine.stop() }
        if isRunning && inputName != "Demo signal" {
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        }
        isRunning = false
    }
}
