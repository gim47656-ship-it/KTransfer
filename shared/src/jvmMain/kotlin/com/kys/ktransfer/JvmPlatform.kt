package com.kys.ktransfer

import com.kys.ktransfer.live.INPUT_CHUNK_BYTES
import com.kys.ktransfer.live.INPUT_SAMPLE_RATE
import com.kys.ktransfer.live.OUTPUT_SAMPLE_RATE
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.prefs.Preferences
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine
import kotlin.concurrent.thread

fun jvmPlatform(): Platform = Platform(JvmAudioDevice(), JvmSettingsStore())

private class JvmSettingsStore : SettingsStore {
    private val prefs = Preferences.userRoot().node("com/kys/ktransfer")

    override fun load(key: String): String? =
        prefs.get(key, null) ?: if (key == "apiKey") System.getenv("GEMINI_API_KEY") else null

    override fun save(key: String, value: String) {
        prefs.put(key, value)
    }
}

/** Desktop has no platform AEC here; [echoCancel] is ignored. */
private class JvmAudioDevice : AudioDevice {
    override fun openMic(echoCancel: Boolean): MicSource = JvmMic()
    override fun openPlayer(echoCancel: Boolean): PcmPlayer = JvmPlayer()
}

private class JvmMic : MicSource {
    private var line: TargetDataLine? = null
    private var worker: Thread? = null
    @Volatile private var running = false

    override fun start(onChunk: (ByteArray) -> Unit) {
        val format = AudioFormat(INPUT_SAMPLE_RATE.toFloat(), 16, 1, true, false)
        val mic = AudioSystem.getTargetDataLine(format)
        mic.open(format, INPUT_CHUNK_BYTES * 4)
        mic.start()
        line = mic
        running = true
        worker = thread(name = "ktransfer-mic") {
            val buffer = ByteArray(INPUT_CHUNK_BYTES)
            while (running) {
                var filled = 0
                while (filled < buffer.size && running) {
                    val n = mic.read(buffer, filled, buffer.size - filled)
                    if (n <= 0) break
                    filled += n
                }
                if (filled == buffer.size) onChunk(buffer.copyOf())
            }
        }
    }

    override fun stop() {
        running = false
        line?.run { stop(); close() }
        worker?.join(500)
        line = null
    }
}

private class JvmPlayer : PcmPlayer {
    private val format = AudioFormat(OUTPUT_SAMPLE_RATE.toFloat(), 16, 1, true, false)
    private val line: SourceDataLine = AudioSystem.getSourceDataLine(format).apply {
        open(format, OUTPUT_SAMPLE_RATE / 2 * 2)
        start()
    }
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "ktransfer-player").apply { isDaemon = true } }
    private val generation = AtomicInteger()

    override fun play(pcm: ByteArray) {
        val gen = generation.get()
        writer.execute { if (gen == generation.get()) line.write(pcm, 0, pcm.size) }
    }

    override fun clear() {
        generation.incrementAndGet()
        line.flush()
    }

    override fun release() {
        generation.incrementAndGet()
        writer.shutdownNow()
        line.stop()
        line.close()
    }
}
