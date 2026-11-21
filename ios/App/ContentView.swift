import LiveSoundCore
import SwiftUI
import UIKit

@main
struct LiveSoundApp: App {
    @StateObject private var model = AnalyzerModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ContentView(model: model)
                .preferredColorScheme(.dark)
        }
        .onChange(of: scenePhase) { _, phase in
            // iOS doesn't allow background mic capture without a background mode; stop cleanly.
            if phase == .background && model.running { model.stop() }
        }
    }
}

enum LowerPane: String, CaseIterable, Identifiable {
    case rta = "1/3-octave RTA"
    case spectrogram = "Spectrogram"
    var id: String { rawValue }
}

struct ContentView: View {
    @ObservedObject var model: AnalyzerModel
    @State private var pane: LowerPane = .rta
    @State private var showSettings = false

    var body: some View {
        NavigationStack {
            GeometryReader { geo in
                if geo.size.width > geo.size.height {
                    // Landscape: graphs left, advice right.
                    HStack(spacing: 8) {
                        graphs
                        RecommendationsView(model: model).frame(width: geo.size.width * 0.38)
                    }
                    .padding(.horizontal, 8)
                } else {
                    VStack(spacing: 8) {
                        graphs.frame(height: geo.size.height * 0.58)
                        RecommendationsView(model: model)
                    }
                    .padding(.horizontal, 8)
                }
            }
            .task {
                // `--demo` launch argument (used by CI screenshots): start with the demo signal.
                if ProcessInfo.processInfo.arguments.contains("--demo") && !model.running {
                    model.source = .demo
                    model.start()
                }
            }
            .navigationTitle("Live Sound")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { toolbar }
            .sheet(isPresented: $showSettings) { SettingsView(model: model) }
            .alert("Microphone access is off", isPresented: $model.showPermissionAlert) {
                Button("Open Settings") {
                    if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                }
                Button("Use demo signal") { model.source = .demo; model.start() }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("Allow Live Sound to use the microphone in Settings > Privacy & Security > Microphone.")
            }
            .alert("No audio from the input", isPresented: $model.showSilenceAlert) {
                Button("OK", role: .cancel) {}
            } message: {
                Text("The microphone is delivering complete silence. Another app may be using it, "
                     + "or access may be restricted. Check Settings > Privacy & Security > Microphone "
                     + "and that any external interface is switched on.")
            }
            .alert("Could not start audio", isPresented: Binding(
                get: { model.errorMessage != nil }, set: { if !$0 { model.errorMessage = nil } })) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(model.errorMessage ?? "")
            }
        }
    }

    private var graphs: some View {
        VStack(spacing: 6) {
            LevelMeterView(rms: model.rmsDB, peak: model.peakDB)
            SpectrumView(model: model)
            Picker("View", selection: $pane) {
                ForEach(LowerPane.allCases) { Text($0.rawValue).tag($0) }
            }
            .pickerStyle(.segmented)
            Group {
                switch pane {
                case .rta: RTAView(model: model)
                case .spectrogram: SpectrogramView(model: model)
                }
            }
            .frame(maxHeight: .infinity)
            Text(model.status)
                .font(.caption2)
                .foregroundStyle(.secondary)
                .lineLimit(2)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        ToolbarItem(placement: .topBarLeading) {
            Button { showSettings = true } label: { Image(systemName: "slider.horizontal.3") }
                .accessibilityLabel("Settings")
        }
        ToolbarItemGroup(placement: .topBarTrailing) {
            Menu {
                Toggle(isOn: $model.frozen) { Label("Freeze graphs", systemImage: "pause.circle") }
                Button { model.reset() } label: { Label("Reset averages", systemImage: "arrow.counterclockwise") }
                ShareLink(item: model.reportText(), subject: Text("Live Sound report"),
                          preview: SharePreview("Live Sound report")) {
                    Label("Share report", systemImage: "square.and.arrow.up")
                }
            } label: {
                Image(systemName: "ellipsis.circle")
            }
            Button {
                model.toggle()
            } label: {
                Text(model.running ? "Stop" : "Start").bold()
                    .padding(.horizontal, 10)
                    .padding(.vertical, 4)
                    .background(model.running ? Color.red.opacity(0.85) : Color.green.opacity(0.8),
                                in: Capsule())
                    .foregroundStyle(.white)
            }
        }
    }
}

// MARK: - Recommendations

