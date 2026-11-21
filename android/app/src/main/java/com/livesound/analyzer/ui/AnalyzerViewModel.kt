package com.livesound.analyzer.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.livesound.analyzer.audio.AudioCapture
import com.livesound.analyzer.audio.InputDevice
import com.livesound.analyzer.core.ExpAverager
import com.livesound.analyzer.core.FeedbackSensitivity
import com.livesound.analyzer.core.LiveSoundAnalyzer
import com.livesound.analyzer.core.LogFrequencyPooler
import com.livesound.analyzer.core.PeakHold
import com.livesound.analyzer.core.Recommendation
import com.livesound.analyzer.core.Severity
import com.livesound.analyzer.core.Spectrum
import com.livesound.analyzer.core.TargetCurve
import com.livesound.analyzer.core.THIRD_OCTAVE_EXACT
import com.livesound.analyzer.core.bandLevels
import com.livesound.analyzer.core.fmt
import com.livesound.analyzer.core.powerToDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

enum class InputSource(val label: String) { MICROPHONE("Microphone"), DEMO("Demo signal") }

enum class DisplayAveraging(val label: String, val tau: Double) {
    FAST("Fast (0.1 s)", 0.1), MEDIUM("Medium (0.5 s)", 0.5), SLOW("Slow (2 s)", 2.0), VERY_SLOW("Very slow (5 s)", 5.0)
}

enum class MainsSetting(val label: String, val hz: Double?) {
    AUTO("Auto-detect", null), HZ50("50 Hz", 50.0), HZ60("60 Hz", 60.0)
}

data class AnalyzerSettings(
    val source: InputSource = InputSource.MICROPHONE,
    val deviceId: Int? = null,
    val fftSize: Int = 8192,
    val averaging: DisplayAveraging = DisplayAveraging.MEDIUM,
    val target: TargetCurve = TargetCurve.LIVE_MUSIC,
    val sensitivity: FeedbackSensitivity = FeedbackSensitivity.MEDIUM,
    val mains: MainsSetting = MainsSetting.AUTO,
)

data class FeedbackMarker(val freq: Double, val severity: Severity)

/** Everything the graphs draw for one frame (immutable snapshot). */
class DisplayFrame(
    val live: DoubleArray = DoubleArray(0),
    val longTerm: DoubleArray = DoubleArray(0),
    val peakHold: DoubleArray = DoubleArray(0),
    val bands: DoubleArray = DoubleArray(0),
    val bandDev: DoubleArray = DoubleArray(0),
    val targetLine: DoubleArray = DoubleArray(0),
    val spectrogram: IntArray? = null,
    val sgColumns: Int = 0,
    val sgRows: Int = 0,
)

data class AnalyzerUiState(
    val running: Boolean = false,
    val frozen: Boolean = false,
    val frame: DisplayFrame = DisplayFrame(),
    val rmsDb: Double = -120.0,
    val peakDb: Double = -120.0,
    val recommendations: List<Recommendation> = emptyList(),
    val markers: List<FeedbackMarker> = emptyList(),
    val status: String = "Stopped",
    val elapsed: Double = 0.0,
)

class AnalyzerViewModel(app: Application) : AndroidViewModel(app) {
    companion object {
        const val DISPLAY_COLUMNS = 480       // wide tablet screen: plenty of horizontal detail
        const val SPECTROGRAM_ROWS = 200
        val FFT_SIZES = listOf(4096, 8192, 16384, 32768)
    }

    var settings by mutableStateOf(AnalyzerSettings()); private set
    var ui by mutableStateOf(AnalyzerUiState()); private set
    var errorMessage by mutableStateOf<String?>(null)
    var silenceWarning by mutableStateOf(false)

    private val capture = AudioCapture(app)
    private var loop: Job? = null
    private var analyzer: LiveSoundAnalyzer? = null

    fun inputDevices(): List<InputDevice> = capture.inputDevices()

    fun update(transform: (AnalyzerSettings) -> AnalyzerSettings) {
        val old = settings
        val new = transform(old)
        settings = new
        analyzer?.let {
            it.target = new.target
            it.feedback.sensitivity = new.sensitivity
            it.mainsHz = new.mains.hz
        }
        val needsRestart = new.source != old.source || new.deviceId != old.deviceId || new.fftSize != old.fftSize
        if (needsRestart && ui.running) start()
    }

    fun setFrozen(f: Boolean) { ui = ui.copy(frozen = f) }

    fun toggle() = if (ui.running) stop() else start()

