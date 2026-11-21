package com.livesound.analyzer.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** In-place iterative radix-2 complex FFT. */
class Fft(val n: Int) {
    private val cosT = DoubleArray(n / 2) { cos(2 * PI * it / n) }
    private val sinT = DoubleArray(n / 2) { sin(2 * PI * it / n) }
    private val rev = IntArray(n)

    init {
        require(n >= 2 && n and (n - 1) == 0) { "FFT size must be a power of two" }
        val bits = Integer.numberOfTrailingZeros(n)
        for (i in 0 until n) {
            var x = i
            var y = 0
            repeat(bits) { y = (y shl 1) or (x and 1); x = x shr 1 }
            rev[i] = y
        }
    }

    /** Forward transform (e^-i); unnormalized inverse when [inverse] is true. */
    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean = false) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        val sign = if (inverse) -1.0 else 1.0
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var start = 0
            while (start < n) {
                var tw = 0
                for (k in start until start + half) {
                    val c = cosT[tw]
                    val s = sign * sinT[tw]
                    val j = k + half
                    val tr = re[j] * c + im[j] * s
                    val ti = im[j] * c - re[j] * s
                    re[j] = re[k] - tr
                    im[j] = im[k] - ti
                    re[k] += tr
                    im[k] += ti
                    tw += step
                }
                start += size
            }
            size = size shl 1
        }
    }
}
