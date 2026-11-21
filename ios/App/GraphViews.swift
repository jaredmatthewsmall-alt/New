import LiveSoundCore
import SwiftUI

private let freqTicks: [Double] = [20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000]
private let minorTicks: [Double] = [30, 40, 60, 70, 80, 90, 300, 400, 600, 700, 800, 900,
                                    3000, 4000, 6000, 7000, 8000, 9000]
private let gridColor = Color.white.opacity(0.12)
private let labelColor = Color.white.opacity(0.55)

func severityColor(_ s: Severity) -> Color {
    switch s {
    case .critical: return Color(red: 0.95, green: 0.25, blue: 0.25)
    case .warning: return Color(red: 0.98, green: 0.62, blue: 0.15)
    case .advisory: return Color(red: 0.40, green: 0.62, blue: 0.95)
    case .info: return .gray
    }
}

/// x position (0...1) on the 20 Hz - 20 kHz log axis.
private func xPos(_ f: Double) -> Double { (log10(f) - log10(20)) / 3 }

// MARK: - Spectrum

struct SpectrumView: View {
    @ObservedObject var model: AnalyzerModel
    @State private var touchX: CGFloat?
    private let leftPad: CGFloat = 30
    private let bottomPad: CGFloat = 16

    var body: some View {
        GeometryReader { geo in
            let plot = CGRect(x: leftPad, y: 4, width: geo.size.width - leftPad - 4,
                              height: geo.size.height - bottomPad - 4)
            ZStack(alignment: .topLeading) {
                Canvas { ctx, _ in draw(ctx, plot) }
                if let x = touchX { readout(x: x, plot: plot) }
                legend.padding(.top, 6).padding(.leading, leftPad + 6)
            }
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0)
                .onChanged { touchX = min(max($0.location.x, plot.minX), plot.maxX) }
                .onEnded { _ in touchX = nil })
        }
        .background(Color.black)
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .accessibilityLabel("Frequency spectrum")
    }

    private var legend: some View {
        HStack(spacing: 10) {
            legendItem("Live", .cyan)
            legendItem("4 s avg", .orange)
            legendItem("Peak", .white.opacity(0.5))
        }
        .font(.system(size: 10))
    }

    private func legendItem(_ t: String, _ c: Color) -> some View {
        HStack(spacing: 3) {
            Rectangle().fill(c).frame(width: 10, height: 2)
            Text(t).foregroundStyle(labelColor)
        }
    }

    private func y(_ db: Double, _ r: CGRect) -> CGFloat {
        let v = (db - AnalyzerModel.dbMin) / (AnalyzerModel.dbMax - AnalyzerModel.dbMin)
        return r.maxY - CGFloat(min(max(v, 0), 1)) * r.height
    }

    private func draw(_ ctx: GraphicsContext, _ r: CGRect) {
        // Grid
        var grid = Path()
        for db in stride(from: -100.0, through: -10.0, by: 10) {
            grid.move(to: CGPoint(x: r.minX, y: y(db, r)))
            grid.addLine(to: CGPoint(x: r.maxX, y: y(db, r)))
        }
        for f in freqTicks + minorTicks {
            let x = r.minX + CGFloat(xPos(f)) * r.width
            grid.move(to: CGPoint(x: x, y: r.minY))
            grid.addLine(to: CGPoint(x: x, y: r.maxY))
        }
        ctx.stroke(grid, with: .color(gridColor), lineWidth: 0.5)
        for db in stride(from: -100.0, through: -10.0, by: 20) {
            ctx.draw(Text("\(Int(db))").font(.system(size: 9)).foregroundColor(labelColor),
                     at: CGPoint(x: r.minX - 3, y: y(db, r)), anchor: .trailing)
        }
        for f in freqTicks {
            ctx.draw(Text(axisLabel(f)).font(.system(size: 9)).foregroundColor(labelColor),
                     at: CGPoint(x: r.minX + CGFloat(xPos(f)) * r.width, y: r.maxY + 2), anchor: .top)
        }
        // Feedback markers (behind the traces so the peak itself stays visible)
        for m in model.markers {
            let x = r.minX + CGFloat(xPos(m.freq)) * r.width
            var p = Path()
            p.move(to: CGPoint(x: x, y: r.minY))
            p.addLine(to: CGPoint(x: x, y: r.maxY))
            let c = severityColor(m.severity)
            ctx.stroke(p, with: .color(c), style: StrokeStyle(lineWidth: 1.2, dash: [4, 3]))
            ctx.draw(Text(formatHz(m.freq)).font(.system(size: 10, weight: .bold)).foregroundColor(c),
                     at: CGPoint(x: x, y: r.minY + 18), anchor: .bottom)
        }
        // Traces
        trace(ctx, model.peakHoldTrace, r, .white.opacity(0.3), 1)
        trace(ctx, model.longTerm, r, .orange, 1.6)
        trace(ctx, model.live, r, .cyan, 1.2)
    }

    private func trace(_ ctx: GraphicsContext, _ v: [Double], _ r: CGRect, _ c: Color, _ w: CGFloat) {
        guard v.count > 1 else { return }
        var p = Path()
        let n = CGFloat(v.count - 1)
        for (i, d) in v.enumerated() {
            let pt = CGPoint(x: r.minX + CGFloat(i) / n * r.width, y: y(d, r))
            if i == 0 { p.move(to: pt) } else { p.addLine(to: pt) }
        }
        ctx.stroke(p, with: .color(c), lineWidth: w)
    }

    @ViewBuilder
    private func readout(x: CGFloat, plot: CGRect) -> some View {
        let frac = Double((x - plot.minX) / plot.width)
        let f = pow(10, log10(20) + frac * 3)
        let col = Int((frac * Double(max(model.live.count - 1, 0))).rounded())
        let level = col < model.live.count ? model.live[col] : .nan
        Path { p in
            p.move(to: CGPoint(x: x, y: plot.minY))
            p.addLine(to: CGPoint(x: x, y: plot.maxY))
        }
        .stroke(Color.white.opacity(0.8), lineWidth: 1)
        Text("\(formatHz(f))  \(noteName(f))\n\(level.isFinite ? String(format: "%.1f dBFS", level) : "--")")
            .font(.system(size: 11, weight: .semibold).monospacedDigit())
            .padding(4)
            .background(.black.opacity(0.75), in: RoundedRectangle(cornerRadius: 4))
            .foregroundStyle(.white)
            .offset(x: min(x + 6, plot.maxX - 120), y: plot.minY + 24)
    }
}

