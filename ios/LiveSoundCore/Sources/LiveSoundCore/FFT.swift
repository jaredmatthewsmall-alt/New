import Foundation

/// In-place iterative radix-2 complex FFT. Pure Swift so it runs on any platform.
public final class FFT {
    public let n: Int
    private let cosT: [Double]
    private let sinT: [Double]
    private let rev: [Int]

    public init(size n: Int) {
        precondition(n >= 2 && n & (n - 1) == 0, "FFT size must be a power of two")
        self.n = n
        var c = [Double](repeating: 0, count: n / 2)
        var s = [Double](repeating: 0, count: n / 2)
        for k in 0..<n / 2 {
            let a = 2.0 * Double.pi * Double(k) / Double(n)
            c[k] = cos(a)
            s[k] = sin(a)
        }
        cosT = c
        sinT = s
        let bits = n.trailingZeroBitCount
        var r = [Int](repeating: 0, count: n)
        for i in 0..<n {
            var x = i, y = 0
            for _ in 0..<bits { y = (y << 1) | (x & 1); x >>= 1 }
            r[i] = y
        }
        rev = r
    }

    /// Forward transform (e^{-i}) when `inverse` is false; unnormalized inverse otherwise.
    public func transform(_ re: inout [Double], _ im: inout [Double], inverse: Bool = false) {
        precondition(re.count == n && im.count == n)
        let sign: Double = inverse ? -1 : 1
        re.withUnsafeMutableBufferPointer { rp in
            im.withUnsafeMutableBufferPointer { ip in
                cosT.withUnsafeBufferPointer { cp in
                    sinT.withUnsafeBufferPointer { sp in
                        rev.withUnsafeBufferPointer { rv in
                            for i in 0..<n {
                                let j = rv[i]
                                if j > i {
                                    rp.swapAt(i, j)
                                    ip.swapAt(i, j)
                                }
                            }
                            var size = 2
                            while size <= n {
                                let half = size / 2
                                let step = n / size
                                var start = 0
                                while start < n {
                                    var tw = 0
                                    for k in start..<(start + half) {
                                        let c = cp[tw], s = sign * sp[tw]
                                        let j = k + half
                                        let tr = rp[j] * c + ip[j] * s
                                        let ti = ip[j] * c - rp[j] * s
                                        rp[j] = rp[k] - tr
                                        ip[j] = ip[k] - ti
                                        rp[k] += tr
                                        ip[k] += ti
                                        tw += step
                                    }
                                    start += size
                                }
                                size <<= 1
                            }
                        }
                    }
                }
            }
        }
    }
}
