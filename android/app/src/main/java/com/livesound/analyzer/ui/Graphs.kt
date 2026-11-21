package com.livesound.analyzer.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.livesound.analyzer.core.Severity
import com.livesound.analyzer.core.THIRD_OCTAVE_NOMINAL
import com.livesound.analyzer.core.axisLabel
import com.livesound.analyzer.core.fmt
import com.livesound.analyzer.core.formatHz
import com.livesound.analyzer.core.noteName
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

private val FREQ_TICKS = listOf(20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0, 20000.0)
private val MINOR_TICKS = listOf(30.0, 40.0, 60.0, 70.0, 80.0, 90.0, 300.0, 400.0, 600.0, 700.0, 800.0, 900.0,
    3000.0, 4000.0, 6000.0, 7000.0, 8000.0, 9000.0)
private val GRID = Color.White.copy(alpha = 0.12f)
private val LABEL = Color.White.copy(alpha = 0.6f)
val PanelBg = Color(0xFF050608)
private const val DB_MIN = -110.0
private const val DB_MAX = 0.0

fun severityColor(s: Severity): Color = when (s) {
    Severity.CRITICAL -> Color(0xFFF24040)
    Severity.WARNING -> Color(0xFFFA9E26)
    Severity.ADVISORY -> Color(0xFF669EF2)
    Severity.INFO -> Color.Gray
}

/** x position (0..1) on the 20 Hz - 20 kHz log axis. */
private fun xPos(f: Double) = ((log10(f) - log10(20.0)) / 3.0).toFloat()

private fun DrawScope.label(tm: TextMeasurer, text: String, x: Float, y: Float, sizeSp: Int = 11,
                            alignX: Float = 0.5f, alignY: Float = 0f, color: Color = LABEL, bold: Boolean = false) {
    val layout = tm.measure(text, TextStyle(color = color, fontSize = sizeSp.sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal))
    drawText(layout, topLeft = Offset(x - layout.size.width * alignX, y - layout.size.height * alignY))
}

// ------------------------------------------------------------------ spectrum

@Composable
fun SpectrumGraph(frame: DisplayFrame, markers: List<FeedbackMarker>, modifier: Modifier = Modifier) {
    val tm = rememberTextMeasurer()
    var touchX by remember { mutableStateOf<Float?>(null) }
    Box(modifier
        .clip(RoundedCornerShape(10.dp))
        .background(PanelBg)
        .semantics { contentDescription = "Frequency spectrum" }
        .pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { touchX = it.x },
                onDrag = { change, _ -> touchX = change.position.x },
                onDragEnd = { touchX = null },
                onDragCancel = { touchX = null },
            )
        }
        .pointerInput(Unit) { detectTapGestures(onPress = { touchX = it.x; tryAwaitRelease(); touchX = null }) }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val plot = Rect(42.dp.toPx(), 6.dp.toPx(), size.width - 6.dp.toPx(), size.height - 20.dp.toPx())
            fun y(db: Double) = plot.bottom - (((db - DB_MIN) / (DB_MAX - DB_MIN)).coerceIn(0.0, 1.0) * plot.height).toFloat()
            fun x(f: Double) = plot.left + xPos(f) * plot.width
            // grid
            val u = 1.dp.toPx()
            for (d in -100..-10 step 10) drawLine(GRID, Offset(plot.left, y(d.toDouble())), Offset(plot.right, y(d.toDouble())), u)
            for (f in FREQ_TICKS + MINOR_TICKS) drawLine(GRID, Offset(x(f), plot.top), Offset(x(f), plot.bottom), u)
            for (d in -100..-10 step 20) label(tm, "$d", plot.left - 4 * u, y(d.toDouble()), alignX = 1f, alignY = 0.5f)
            for (f in FREQ_TICKS) {
                label(tm, axisLabel(f), x(f), plot.bottom + 3 * u, alignX = if (f >= 20000.0) 1f else 0.5f)
            }
            // feedback markers behind traces
            for (m in markers) {
                val c = severityColor(m.severity)
                drawLine(c, Offset(x(m.freq), plot.top), Offset(x(m.freq), plot.bottom), 1.5f * u,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(7 * u, 5 * u)))
                label(tm, formatHz(m.freq), x(m.freq), plot.top + 2 * u, 14, color = c, bold = true)
            }
            trace(frame.peakHold, plot, ::y, Color.White.copy(alpha = 0.3f), u)
            trace(frame.longTerm, plot, ::y, Color(0xFFFFA726), 1.8f * u)
            trace(frame.live, plot, ::y, Color(0xFF40D0FF), 1.3f * u)
            // legend
            var lx = plot.left + 8 * u
            val ly = plot.top + 26 * u
            for ((name, c) in listOf("Live" to Color(0xFF40D0FF), "4 s average" to Color(0xFFFFA726),
                "Peak hold" to Color.White.copy(alpha = 0.5f))) {
                drawLine(c, Offset(lx, ly), Offset(lx + 14 * u, ly), 2.5f * u)
                val w = tm.measure(name, TextStyle(fontSize = 12.sp)).size.width
                label(tm, name, lx + 18 * u, ly, 12, alignX = 0f, alignY = 0.5f)
                lx += 30 * u + w
            }
            // touch readout
            touchX?.let { tx ->
                val cx = tx.coerceIn(plot.left, plot.right)
                val frac = ((cx - plot.left) / plot.width).toDouble()
                val f = 10.0.pow(log10(20.0) + frac * 3)
                val col = (frac * (frame.live.size - 1)).roundToInt()
                val lvl = frame.live.getOrNull(col)
                drawLine(Color.White, Offset(cx, plot.top), Offset(cx, plot.bottom), u)
                val text = "${formatHz(f)}  ${noteName(f)}   " + (lvl?.let { fmt("%.1f dBFS", it) } ?: "--")
                val layout = tm.measure(text, TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold))
                val bx = (cx + 8 * u).coerceAtMost(plot.right - layout.size.width - 8 * u)
                drawRoundRect(Color.Black.copy(alpha = 0.8f), Offset(bx - 5 * u, plot.top + 40 * u),
                    Size(layout.size.width + 10 * u, layout.size.height + 6 * u),
                    androidx.compose.ui.geometry.CornerRadius(5 * u))
                drawText(layout, topLeft = Offset(bx, plot.top + 43 * u))
            }
        }
    }
}