// MARK: - 1/3-octave RTA

struct RTAView: View {
    @ObservedObject var model: AnalyzerModel

    var body: some View {
        Canvas { ctx, size in
            let r = CGRect(x: 26, y: 4, width: size.width - 30, height: size.height - 20)
            let lo = -80.0, hi = 0.0
            func y(_ d: Double) -> CGFloat { r.maxY - CGFloat(min(max((d - lo) / (hi - lo), 0), 1)) * r.height }
            var grid = Path()
            for d in stride(from: -70.0, through: -10, by: 10) {
                grid.move(to: CGPoint(x: r.minX, y: y(d)))
                grid.addLine(to: CGPoint(x: r.maxX, y: y(d)))
            }
            ctx.stroke(grid, with: .color(gridColor), lineWidth: 0.5)
            for d in stride(from: -70.0, through: -10, by: 20) {
                ctx.draw(Text("\(Int(d))").font(.system(size: 9)).foregroundColor(labelColor),
                         at: CGPoint(x: r.minX - 3, y: y(d)), anchor: .trailing)
            }
            let n = thirdOctaveNominal.count
            let slot = r.width / CGFloat(n)
            for i in 0..<min(n, model.bands.count) {
                let dev = i < model.bandDev.count && model.bandDev[i].isFinite ? model.bandDev[i] : 0
                let color: Color = dev > 6 ? Color(red: 0.86, green: 0.24, blue: 0.2)
                    : dev > 3 ? Color(red: 0.9, green: 0.63, blue: 0.16)
                    : dev < -4 ? Color(red: 0.27, green: 0.47, blue: 0.86)
                    : Color(red: 0.24, green: 0.7, blue: 0.35)
                let top = y(model.bands[i])
                ctx.fill(Path(CGRect(x: r.minX + CGFloat(i) * slot + slot * 0.1, y: top,
                                     width: slot * 0.8, height: max(r.maxY - top, 0))), with: .color(color))
            }
            if model.targetLine.count == n {
                var p = Path()
                for i in 0..<n {
                    let pt = CGPoint(x: r.minX + (CGFloat(i) + 0.5) * slot, y: y(model.targetLine[i]))
                    if i == 0 { p.move(to: pt) } else { p.addLine(to: pt) }
                }
                ctx.stroke(p, with: .color(.white.opacity(0.8)), style: StrokeStyle(lineWidth: 1.5, dash: [5, 3]))
            }
            for i in stride(from: 0, to: n, by: 3) {
                ctx.draw(Text(axisLabel(thirdOctaveNominal[i])).font(.system(size: 9)).foregroundColor(labelColor),
                         at: CGPoint(x: r.minX + (CGFloat(i) + 0.5) * slot, y: r.maxY + 2), anchor: .top)
            }
        }
        .background(Color.black)
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .accessibilityLabel("One-third octave analyzer with target curve")
    }
}

