package com.livesound.analyzer.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.livesound.analyzer.audio.InputDevice

class MainActivity : ComponentActivity() {
    private val vm: AnalyzerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val autoDemo = intent?.getBooleanExtra("demo", false) == true
        setContent {
            MaterialTheme(colorScheme = LiveSoundColors) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    App(vm, autoDemo)
                }
            }
        }
    }

    @Composable
    private fun App(vm: AnalyzerViewModel, autoDemo: Boolean) {
        var showPermissionDialog by remember { mutableStateOf(false) }
        val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) vm.start() else showPermissionDialog = true
        }

        fun startWithPermission() {
            val needsMic = vm.settings.source == InputSource.MICROPHONE
            val granted = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (!needsMic || granted) vm.start() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        val actions = remember(vm) {
            object : AnalyzerActions {
                override fun toggleRun() = if (vm.ui.running) vm.stop() else startWithPermission()
                override fun setFrozen(frozen: Boolean) = vm.setFrozen(frozen)
                override fun reset() = vm.reset()
                override fun share() {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Live Sound report")
                        putExtra(Intent.EXTRA_TEXT, vm.reportText())
                    }
                    startActivity(Intent.createChooser(send, "Share report"))
                }
                override fun updateSettings(transform: (AnalyzerSettings) -> AnalyzerSettings) {
                    val after = transform(vm.settings)
                    val granted = ContextCompat.checkSelfPermission(this@MainActivity,
                        Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    if (vm.ui.running && after.source == InputSource.MICROPHONE && !granted) {
                        // Switching from the demo to the mic while running: ask first, then start.
                        vm.stop()
                        vm.update { after }
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        vm.update { after }
                    }
                }
                override fun inputDevices(): List<InputDevice> = vm.inputDevices()
            }
        }

        // Keep the screen awake while measuring; stop when the app goes to the background
        // (Android doesn't allow background microphone capture without a foreground service).
        val view = LocalView.current
        DisposableEffect(vm.ui.running) {
            view.keepScreenOn = vm.ui.running
            onDispose { view.keepScreenOn = false }
        }
        LifecycleEventEffect(Lifecycle.Event.ON_STOP) { if (vm.ui.running) vm.stop() }
        LifecycleEventEffect(Lifecycle.Event.ON_START) {
            if (autoDemo && !vm.ui.running) {
                vm.update { it.copy(source = InputSource.DEMO) }
                vm.start()
            }
        }

        AnalyzerScreen(vm.ui, vm.settings, actions, Modifier.safeDrawingPadding())

        if (showPermissionDialog) {
            AlertDialog(
                onDismissRequest = { showPermissionDialog = false },
                title = { Text("Microphone access is off") },
                text = { Text("Live Sound needs the microphone to analyze your sound. Audio is analyzed on " +
                    "the tablet and never recorded or sent anywhere.") },
                confirmButton = {
                    TextButton(onClick = {
                        showPermissionDialog = false
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", packageName, null)))
                    }) { Text("Open settings") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showPermissionDialog = false
                        vm.update { it.copy(source = InputSource.DEMO) }
                        vm.start()
                    }) { Text("Use demo signal") }
                },
            )
        }
        vm.errorMessage?.let { msg ->
            AlertDialog(onDismissRequest = { vm.errorMessage = null },
                title = { Text("Could not start audio") }, text = { Text(msg) },
                confirmButton = { TextButton(onClick = { vm.errorMessage = null }) { Text("OK") } })
        }
        if (vm.silenceWarning) {
            AlertDialog(onDismissRequest = { vm.silenceWarning = false },
                title = { Text("No audio from the input") },
                text = { Text("The microphone is delivering complete silence. Another app (a call, voice " +
                    "assistant or recorder) may be using it, or microphone access is blocked in the quick " +
                    "settings panel. If you're using a USB interface, check it's powered and selected.") },
                confirmButton = { TextButton(onClick = { vm.silenceWarning = false }) { Text("OK") } })
        }
    }
}