    /** Caller must have obtained RECORD_AUDIO for the microphone source. */
    fun start() {
        stopLoop()
        val s = settings
        try {
            when (s.source) {
                InputSource.MICROPHONE -> capture.startMicrophone(s.deviceId)
                InputSource.DEMO -> capture.startDemo()
            }
        } catch (e: Exception) {
            capture.stop()
            ui = ui.copy(running = false, status = "Stopped")
            errorMessage = e.message ?: e.toString()
            return
        }
        val sr = capture.sampleRate.toDouble()
        val sp = Spectrum(sr, s.fftSize)
        val an = LiveSoundAnalyzer(sr, s.fftSize, sp.enbw, s.target, s.sensitivity, s.mains.hz)
        analyzer = an
        val status = "Listening to ${capture.inputName} (${capture.sourceDescription}) at ${sr.toInt()} Hz, " +
            "FFT ${s.fftSize} (${fmt("%.1f", sr / s.fftSize)} Hz resolution)"
        ui = ui.copy(running = true, status = status, recommendations = emptyList(), markers = emptyList())
        silenceWarning = false
        loop = viewModelScope.launch(Dispatchers.Default) { runLoop(sp, an) }
    }

    fun stop() {
        stopLoop()
        capture.stop()
        ui = ui.copy(running = false, status = "Stopped")
    }

    private fun stopLoop() {
        loop?.cancel()
        loop = null
    }

    @Volatile private var resetRequested = false
    fun reset() { resetRequested = true }

    fun reportText(): String =
        analyzer?.report(capture.inputName, DateFormat.getDateTimeInstance().format(Date()))
            ?: "Live Sound Analyzer: no analysis has been run yet.\n"

    /** 30 fps processing loop on a background thread; publishes snapshots to the UI. */
    private suspend fun runLoop(sp: Spectrum, an: LiveSoundAnalyzer) {
        val dispAvg = ExpAverager(settings.averaging.tau)
        val peakHold = PeakHold(2.0, 12.0)
        val pooler = LogFrequencyPooler(sp.freqs, DISPLAY_COLUMNS)
        val sg = SpectrogramPixels(DISPLAY_COLUMNS, SPECTROGRAM_ROWS)
        var lastTotal = 0L
        var lastRec = 0.0
        var silentSince = System.nanoTime() / 1e9
        var recs = emptyList<Recommendation>()
        var markers = emptyList<FeedbackMarker>()
        while (kotlin.coroutines.coroutineContext.isActive) {
            delay(33)
            if (resetRequested) {
                resetRequested = false
                dispAvg.reset(); peakHold.reset(); an.reset(); sg.clear()
                recs = emptyList(); markers = emptyList()
            }
            dispAvg.tau = settings.averaging.tau
            val ring = capture.buffer
            val total = ring.total
            val new = (total - lastTotal).toInt()
            if (new <= 0) continue
            lastTotal = total
            val (block, _) = ring.readLatest(sp.fftSize)
            val recent = minOf(new, block.size)
            var ms = 0.0
            for (i in block.size - recent until block.size) ms += block[i].toDouble() * block[i]
            val rms = powerToDb(ms / maxOf(recent, 1))
            val peak = ring.popPeak().toDouble()
            val now = System.nanoTime() / 1e9
            val power = sp.power(block)
            an.update(power, now, peak, rms)

            if (settings.source == InputSource.MICROPHONE && peak == 0.0) {
                if (now - silentSince > 2.5 && !silenceWarning) withContext(Dispatchers.Main) { silenceWarning = true }
            } else {
                silentSince = now
            }

            if (now - lastRec > 0.5) {
                lastRec = now
                recs = an.recommendations()
                markers = recs.filter { it.category == "Feedback" && it.freq != null }
                    .map { FeedbackMarker(it.freq!!, it.severity) }
            }

            val frozen = ui.frozen
            val frame = if (frozen) ui.frame else {
                val avg = dispAvg.update(power, now)
                val rawDb = DoubleArray(power.size) { powerToDb(power[it]) }
                val avgDb = DoubleArray(avg.size) { powerToDb(avg[it]) }
                sg.push(pooler.pool(rawDb))
                val slow = an.slow.value
                DisplayFrame(
                    live = pooler.pool(avgDb),
                    longTerm = slow?.let { s -> pooler.pool(DoubleArray(s.size) { powerToDb(s[it]) }) } ?: DoubleArray(0),
                    peakHold = pooler.pool(peakHold.update(rawDb, now)),
                    bands = bandLevels(sp.freqs, avg, sp.enbw).map { if (it.isFinite()) it else -110.0 }.toDoubleArray(),
                    bandDev = an.bandDev?.copyOf() ?: DoubleArray(0),
                    targetLine = if (an.bandDb != null) {
                        DoubleArray(THIRD_OCTAVE_EXACT.size) { an.target(THIRD_OCTAVE_EXACT[it]) + an.bandOffset }
                    } else DoubleArray(0),
                    spectrogram = sg.pixels.copyOf(),
                    sgColumns = sg.columns,
                    sgRows = sg.rows,
                )
            }
            val r = recs
            val m = markers
            val elapsed = an.elapsed
            withContext(Dispatchers.Main) {
                if (ui.running) {
                    ui = ui.copy(frame = frame, rmsDb = rms, peakDb = powerToDb(peak * peak),
                        recommendations = r, markers = m, elapsed = elapsed)
                }
            }
        }
    }

    override fun onCleared() {
        stop()
    }
}