// MARK: - Spectrogram

struct SpectrogramView: View {
    @ObservedObject var model: AnalyzerModel

    var body: some View {
        VStack(spacing: 2) {
            if let img = model.spectrogram {
                Image(decorative: img, scale: 1)
                    .resizable()
                    .interpolation(.medium)
            } else {
                Color.black
            }
            GeometryReader { geo in
                ForEach(freqTicks.dropLast(), id: \.self) { f in
                    Text(axisLabel(f)).font(.system(size: 9)).foregroundStyle(labelColor)
                        .position(x: CGFloat(xPos(f)) * geo.size.width + (f == 20 ? 8 : 0), y: 6)
                }
            }
            .frame(height: 12)
        }
        .padding(.bottom, 2)
        .background(Color.black)
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .accessibilityLabel("Spectrogram. Feedback appears as a bright vertical streak.")
    }
}

// MARK: - Level meter

struct LevelMeterView: View {
    let rms: Double
    let peak: Double
    @State private var hold = -120.0
    @State private var holdTime = Date.distantPast

    var body: some View {
        HStack(spacing: 6) {
            Canvas { ctx, size in
                func x(_ d: Double) -> CGFloat { CGFloat((min(max(d, -60), 0) + 60) / 60) * size.width }
                ctx.fill(Path(CGRect(origin: .zero, size: size)), with: .color(Color(white: 0.2)))
                let grad = Gradient(stops: [
                    .init(color: Color(red: 0.16, green: 0.63, blue: 0.24), location: 0),
                    .init(color: Color(red: 0.24, green: 0.75, blue: 0.24), location: 0.75),
                    .init(color: Color(red: 0.86, green: 0.78, blue: 0.16), location: 0.85),
                    .init(color: Color(red: 0.9, green: 0.16, blue: 0.16), location: 1),
                ])
                let shading = GraphicsContext.Shading.linearGradient(
                    grad, startPoint: .zero, endPoint: CGPoint(x: size.width, y: 0))
                ctx.fill(Path(CGRect(x: 0, y: 0, width: x(rms), height: size.height * 0.65)), with: shading)
                ctx.fill(Path(CGRect(x: 0, y: size.height * 0.7, width: x(peak), height: size.height * 0.3)),
                         with: .color(.white.opacity(0.45)))
                var h = Path()
                h.move(to: CGPoint(x: x(hold), y: 0))
                h.addLine(to: CGPoint(x: x(hold), y: size.height))
                ctx.stroke(h, with: .color(hold > -1 ? .red : .white), lineWidth: 2)
            }
            .frame(height: 14)
            .clipShape(RoundedRectangle(cornerRadius: 3))
            Text(rms > -119 ? String(format: "%5.1f / %5.1f", rms, hold) : "--")
                .font(.system(size: 11).monospacedDigit())
                .foregroundStyle(.secondary)
                .frame(width: 92, alignment: .trailing)
        }
        .onChange(of: peak) { _, p in
            if p >= hold || Date().timeIntervalSince(holdTime) > 2 {
                hold = p
                holdTime = Date()
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(String(format: "Input level %.0f dBFS, peak %.0f", rms, hold))
    }
}
