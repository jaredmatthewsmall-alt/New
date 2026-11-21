package com.livesound.analyzer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.livesound.analyzer.audio.InputDevice
import com.livesound.analyzer.core.FeedbackSensitivity
import com.livesound.analyzer.core.Recommendation
import com.livesound.analyzer.core.TargetCurve
import com.livesound.analyzer.core.fmt
import com.livesound.analyzer.core.formatHz
import com.livesound.analyzer.core.noteName

val LiveSoundColors = darkColorScheme(
    primary = Color(0xFF40D0FF),
    onPrimary = Color(0xFF00222E),
    secondary = Color(0xFFFFA726),
    background = Color(0xFF121318),
    surface = Color(0xFF1B1D24),
    surfaceVariant = Color(0xFF252833),
    onSurface = Color(0xFFE6E6EA),
)

/** Callbacks from the screen; implemented by MainActivity (and fakes in screenshot tests). */
interface AnalyzerActions {
    fun toggleRun()
    fun setFrozen(frozen: Boolean)
    fun reset()
    fun share()
    fun updateSettings(transform: (AnalyzerSettings) -> AnalyzerSettings)
    fun inputDevices(): List<InputDevice>
}

enum class BarMode { ONE_ROW, TWO_ROWS, COMPACT }

private enum class LowerPane(val label: String) { RTA("1/3-octave RTA"), SPECTROGRAM("Spectrogram") }

/**
 * Stateless main screen. Landscape tablets (Galaxy Tab S10 Ultra: ~1400 x 870 dp) get the full
 * three-panel layout; portrait tablets stack it; phones/split-screen switch RTA and spectrogram.
 */
@Composable
fun AnalyzerScreen(state: AnalyzerUiState, settings: AnalyzerSettings, actions: AnalyzerActions,
                   modifier: Modifier = Modifier) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(10.dp)) {
    // One toolbar row needs ~1250 dp (Tab S10 Ultra landscape); otherwise split it in two.
    val barMode = when {
        maxWidth >= 1250.dp -> BarMode.ONE_ROW
        maxWidth >= 600.dp -> BarMode.TWO_ROWS
        else -> BarMode.COMPACT
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(state, settings, actions, barMode, onSettings = { showSettings = true })
        Spacer(Modifier.height(8.dp))
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 1000.dp && maxWidth > maxHeight
            val tabletPortrait = !wide && maxWidth >= 600.dp
            when {
                wide -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(0.69f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LevelMeter(state.rmsDb, state.peakDb, Modifier.fillMaxWidth())
                        SpectrumGraph(state.frame, state.markers, Modifier.fillMaxWidth().weight(0.58f))
                        Row(Modifier.fillMaxWidth().weight(0.42f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RtaGraph(state.frame, Modifier.weight(1f).fillMaxHeight())
                            SpectrogramGraph(state.frame, Modifier.weight(1f).fillMaxHeight())
                        }
                        StatusLine(state.status)
                    }
                    RecommendationsPanel(state, Modifier.weight(0.31f).fillMaxHeight())
                }
                tabletPortrait -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LevelMeter(state.rmsDb, state.peakDb, Modifier.fillMaxWidth())
                    SpectrumGraph(state.frame, state.markers, Modifier.fillMaxWidth().weight(0.36f))
                    Row(Modifier.fillMaxWidth().weight(0.24f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RtaGraph(state.frame, Modifier.weight(1f).fillMaxHeight())
                        SpectrogramGraph(state.frame, Modifier.weight(1f).fillMaxHeight())
                    }
                    StatusLine(state.status)
                    RecommendationsPanel(state, Modifier.fillMaxWidth().weight(0.4f))
                }
                else -> CompactLayout(state)
            }
        }
    }
    }
    if (showSettings) SettingsDialog(settings, actions, onDismiss = { showSettings = false })
}

@Composable
private fun CompactLayout(state: AnalyzerUiState) {
    var pane by rememberSaveable { mutableStateOf(LowerPane.RTA) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LevelMeter(state.rmsDb, state.peakDb, Modifier.fillMaxWidth())
        SpectrumGraph(state.frame, state.markers, Modifier.fillMaxWidth().weight(0.35f))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            LowerPane.entries.forEachIndexed { i, p ->
                SegmentedButton(selected = pane == p, onClick = { pane = p },
                    shape = SegmentedButtonDefaults.itemShape(i, LowerPane.entries.size)) { Text(p.label) }
            }
        }
        when (pane) {
            LowerPane.RTA -> RtaGraph(state.frame, Modifier.fillMaxWidth().weight(0.25f))
            LowerPane.SPECTROGRAM -> SpectrogramGraph(state.frame, Modifier.fillMaxWidth().weight(0.25f))
        }
        RecommendationsPanel(state, Modifier.fillMaxWidth().weight(0.4f))
    }
}