struct RecommendationsView: View {
    @ObservedObject var model: AnalyzerModel
    @State private var expanded: Set<String> = []

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Recommendations").font(.headline)
            if model.recommendations.isEmpty {
                Text(emptyText)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            } else {
                ScrollView {
                    LazyVStack(spacing: 6) {
                        ForEach(model.recommendations) { r in
                            row(r)
                        }
                    }
                }
            }
        }
    }

    private var emptyText: String {
        if !model.running { return "Press Start, then point the mic at the PA or listening area. "
            + "Choose 'Demo signal' in Settings to try it without a microphone." }
        if (model.analyzer?.elapsed ?? 0) < 3 { return "Listening... tonal analysis starts after a few seconds." }
        return "No issues detected."
    }

    private func row(_ r: Recommendation) -> some View {
        let open = expanded.contains(r.id)
        return VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text(r.severity.name.uppercased())
                    .font(.system(size: 9, weight: .heavy))
                    .padding(.horizontal, 5).padding(.vertical, 2)
                    .background(severityColor(r.severity), in: Capsule())
                    .foregroundStyle(.black)
                Text(r.title).font(.subheadline.bold())
                Spacer()
                Text(r.category).font(.caption2).foregroundStyle(.secondary)
                Image(systemName: open ? "chevron.up" : "chevron.down")
                    .font(.caption2).foregroundStyle(.secondary)
            }
            Text(r.action).font(.footnote)
            if open {
                Text(r.detail).font(.caption).foregroundStyle(.secondary)
                if let f = r.freq {
                    Text(detailLine(r, f)).font(.caption.italic()).foregroundStyle(.secondary)
                }
            }
        }
        .padding(8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(white: 0.13), in: RoundedRectangle(cornerRadius: 8))
        .overlay(alignment: .leading) {
            RoundedRectangle(cornerRadius: 2).fill(severityColor(r.severity)).frame(width: 3)
        }
        .contentShape(Rectangle())
        .onTapGesture {
            if open { expanded.remove(r.id) } else { expanded.insert(r.id) }
        }
    }

    private func detailLine(_ r: Recommendation, _ f: Double) -> String {
        var s = "\(formatHz(f)) (\(noteName(f)))"
        if let g = r.gainDB { s += String(format: ", gain %+.1f dB", g) }
        if let q = r.q { s += String(format: ", Q %g", q) }
        return s
    }
}

// MARK: - Settings

struct InputChoice: Hashable {
    let uid: String
    let name: String
}

struct SettingsView: View {
    @ObservedObject var model: AnalyzerModel
    @Environment(\.dismiss) private var dismiss
    @State private var inputs: [InputChoice] = []

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker("Source", selection: $model.source) {
                        ForEach(InputSource.allCases) { Text($0.rawValue).tag($0) }
                    }
                    if model.source == .microphone {
                        Picker("Input", selection: $model.preferredInputUID) {
                            Text("Automatic").tag(String?.none)
                            ForEach(inputs, id: \.uid) { Text($0.name).tag(Optional($0.uid)) }
                        }
                    }
                } header: {
                    Text("Input")
                } footer: {
                    Text("For accurate readings use a measurement mic through a USB audio interface. "
                         + "The built-in mic works for spotting feedback and big tonal problems. "
                         + "iOS's voice processing and automatic gain are switched off while measuring.")
                }

                Section("Analysis") {
                    Picker("Target curve", selection: $model.target) {
                        ForEach(TargetCurve.allCases) { Text($0.rawValue).tag($0) }
                    }
                    Picker("Feedback sensitivity", selection: $model.sensitivity) {
                        ForEach(FeedbackSensitivity.allCases) { Text($0.rawValue).tag($0) }
                    }
                    Picker("Mains frequency", selection: $model.mains) {
                        ForEach(MainsSetting.allCases) { Text($0.rawValue).tag($0) }
                    }
                }

                Section("Display") {
                    Picker("FFT size", selection: $model.fftSize) {
                        ForEach(AnalyzerModel.fftSizes, id: \.self) { Text("\($0)").tag($0) }
                    }
                    Picker("Averaging", selection: $model.averaging) {
                        ForEach(DisplayAveraging.allCases) { Text($0.rawValue).tag($0) }
                    }
                }

                Section("Tips") {
                    Text("Ringing out monitors: set sensitivity to High and raise the gain slowly. "
                         + "Each ring frequency shows as a red marker with the notch to apply.")
                    Text("System tuning: play pink noise through the PA and choose the Flat target.")
                    Text("During a show: use the house curve. Tonal advice uses a 4-second average.")
                }
                .font(.footnote)
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
            .onAppear {
                inputs = AudioCapture.availableInputs().map { InputChoice(uid: $0.uid, name: $0.portName) }
            }
        }
    }
}
