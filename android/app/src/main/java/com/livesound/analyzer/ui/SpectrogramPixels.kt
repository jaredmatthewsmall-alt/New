package com.livesound.analyzer.ui

import kotlin.math.roundToInt

/** Scrolling spectrogram as ARGB pixels: newest row at the top, "inferno"-style colours. */
class SpectrogramPixels(val columns: Int, val rows: Int) {
    var levelMin = -100.0
    var levelMax = -20.0
    val pixels = IntArray(columns * rows) { LUT[0] }

    fun clear() = pixels.fill(LUT[0])

    /** Add one row of dB values (one per column); older rows move down. */
    fun push(db: DoubleArray) {
        System.arraycopy(pixels, 0, pixels, columns, columns * (rows - 1))
        val span = levelMax - levelMin
        for (c in 0 until minOf(columns, db.size)) {
            val x = ((db[c] - levelMin) / span).coerceIn(0.0, 1.0)
            pixels[c] = LUT[(x * 255).roundToInt()]
        }
    }

    companion object {
        /** Piecewise-linear approximation of matplotlib's "inferno" colour map. */
        val LUT: IntArray = run {
            val stops = arrayOf(
                doubleArrayOf(0.00, 0.0, 0.0, 4.0), doubleArrayOf(0.15, 31.0, 12.0, 72.0),
                doubleArrayOf(0.30, 85.0, 15.0, 109.0), doubleArrayOf(0.45, 136.0, 34.0, 106.0),
                doubleArrayOf(0.60, 186.0, 54.0, 85.0), doubleArrayOf(0.75, 227.0, 89.0, 51.0),
                doubleArrayOf(0.88, 249.0, 140.0, 10.0), doubleArrayOf(1.00, 252.0, 255.0, 164.0),
            )
            IntArray(256) { i ->
                val x = i / 255.0
                var k = 0
                while (k < stops.size - 2 && x > stops[k + 1][0]) k++
                val a = stops[k]
                val b = stops[k + 1]
                val t = (x - a[0]) / (b[0] - a[0])
                fun mix(j: Int) = (a[j] + (b[j] - a[j]) * t).roundToInt().coerceIn(0, 255)
                (0xFF shl 24) or (mix(1) shl 16) or (mix(2) shl 8) or mix(3)
            }
        }
    }
}