private fun DrawScope.trace(v: DoubleArray, r: Rect, y: (Double) -> Float, c: Color, w: Float) {
    if (v.size < 2) return
    val p = Path()
    val n = (v.size - 1).toFloat()
    for (i in v.indices) {
        val px = r.left + i / n * r.width
        if (i == 0) p.moveTo(px, y(v[i])) else p.lineTo(px, y(v[i]))
    }
    drawPath(p, c, style = Stroke(width = w, join = StrokeJoin.Round))
}

// ------------------------------------------------------------------ RTA

@Composable
fun RtaGraph(frame: DisplayFrame, modifier: Modifier = Modifier) {
    val tm = rememberTextMeasurer()
    Canvas(modifier
        .clip(RoundedCornerShape(10.dp))
        .background(PanelBg)
        .semantics { contentDescription = "One-third octave analyzer with target curve" }) {
        val u = 1.dp.toPx()
        val r = Rect(38 * u, 24 * u, size.width - 6 * u, size.height - 20 * u)
        val lo = -80.0
        val hi = 0.0
        fun y(d: Double) = r.bottom - (((d - lo) / (hi - lo)).coerceIn(0.0, 1.0) * r.height).toFloat()
        label(tm, "1/3-octave RTA (31-band GEQ centres) vs target", size.width / 2, 4 * u, 13, color = Color.White.copy(alpha = 0.8f))
        for (d in -70..-10 step 10) drawLine(GRID, Offset(r.left, y(d.toDouble())), Offset(r.right, y(d.toDouble())), u)
        for (d in -70..-10 step 20) label(tm, "$d", r.left - 4 * u, y(d.toDouble()), alignX = 1f, alignY = 0.5f)
        val n = THIRD_OCTAVE_NOMINAL.size
        val slot = r.width / n
        for (i in 0 until minOf(n, frame.bands.size)) {
            val dev = frame.bandDev.getOrNull(i)?.takeIf { it.isFinite() } ?: 0.0
            val c = when {
                dev > 6 -> Color(0xFFDC3D33)
                dev > 3 -> Color(0xFFE6A028)
                dev < -4 -> Color(0xFF4678DC)
                else -> Color(0xFF3CB45A)
            }
            val top = y(frame.bands[i])
            drawRect(c, Offset(r.left + i * slot + slot * 0.12f, top), Size(slot * 0.76f, (r.bottom - top).coerceAtLeast(0f)))
        }
        if (frame.targetLine.size == n) {
            val p = Path()
            for (i in 0 until n) {
                val px = r.left + (i + 0.5f) * slot
                if (i == 0) p.moveTo(px, y(frame.targetLine[i])) else p.lineTo(px, y(frame.targetLine[i]))
            }
            drawPath(p, Color.White.copy(alpha = 0.85f), style = Stroke(1.6f * u, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8 * u, 5 * u))))
        }
        for (i in 0 until n step 3) label(tm, axisLabel(THIRD_OCTAVE_NOMINAL[i]), r.left + (i + 0.5f) * slot, r.bottom + 3 * u)
    }
}

