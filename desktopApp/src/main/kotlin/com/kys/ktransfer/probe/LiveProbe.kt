package com.kys.ktransfer.probe

import com.kys.ktransfer.live.CHUNK_MS
import com.kys.ktransfer.live.INPUT_CHUNK_BYTES
import com.kys.ktransfer.live.INPUT_SAMPLE_RATE
import com.kys.ktransfer.live.LiveSession
import com.kys.ktransfer.live.OUTPUT_SAMPLE_RATE
import com.kys.ktransfer.live.ServerEvent
import com.kys.ktransfer.live.TranslateConfig
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Properties
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.system.exitProcess

/**
 * Streams a WAV file to Live Translate at real-time pace (1 s silence, speech, tail silence)
 * and reports when the first input transcript and first translated audio arrive.
 *
 * Args: <wav> <targetLang> [--resumption] [--out <wav>] [--tail <seconds>]
 * Key: GEMINI_API_KEY environment variable, or gemini.apiKey in local.properties.
 */
fun main(args: Array<String>): Unit = runBlocking {
    if (args.size < 2) {
        System.err.println("usage: <wav> <targetLang> [--resumption] [--out <wav>] [--tail <seconds>]")
        exitProcess(2)
    }
    val wav = File(args[0])
    val target = args[1]
    val resumption = "--resumption" in args
    val out = args.valueAfter("--out")?.let(::File)
    val tailSeconds = args.valueAfter("--tail")?.toInt() ?: 8
    val apiKey = System.getenv("GEMINI_API_KEY") ?: localProperty("gemini.apiKey")
        ?: run { System.err.println("GEMINI_API_KEY or local.properties gemini.apiKey is required"); exitProcess(2) }

    val speech = loadPcm16kMono(wav)
    val speechMs = speech.size / 2 * 1000L / INPUT_SAMPLE_RATE
    val t0 = System.nanoTime()
    fun now() = (System.nanoTime() - t0) / 1_000_000
    fun log(message: String) = println("%6d ms  %s".format(now(), message))

    val http = HttpClient { install(WebSockets) }
    val session = LiveSession(http, apiKey)
    val setup = CompletableDeferred<Unit>()
    val outputAudio = ByteArrayOutputStream()
    val inputText = StringBuilder()
    val outputText = StringBuilder()
    var speechStart = -1L
    var firstInput = -1L
    var firstAudio = -1L
    var lastAudio = -1L
    var lastVoicedAudio = -1L
    var voicedBytes = 0L
    var resumptionUpdates = 0

    val connection = launch {
        val reason = runCatching {
            session.connect(TranslateConfig(target, requestResumption = resumption)) { event ->
                when (event) {
                    ServerEvent.SetupComplete -> { log("setupComplete"); setup.complete(Unit) }
                    is ServerEvent.InputTranscript -> {
                        if (firstInput < 0 && speechStart >= 0) firstInput = now()
                        inputText.append(event.text)
                    }
                    is ServerEvent.OutputTranscript -> outputText.append(event.text)
                    is ServerEvent.Audio -> {
                        if (firstAudio < 0 && speechStart >= 0) { firstAudio = now(); log("first translated audio") }
                        lastAudio = now()
                        if (rms(event.pcm) > VOICED_RMS) { lastVoicedAudio = lastAudio; voicedBytes += event.pcm.size }
                        outputAudio.write(event.pcm)
                    }
                    ServerEvent.TurnComplete -> log("turnComplete")
                    ServerEvent.Interrupted -> log("interrupted")
                    is ServerEvent.GoAway -> log("goAway timeLeft=${event.timeLeft}")
                    is ServerEvent.Resumption -> {
                        resumptionUpdates++
                        if (resumptionUpdates == 1) log("sessionResumptionUpdate resumable=${event.resumable} handle=${event.newHandle != null}")
                    }
                    is ServerEvent.Other -> log("other message keys=${event.keys}")
                }
            }
        }.getOrElse { "error: ${it::class.simpleName}: ${it.message}" }
        log("connection closed: $reason")
        setup.complete(Unit)
    }

    runCatching { withTimeout(15_000) { setup.await() } }
        .onFailure { log("setup timeout"); connection.cancel(); http.close(); exitProcess(1) }
    if (!connection.isActive) { http.close(); exitProcess(1) }

    val silence = ByteArray(INPUT_CHUNK_BYTES)
    var deadline = now()
    suspend fun pace() { deadline += CHUNK_MS; delay((deadline - now()).coerceAtLeast(0)) }
    repeat(1000 / CHUNK_MS) { session.sendAudio(silence); pace() }
    speechStart = now()
    log("speech start (${speechMs} ms of audio)")
    var offset = 0
    while (offset < speech.size) {
        val chunk = speech.copyOfRange(offset, minOf(offset + INPUT_CHUNK_BYTES, speech.size))
        session.sendAudio(if (chunk.size == INPUT_CHUNK_BYTES) chunk else chunk.copyOf(INPUT_CHUNK_BYTES))
        offset += INPUT_CHUNK_BYTES
        pace()
    }
    val speechEnd = now()
    log("speech end")
    repeat(tailSeconds * 1000 / CHUNK_MS) { session.sendAudio(silence); pace() }
    connection.cancel()
    http.close()

    val outMs = outputAudio.size() / 2 * 1000L / OUTPUT_SAMPLE_RATE
    println()
    println("target=$target resumptionRequested=$resumption resumptionUpdates=$resumptionUpdates")
    println("first input transcript after speech start: ${if (firstInput >= 0) "${firstInput - speechStart} ms" else "none"}")
    println("first translated audio after speech start: ${if (firstAudio >= 0) "${firstAudio - speechStart} ms" else "none"}")
    println("last translated audio after speech end:    ${if (lastAudio >= 0) "${lastAudio - speechEnd} ms" else "none"}")
    println("last voiced audio after speech end:        ${if (lastVoicedAudio >= 0) "${lastVoicedAudio - speechEnd} ms" else "none"}")
    println("translated audio total: $outMs ms, voiced ${voicedBytes / 2 * 1000L / OUTPUT_SAMPLE_RATE} ms (input speech $speechMs ms)")
    println("input transcript:  ${inputText.toString().trim()}")
    println("output transcript: ${outputText.toString().trim()}")
    out?.let {
        val bytes = outputAudio.toByteArray()
        val format = AudioFormat(OUTPUT_SAMPLE_RATE.toFloat(), 16, 1, true, false)
        AudioSystem.write(AudioInputStream(ByteArrayInputStream(bytes), format, bytes.size / 2L), AudioFileFormat.Type.WAVE, it)
        println("wrote ${it.path}")
    }
    exitProcess(if (firstAudio >= 0) 0 else 1)
}

private fun Array<String>.valueAfter(flag: String): String? = indexOf(flag).takeIf { it >= 0 }?.let { getOrNull(it + 1) }

private const val VOICED_RMS = 300.0

private fun rms(pcm: ByteArray): Double {
    var sum = 0.0
    val samples = pcm.size / 2
    for (i in 0 until samples) {
        val s = ((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xFF)).toShort().toDouble()
        sum += s * s
    }
    return if (samples == 0) 0.0 else kotlin.math.sqrt(sum / samples)
}

private fun localProperty(name: String): String? {
    val file = File("local.properties").takeIf { it.isFile } ?: return null
    return Properties().apply { file.inputStream().use { load(it) } }.getProperty(name)?.takeIf { it.isNotBlank() }
}

private fun loadPcm16kMono(file: File): ByteArray {
    val target = AudioFormat(INPUT_SAMPLE_RATE.toFloat(), 16, 1, true, false)
    AudioSystem.getAudioInputStream(file).use { source ->
        val pcm = if (source.format.matches(target)) source else AudioSystem.getAudioInputStream(target, source)
        return pcm.use { it.readAllBytes() }
    }
}
