import CoreGraphics
import Foundation

/// Scrolling spectrogram bitmap: newest row at the top, "inferno"-style colours.
final class SpectrogramRenderer {
    let columns: Int
    let rows: Int
    var levelMin = -100.0
    var levelMax = -20.0
    private var pixels: [UInt8]
    private static let lut: [(UInt8, UInt8, UInt8)] = makeLUT()

    init(columns: Int, rows: Int) {
        self.columns = columns
        self.rows = rows
        pixels = [UInt8](repeating: 0, count: columns * rows * 4)
        clear()
    }

    func clear() {
        let c = Self.lut[0]
        for i in stride(from: 0, to: pixels.count, by: 4) {
            pixels[i] = c.0; pixels[i + 1] = c.1; pixels[i + 2] = c.2; pixels[i + 3] = 255
        }
    }

    /// Add a row of dB values (one per column); older rows move down.
    func push(_ db: [Double]) {
        let rowBytes = columns * 4
        pixels.withUnsafeMutableBytes { p in
            guard let base = p.baseAddress else { return }
            memmove(base + rowBytes, base, rowBytes * (rows - 1))
        }
        let span = levelMax - levelMin
        for c in 0..<min(columns, db.count) {
            let x = (db[c] - levelMin) / span
            let idx = Int((min(max(x, 0), 1) * 255).rounded())
            let col = Self.lut[idx]
            pixels[c * 4] = col.0
            pixels[c * 4 + 1] = col.1
            pixels[c * 4 + 2] = col.2
        }
    }

    func image() -> CGImage? {
        let data = Data(pixels) as CFData
        guard let provider = CGDataProvider(data: data) else { return nil }
        return CGImage(width: columns, height: rows, bitsPerComponent: 8, bitsPerPixel: 32,
                       bytesPerRow: columns * 4, space: CGColorSpaceCreateDeviceRGB(),
                       bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue),
                       provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent)
    }

    /// Piecewise-linear approximation of matplotlib's "inferno" colour map.
    private static func makeLUT() -> [(UInt8, UInt8, UInt8)] {
        let stops: [(Double, Double, Double, Double)] = [
            (0.00, 0, 0, 4), (0.15, 31, 12, 72), (0.30, 85, 15, 109), (0.45, 136, 34, 106),
            (0.60, 186, 54, 85), (0.75, 227, 89, 51), (0.88, 249, 140, 10), (1.00, 252, 255, 164),
        ]
        return (0..<256).map { i in
            let x = Double(i) / 255
            var k = 0
            while k < stops.count - 2 && x > stops[k + 1].0 { k += 1 }
            let a = stops[k], b = stops[k + 1]
            let t = (x - a.0) / (b.0 - a.0)
            func mix(_ u: Double, _ v: Double) -> UInt8 { UInt8(max(0, min(255, (u + (v - u) * t).rounded()))) }
            return (mix(a.1, b.1), mix(a.2, b.2), mix(a.3, b.3))
        }
    }
}
