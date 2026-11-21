package com.livesound.analyzer.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import com.livesound.analyzer.core.DemoSignal
import com.livesound.analyzer.core.RingBuffer
import kotlin.math.max

/** An input the user can choose (built-in mics, USB interface, wired headset...). */
data class InputDevice(val id: Int, val name: String)

/**
 * Captures mono float audio into a [RingBuffer] from the microphone (AudioRecord) or from the
 * built-in demo signal.
 */
class AudioCapture(context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Volatile var sampleRate = 48000; private set
    @Volatile var buffer = RingBuffer(48000 * 4); private set
    @Volatile var inputName = ""; private set
    /** Which Android audio source is in use (shown in the status line). */
    @Volatile var sourceDescription = ""; private set
    @Volatile var running = false; private set

    private var record: AudioRecord? = null
    private var thread: Thread? = null

    fun inputDevices(): List<InputDevice> =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.type != AudioDeviceInfo.TYPE_TELEPHONY && it.type != AudioDeviceInfo.TYPE_REMOTE_SUBMIX }
            .map { InputDevice(it.id, describe(it)) }
            .distinctBy { it.name }

    private fun describe(d: AudioDeviceInfo): String {
        val kind = when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in mic"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB audio"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth (low quality)"
            else -> "Input"
        }
        val product = d.productName?.toString().orEmpty()
        val addr = if (Build.VERSION.SDK_INT >= 28 && d.address.isNotBlank()) " ${d.address}" else ""
        return if (product.isBlank() || d.type == AudioDeviceInfo.TYPE_BUILTIN_MIC) "$kind$addr" else "$kind: $product"
    }

    /** Requires RECORD_AUDIO permission (checked by the caller). */
    @SuppressLint("MissingPermission")
    fun startMicrophone(preferredDeviceId: Int?) {
        stop()
        // UNPROCESSED = no AGC, noise suppression or EQ: what a measurement needs. Fall back to
        // VOICE_RECOGNITION, which Android requires to have AGC off as well.
        val unprocessed = audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        sourceDescription = if (unprocessed) "unprocessed" else "voice-recognition (AGC off)"

        val sr = 48000
        val minBytes = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        require(minBytes > 0) { "This device can't record 48 kHz float audio." }
        val rec = AudioRecord.Builder()
            .setAudioSource(source)
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(sr)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .build())
            .setBufferSizeInBytes(max(minBytes * 2, 8192 * 4))
            .build()
        check(rec.state == AudioRecord.STATE_INITIALIZED) {
            "Could not open the microphone. Another app may be using it."
        }
        preferredDeviceId?.let { id ->
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == id }
                ?.let { rec.setPreferredDevice(it) }
        }
        sampleRate = rec.sampleRate
        val ring = RingBuffer(sampleRate * 4)
        buffer = ring
        rec.startRecording()
        record = rec
        inputName = rec.routedDevice?.let { describe(it) } ?: "Microphone"
        running = true
        thread = Thread({
            val chunk = FloatArray(1024)
            while (running) {
                val n = rec.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
                if (n > 0) ring.write(chunk, n) else if (n < 0) break
            }
        }, "LiveSound-capture").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    /** Synthetic signal: pink noise + hum + building feedback. */
    fun startDemo() {
        stop()
        sampleRate = 48000
        val ring = RingBuffer(sampleRate * 4)
        buffer = ring
        inputName = "Demo signal"
        sourceDescription = "synthetic"
        running = true
        thread = Thread({
            val demo = DemoSignal(sampleRate.toDouble())
            val start = System.nanoTime()
            var generated = 0L
            while (running) {
                val due = ((System.nanoTime() - start) / 1e9 * sampleRate).toLong()
                if (due - generated > sampleRate) generated = due - sampleRate // resync after a stall
                val todo = (due - generated).toInt()
                if (todo > 0) {
                    ring.write(demo.generate(todo))
                    generated += todo
                }
                Thread.sleep(10)
            }
        }, "LiveSound-demo").apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
    }
}
