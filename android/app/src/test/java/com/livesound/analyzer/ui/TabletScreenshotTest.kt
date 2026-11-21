package com.livesound.analyzer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.livesound.analyzer.audio.InputDevice
import com.livesound.analyzer.core.DemoSignal
import com.livesound.analyzer.core.ExpAverager
import com.livesound.analyzer.core.LiveSoundAnalyzer
import com.livesound.analyzer.core.LogFrequencyPooler
import com.livesound.analyzer.core.PeakHold
import com.livesound.analyzer.core.Spectrum
import com.livesound.analyzer.core.THIRD_OCTAVE_EXACT
import com.livesound.analyzer.core.bandLevels
import com.livesound.analyzer.core.powerToDb
import org.junit.Rule
import org.junit.Test

/** Galaxy Tab S10 Ultra: 14.6" 2960 x 1848 panel, ~340 dpi logical density (~1393 x 870 dp). */
val TAB_S10_ULTRA = DeviceConfig.PIXEL_C.copy(
    screenWidth = 2960, screenHeight = 1848, xdpi = 239, ydpi = 239,
    density = Density.create(340), orientation = ScreenOrientation.LANDSCAPE,
    softButtons = false,
)

private object NoActions : AnalyzerActions {
    override fun toggleRun() {}
    override fun setFrozen(frozen: Boolean) {}
    override fun reset() {}
    override fun share() {}
    override fun updateSettings(transform: (AnalyzerSettings) -> AnalyzerSettings) {}
    override fun inputDevices() = emptyList<InputDevice>()
}

/** Runs the real engine over 6.5 s of the demo signal and builds the UI state the app would show. */
fun demoState(seconds: Double = 6.5): AnalyzerUiState {
    val sr = 48000.0
    val n = 8192
    val hop = 1600
    val sig = DemoSignal(sr).generate((sr * seconds).toInt())
    val sp = Spectrum(sr, n)
    val an = LiveSoundAnalyzer(sr, n, sp.enbw)
    val disp = ExpAverager(0.5)
    val hold = PeakHold()
    val pooler = LogFrequencyPooler(sp.freqs, AnalyzerViewModel.DISPLAY_COLUMNS)
    val sg = SpectrogramPixels(AnalyzerViewModel.DISPLAY_COLUMNS, AnalyzerViewModel.SPECTROGRAM_ROWS)
    var t = 0.0
    var end = n
    var rms = -120.0
    var peak = 0.0
    var avg = DoubleArray(0)
    var holdDb = DoubleArray(0)
    while (end <= sig.size) {
        val block = sig.copyOfRange(end - n, end)
        var ms = 0.0
        peak = 0.0
        for (i in end - hop until end) { ms += sig[i].toDouble() * sig[i]; peak = maxOf(peak, kotlin.math.abs(sig[i].toDouble())) }
        rms = powerToDb(ms / hop)
        t += hop / sr
        val power = sp.power(block)
        an.update(power, t, peak, rms)
        avg = disp.update(power, t)
        val raw = DoubleArray(power.size) { powerToDb(power[it]) }
        holdDb = hold.update(raw, t)
        sg.push(pooler.pool(raw))
        end += hop
    }
    val recs = an.recommendations()
    val slow = an.slow.value!!
    val frame = DisplayFrame(
        live = pooler.pool(DoubleArray(avg.size) { powerToDb(avg[it]) }),
        longTerm = pooler.pool(DoubleArray(slow.size) { powerToDb(slow[it]) }),
        peakHold = pooler.pool(holdDb),
        bands = bandLevels(sp.freqs, avg, sp.enbw),
        bandDev = an.bandDev!!,
        targetLine = DoubleArray(31) { an.target(THIRD_OCTAVE_EXACT[it]) + an.bandOffset },
        spectrogram = sg.pixels.copyOf(), sgColumns = sg.columns, sgRows = sg.rows,
    )
    return AnalyzerUiState(
        running = true, frame = frame, rmsDb = rms, peakDb = powerToDb(peak * peak),
        recommendations = recs,
        markers = recs.filter { it.category == "Feedback" }.map { FeedbackMarker(it.freq!!, it.severity) },
        status = "Listening to Demo signal (synthetic) at 48000 Hz, FFT 8192 (5.9 Hz resolution)",
        elapsed = an.elapsed,
    )
}

class TabletScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = TAB_S10_ULTRA, maxPercentDifference = 1.0)

    @Test
    fun tabS10UltraLandscape() {
        val state = demoState()
        paparazzi.snapshot {
            MaterialTheme(colorScheme = LiveSoundColors) {
                Surface { AnalyzerScreen(state, AnalyzerSettings(), NoActions) }
            }
        }
    }

    @Test
    fun tabS10UltraPortrait() {
        val state = demoState()
        paparazzi.unsafeUpdateConfig(TAB_S10_ULTRA.copy(screenWidth = 1848, screenHeight = 2960,
            orientation = ScreenOrientation.PORTRAIT))
        paparazzi.snapshot {
            MaterialTheme(colorScheme = LiveSoundColors) {
                Surface { AnalyzerScreen(state, AnalyzerSettings(), NoActions) }
            }
        }
    }

    @Test
    fun phoneOrSplitScreen() {
        val state = demoState()
        paparazzi.unsafeUpdateConfig(DeviceConfig.PIXEL_5)
        paparazzi.snapshot {
            MaterialTheme(colorScheme = LiveSoundColors) {
                Surface { AnalyzerScreen(state, AnalyzerSettings(), NoActions) }
            }
        }
    }
}