@Composable
private fun StatusLine(text: String) =
    Text(text, color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)

// ------------------------------------------------------------------ top bar

@Composable
private fun TopBar(state: AnalyzerUiState, settings: AnalyzerSettings, actions: AnalyzerActions,
                   mode: BarMode, onSettings: () -> Unit) {
    if (mode == BarMode.COMPACT) {
        // Phones / split-screen: target & sensitivity live in Settings; the rest in a menu.
        var menu by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Live Sound", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(if (state.frozen) "Unfreeze graphs" else "Freeze graphs") },
                        onClick = { menu = false; actions.setFrozen(!state.frozen) })
                    DropdownMenuItem(text = { Text("Reset averages") }, onClick = { menu = false; actions.reset() })
                    DropdownMenuItem(text = { Text("Share report") }, onClick = { menu = false; actions.share() })
                }
            }
            StartStopButton(state, actions)
        }
        return
    }
    if (mode == BarMode.ONE_ROW) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Title()
            Spacer(Modifier.weight(1f))
            AnalysisControls(settings, actions)
            RunControls(state, actions, onSettings)
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Title()
                Spacer(Modifier.weight(1f))
                RunControls(state, actions, onSettings)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AnalysisControls(settings, actions)
            }
        }
    }
}

@Composable
private fun Title() =
    Text("Live Sound Analyzer", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1)

@Composable
private fun AnalysisControls(settings: AnalyzerSettings, actions: AnalyzerActions) {
        ChoiceButton("Input", settings.source.label, InputSource.entries.map { it.label }) { i ->
            actions.updateSettings { it.copy(source = InputSource.entries[i]) }
        }
        ChoiceButton("Target", settings.target.label, TargetCurve.entries.map { it.label }) { i ->
            actions.updateSettings { it.copy(target = TargetCurve.entries[i]) }
        }
        Text("Feedback", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
        SingleChoiceSegmentedButtonRow(Modifier.width(250.dp)) {
            FeedbackSensitivity.entries.forEachIndexed { i, s ->
                SegmentedButton(selected = settings.sensitivity == s,
                    onClick = { actions.updateSettings { it.copy(sensitivity = s) } },
                    shape = SegmentedButtonDefaults.itemShape(i, FeedbackSensitivity.entries.size)) { Text(s.label) }
            }
        }
}

@Composable
private fun RunControls(state: AnalyzerUiState, actions: AnalyzerActions, onSettings: () -> Unit) {
        FilterChip(selected = state.frozen, onClick = { actions.setFrozen(!state.frozen) }, label = { Text("Freeze") })
        IconButton(onClick = actions::reset) { Icon(Icons.Filled.Refresh, "Reset averages") }
        IconButton(onClick = actions::share) { Icon(Icons.Filled.Share, "Share report") }
        IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings") }
        StartStopButton(state, actions)
}

@Composable
private fun StartStopButton(state: AnalyzerUiState, actions: AnalyzerActions) {
        Button(onClick = actions::toggleRun, modifier = Modifier.width(110.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (state.running) Color(0xFFD83A3A) else Color(0xFF2E9E4F),
                contentColor = Color.White)) {
            Text(if (state.running) "Stop" else "Start", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
}

@Composable
private fun ChoiceButton(label: String, current: String, options: List<String>, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) {
            Text("$label: ", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
            Text(current, fontSize = 13.sp, maxLines = 1)
            Icon(Icons.Filled.KeyboardArrowDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, o -> DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onSelect(i) }) }
        }
    }
}

// ------------------------------------------------------------------ recommendations

@Composable
fun RecommendationsPanel(state: AnalyzerUiState, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(setOf<String>()) }
    Column(modifier
        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
        .padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Recommendations", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.weight(1f))
            if (state.recommendations.isNotEmpty()) {
                Text("${state.recommendations.size}", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
            }
        }
        Text("Tap an item for the explanation", color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        if (state.recommendations.isEmpty()) {
            Text(
                when {
                    !state.running -> "Press Start, then point the tablet's mic (or a measurement mic on a USB " +
                        "interface) at the PA or listening area.\n\nChoose Input: Demo signal to try it without a microphone."
                    state.elapsed < 3 -> "Listening... tonal analysis starts after a few seconds."
                    else -> "No issues detected."
                },
                color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.recommendations, key = { it.id }) { r ->
                    val open = r.id in expanded
                    RecommendationCard(r, open) { expanded = if (open) expanded - r.id else expanded + r.id }
                }
            }
        }
    }
}

