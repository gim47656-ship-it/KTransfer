package com.kys.ktransfer

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Build
import com.kys.ktransfer.live.INPUT_CHUNK_BYTES
import com.kys.ktransfer.live.INPUT_SAMPLE_RATE
import com.kys.ktransfer.live.OUTPUT_SAMPLE_RATE
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

fun androidPlatform(context: Context): Platform =
    Platform(AndroidAudioDevice(context.applicationContext), AndroidSettingsStore(context.applicationContext))

private class AndroidSettingsStore(context: Context) : SettingsStore {
    // App-private storage; readable only by this app on a non-rooted device.
    private val prefs = context.getSharedPreferences("ktransfer", Context.MODE_PRIVATE)
    override fun load(key: String): String? = prefs.getString(key, null)
    override fun save(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}

private class AndroidAudioDevice(context: Context) : AudioDevice {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    override fun openMic(echoCancel: Boolean): MicSource = AndroidMic(audioManager, echoCancel)
    override fun openPlayer(echoCancel: Boolean): PcmPlayer = AndroidPlayer(echoCancel)
}

/**
 * State before entering communication mode. On API 31+ the speaker route is this app's own
 * setCommunicationDevice request, so restoring means clearing that request, not re-selecting a device.
 */
private class SavedRoute(val mode: Int, val speakerphone: Boolean)

private class AndroidMic(private val audioManager: AudioManager, private val echoCancel: Boolean) : MicSource {
    private var record: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var worker: Thread? = null
    private var savedRoute: SavedRoute? = null
    @Volatile private var running = false

    @SuppressLint("MissingPermission") // The activity requests RECORD_AUDIO; failure surfaces as an exception.
    override fun start(onChunk: (ByteArray) -> Unit) {
        try {
            if (echoCancel) enterCommunicationMode()
            val source = if (echoCancel) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC
            val minBuffer = AudioRecord.getMinBufferSize(INPUT_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord(source, INPUT_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, INPUT_CHUNK_BYTES * 4))
            record = rec
            check(rec.state == AudioRecord.STATE_INITIALIZED) { "마이크를 열 수 없습니다 (권한 확인)" }
            if (echoCancel && AcousticEchoCanceler.isAvailable()) {
                val effect = AcousticEchoCanceler.create(rec.audioSessionId)
                aec = effect
                effect?.enabled = true
            }
            rec.startRecording()
            running = true
            worker = thread(name = "ktransfer-mic") { readLoop(rec, onChunk) }
        } catch (e: Exception) {
            stop()
            throw e
        }
    }

    private fun readLoop(rec: AudioRecord, onChunk: (ByteArray) -> Unit) {
        val buffer = ByteArray(INPUT_CHUNK_BYTES)
        while (running) {
            var filled = 0
            while (filled < buffer.size && running) {
                val n = rec.read(buffer, filled, buffer.size - filled)
                if (n <= 0) {
                    // ERROR_DEAD_OBJECT and friends do not recover: end capture instead of spinning.
                    running = false
                    break
                }
                filled += n
            }
            if (filled == buffer.size) onChunk(buffer.copyOf())
        }
    }

    override fun stop() {
        running = false
        try {
            worker?.join(500)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            worker = null
            record?.let { rec ->
                try {
                    if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) rec.stop()
                } catch (_: IllegalStateException) {
                } finally {
                    rec.release()
                }
            }
            record = null
            aec?.let { runCatching { it.release() } }
            aec = null
            savedRoute?.let { restoreRoute(it) }
            savedRoute = null
        }
    }

    private fun enterCommunicationMode() {
        @Suppress("DEPRECATION")
        val saved = SavedRoute(mode = audioManager.mode, speakerphone = audioManager.isSpeakerphoneOn)
        savedRoute = saved
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?.let { audioManager.setCommunicationDevice(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = true
        }
    }

    private fun restoreRoute(saved: SavedRoute) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = saved.speakerphone
        }
        audioManager.mode = saved.mode
    }
}

/**
 * Writes are split into [WRITE_PIECE_BYTES] pieces and re-check the generation between pieces,
 * so clear() and release() stop an in-flight write within one piece plus the track buffer.
 */
private class AndroidPlayer(echoCancel: Boolean) : PcmPlayer {
    private val track: AudioTrack
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "ktransfer-player") }
    private val generation = AtomicInteger()

    init {
        val minBuffer = AudioTrack.getMinBufferSize(OUTPUT_SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(if (echoCancel) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(OUTPUT_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuffer, OUTPUT_SAMPLE_RATE / 5 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
    }

    override fun play(pcm: ByteArray) {
        val gen = generation.get()
        submit {
            var offset = 0
            while (offset < pcm.size && gen == generation.get()) {
                val written = track.write(pcm, offset, minOf(WRITE_PIECE_BYTES, pcm.size - offset))
                if (written <= 0) break
                offset += written
            }
        }
    }

    override fun clear() {
        val gen = generation.incrementAndGet()
        submit {
            if (gen == generation.get()) {
                track.pause()
                track.flush()
                track.play()
            }
        }
    }

    override fun release() {
        generation.incrementAndGet()
        writer.shutdown()
        try {
            if (!writer.awaitTermination(1, TimeUnit.SECONDS)) {
                // A blocking write is stuck; stop() makes it return, then the piece loop sees the new generation.
                track.stop()
                writer.awaitTermination(1, TimeUnit.SECONDS)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    private fun submit(task: () -> Unit) {
        try {
            writer.execute(task)
        } catch (_: RejectedExecutionException) {
            // Released: nothing to play.
        }
    }

    private companion object {
        const val WRITE_PIECE_BYTES = OUTPUT_SAMPLE_RATE * 2 * 20 / 1000
    }
}