// ------------------------------------------------------------------ spectrogram

@Composable
fun SpectrogramGraph(frame: DisplayFrame, modifier: Modifier = Modifier) {
    val tm = rememberTextMeasurer()
    val cols = frame.sgColumns
    val rows = frame.sgRows
    val bitmap = remember(cols, rows) {
        if (cols > 0 && rows > 0) Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888) else null
    }
    Canvas(modifier
        .clip(RoundedCornerShape(10.dp))
        .background(PanelBg)
        .semantics { contentDescription = "Spectrogram. Feedback appears as a bright vertical streak." }) {
        val u = 1.dp.toPx()
        label(tm, "Spectrogram (newest at top) - feedback = bright vertical streak", size.width / 2, 4 * u, 13,
            color = Color.White.copy(alpha = 0.8f))
        val r = Rect(6 * u, 24 * u, size.width - 6 * u, size.height - 20 * u)
        val px = frame.spectrogram
        if (bitmap != null && px != null) {
            bitmap.setPixels(px, 0, cols, 0, 0, cols, rows)
            drawImage(bitmap.asImageBitmap(), srcOffset = IntOffset.Zero, srcSize = IntSize(cols, rows),
                dstOffset = IntOffset(r.left.toInt(), r.top.toInt()),
                dstSize = IntSize(r.width.toInt(), r.height.toInt()), filterQuality = FilterQuality.Low)
        }
        for (f in FREQ_TICKS.dropLast(1)) {
            val x = r.left + xPos(f) * r.width
            label(tm, axisLabel(f), x.coerceAtLeast(r.left + 8 * u), r.bottom + 3 * u)
        }
    }
}

// ------------------------------------------------------------------ level meter

@Composable
fun LevelMeter(rmsDb: Double, peakDb: Double, modifier: Modifier = Modifier) {
    var hold by remember { mutableStateOf(-120.0) }
    var holdT by remember { mutableStateOf(0L) }
    val now = System.currentTimeMillis()
    if (peakDb >= hold || now - holdT > 2000) { hold = peakDb; holdT = now }
    Row(modifier.semantics { contentDescription = fmt("Input level %.0f dBFS", rmsDb) },
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text("Input", color = LABEL, fontSize = 14.sp, modifier = Modifier.width(52.dp))
        Canvas(Modifier.weight(1f).height(22.dp).clip(RoundedCornerShape(4.dp))) {
            fun x(d: Double) = (((d.coerceIn(-60.0, 0.0)) + 60) / 60 * size.width).toFloat()
            drawRect(Color(0xFF2A2A2E))
            drawRect(Brush.horizontalGradient(0f to Color(0xFF28A03C), 0.75f to Color(0xFF3CBE3C),
                0.85f to Color(0xFFDCC828), 1f to Color(0xFFE62828), endX = size.width),
                size = Size(x(rmsDb), size.height * 0.65f))
            drawRect(Color.White.copy(alpha = 0.45f), Offset(0f, size.height * 0.7f), Size(x(peakDb), size.height * 0.3f))
            drawLine(if (hold > -1) Color.Red else Color.White, Offset(x(hold), 0f), Offset(x(hold), size.height), 2.dp.toPx())
            for (d in listOf(-48.0, -36.0, -24.0, -18.0, -12.0, -6.0)) {
                drawLine(Color.Gray, Offset(x(d), size.height - 4.dp.toPx()), Offset(x(d), size.height), 1.dp.toPx())
            }
        }
        Text(if (rmsDb > -119) fmt("%5.1f / %5.1f dBFS", rmsDb, hold) else "--",
            color = LABEL, fontSize = 14.sp, modifier = Modifier.width(150.dp).padding(start = 10.dp))
    }
}