@Composable
private fun RecommendationCard(r: Recommendation, open: Boolean, onClick: () -> Unit) {
    val c = severityColor(r.severity)
    Row(Modifier
        .fillMaxWidth()
        .height(IntrinsicSize.Min)
        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
        .clickable(onClick = onClick)) {
        Box(Modifier.width(5.dp).fillMaxHeight().background(c, RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)))
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp).weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.severity.label.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Black, color = Color.Black,
                    modifier = Modifier.background(c, RoundedCornerShape(50)).padding(horizontal = 6.dp, vertical = 1.dp))
                Spacer(Modifier.width(8.dp))
                Text(r.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(r.category, fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f))
                Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null,
                    tint = Color.White.copy(alpha = 0.5f))
            }
            Spacer(Modifier.height(3.dp))
            Text(r.action, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
            if (open) {
                Spacer(Modifier.height(6.dp))
                Text(r.detail, fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f))
                r.freq?.let { f ->
                    var s = "${formatHz(f)} (${noteName(f)})"
                    r.gainDb?.let { s += fmt(", gain %+.1f dB", it) }
                    r.q?.let { s += fmt(", Q %.1f", it) }
                    Text(s, fontSize = 13.sp, fontStyle = FontStyle.Italic, color = Color.White.copy(alpha = 0.6f))
                }
            }
        }
    }
}

// ------------------------------------------------------------------ settings

@Composable
private fun SettingsDialog(settings: AnalyzerSettings, actions: AnalyzerActions, onDismiss: () -> Unit) {
    val devices = remember { actions.inputDevices() }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).widthIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingRow("Source", settings.source.label, InputSource.entries.map { it.label }) { i ->
                    actions.updateSettings { it.copy(source = InputSource.entries[i]) }
                }
                if (settings.source == InputSource.MICROPHONE) {
                    val names = listOf("Automatic") + devices.map { it.name }
                    val current = devices.firstOrNull { it.id == settings.deviceId }?.name ?: "Automatic"
                    SettingRow("Input device", current, names) { i ->
                        actions.updateSettings { it.copy(deviceId = if (i == 0) null else devices[i - 1].id) }
                    }
                }
                HorizontalDivider()
                SettingRow("Target curve", settings.target.label, TargetCurve.entries.map { it.label }) { i ->
                    actions.updateSettings { it.copy(target = TargetCurve.entries[i]) }
                }
                SettingRow("Feedback sensitivity", settings.sensitivity.label, FeedbackSensitivity.entries.map { it.label }) { i ->
                    actions.updateSettings { it.copy(sensitivity = FeedbackSensitivity.entries[i]) }
                }
                SettingRow("Mains frequency", settings.mains.label, MainsSetting.entries.map { it.label }) { i ->
                    actions.updateSettings { it.copy(mains = MainsSetting.entries[i]) }
                }
                HorizontalDivider()
                SettingRow("FFT size", "${settings.fftSize}", AnalyzerViewModel.FFT_SIZES.map { "$it" }) { i ->
                    actions.updateSettings { it.copy(fftSize = AnalyzerViewModel.FFT_SIZES[i]) }
                }
                SettingRow("Averaging", settings.averaging.label, DisplayAveraging.entries.map { it.label }) { i ->
                    actions.updateSettings { it.copy(averaging = DisplayAveraging.entries[i]) }
                }
                HorizontalDivider()
                Text("Tips", fontWeight = FontWeight.Bold)
                Text("- For accurate measurements use a measurement mic on a class-compliant USB-C audio " +
                    "interface and choose it as the input device. The tablet's built-in mics are fine for " +
                    "spotting feedback and big tonal problems.\n" +
                    "- Ringing out monitors: set Feedback to High and raise the gain slowly. Each ring shows " +
                    "as a red marker with the notch to apply.\n" +
                    "- System tuning: play pink noise through the PA and choose the Flat target.\n" +
                    "- During a show: use the house curve. Tonal advice uses a 4-second average.",
                    fontSize = 13.sp, color = Color.White.copy(alpha = 0.75f))
            }
        },
    )
}

@Composable
private fun SettingRow(label: String, current: String, options: List<String>, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(0.4f), fontSize = 15.sp)
        Box(Modifier.weight(0.6f)) {
            Row(Modifier
                .fillMaxWidth()
                .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                .clickable { open = true }
                .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(current, modifier = Modifier.weight(1f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Filled.KeyboardArrowDown, null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEachIndexed { i, o -> DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onSelect(i) }) }
            }
        }
    }
}
