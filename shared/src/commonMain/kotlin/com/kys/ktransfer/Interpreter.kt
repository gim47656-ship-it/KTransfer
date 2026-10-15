package com.kys.ktransfer

import com.kys.ktransfer.live.CHUNK_MS
import com.kys.ktransfer.live.LiveSession
import com.kys.ktransfer.live.OUTPUT_SAMPLE_RATE
import com.kys.ktransfer.live.ServerEvent
import com.kys.ktransfer.live.TranslateConfig
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt
import kotlin.time.TimeSource

enum class Mode { LISTEN, CONVERSATION }

data class RunOptions(
    val mode: Mode,
    val partner: Language,
    /** Android: VOICE_COMMUNICATION mic + hardware AEC + communication playback. */
    val echoCancel: Boolean,
    /** Send silence to every session while any translation is still playing. */
    val halfDuplex: Boolean,
    val requestResumption: Boolean,
)

data class LaneState(
    val target: Language,
    val status: String = "대기",
    val source: String = "",
    val translated: String = "",
    val lastLatencyMs: Long? = null,
    val latencies: List<Long> = emptyList(),
    val resumable: Boolean? = null,
)

data class InterpreterState(
    val running: Boolean = false,
    val lanes: List<LaneState> = emptyList(),
    val micLevel: Int = 0,
    val gated: Boolean = false,
    val log: List<String> = emptyList(),
    /** Run that owns [lanes]; lane updates from any other run are dropped atomically. */
    val run: Int = 0,
)

/**
 * Feeds one microphone into one session per target language. In conversation mode the two
 * sessions use echoTargetLanguage=false, so only the session whose target differs from the
 * spoken language answers: direction switches without a button.
 */
class Interpreter(private val audio: AudioDevice) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = HttpClient { install(WebSockets) }
    private val clock = TimeSource.Monotonic.markNow()
    private val _state = MutableStateFlow(InterpreterState())
    val state: StateFlow<InterpreterState> = _state

    private var job: Job? = null
    private var mic: MicSource? = null
    @kotlin.concurrent.Volatile private var lanes: List<Lane> = emptyList()
    @kotlin.concurrent.Volatile private var options: RunOptions? = null
    /** Incremented on every start and stop; callbacks from an older run are ignored. */
    @kotlin.concurrent.Volatile private var runId = 0
    /** Cleanup of earlier runs, chained so each waits for the previous one. */
    private var cleanup: Job? = null
    @kotlin.concurrent.Volatile private var lastVoiceAt = Long.MIN_VALUE / 2

    private class Lane(val runId: Int, val index: Int, val target: Language, val session: LiveSession, val player: PcmPlayer) {
        @kotlin.concurrent.Volatile var playingUntil = 0L
        @kotlin.concurrent.Volatile var speechStartedAt: Long? = null
        var handle: String? = null
        var resumable = false
        var failures = 0
        val seenOther = mutableSetOf<Set<String>>()
    }

    fun start(apiKey: String, options: RunOptions) {
        if (job != null) return
        val targets = when (options.mode) {
            Mode.LISTEN -> listOf(KOREAN)
            Mode.CONVERSATION -> listOf(KOREAN, options.partner)
        }
        val run = ++runId
        val opened = mutableListOf<Lane>()
        try {
            targets.forEachIndexed { i, target ->
                opened += Lane(run, i, target, LiveSession(http, apiKey), audio.openPlayer(options.echoCancel))
            }
        } catch (e: Exception) {
            opened.forEach { it.player.release() }
            _state.value = InterpreterState()
            log("스피커 열기 실패: ${e.message}")
            return
        }
        this.options = options
        lanes = opened
        _state.value = InterpreterState(running = true, lanes = targets.map { LaneState(it) }, run = run)
        job = scope.launch { opened.forEach { lane -> launch { runLane(lane, options) } } }
        try {
            val newMic = audio.openMic(options.echoCancel)
            mic = newMic
            newMic.start(::onMicChunk)
            log("마이크 시작 (에코 제거 ${onOff(options.echoCancel)}, 반이중 ${onOff(options.halfDuplex)})")
        } catch (e: Exception) {
            log("마이크 실패: ${e.message}")
            stop()
        }
    }

    fun stop() {
        stopRun()
    }

    fun close() {
        val released = stopRun()
        scope.launch {
            released.join()
            http.close()
            scope.cancel()
        }
    }

    /**
     * Stops the current run; players are released only after its session callbacks have finished.
     * The returned cleanup also waits for every earlier cleanup.
     */
    private fun stopRun(): Job {
        val newRun = ++runId
        mic?.stop()
        mic = null
        val oldJob = job
        val oldLanes = lanes
        job = null
        lanes = emptyList()
        _state.update { it.copy(running = false, micLevel = 0, gated = false, run = newRun) }
        val previous = cleanup
        return scope.launch {
            previous?.join()
            oldJob?.cancelAndJoin()
            oldLanes.forEach { it.player.release() }
        }.also { cleanup = it }
    }

    private fun now(): Long = clock.elapsedNow().inWholeMilliseconds

    private fun onMicChunk(pcm: ByteArray) {
        val opts = options ?: return
        val t = now()
        val playing = lanes.any { t < it.playingUntil + PLAYBACK_TAIL_MS }
        val gated = opts.halfDuplex && playing
        val level = rms(pcm)
        if (!gated && level > SPEECH_RMS) {
            if (t - lastVoiceAt > SILENCE_GAP_MS) lanes.forEach { it.speechStartedAt = t - CHUNK_MS }
            lastVoiceAt = t
        }
        val payload = if (gated) ByteArray(pcm.size) else pcm
        lanes.forEach { it.session.sendAudio(payload) }
        _state.update { it.copy(micLevel = level, gated = gated) }
    }

    private suspend fun runLane(lane: Lane, opts: RunOptions) {
        val tag = "[${lane.target.code}]"
        while (currentCoroutineContext().isActive) {
            updateLane(lane) { it.copy(status = "연결 중") }
            val config = TranslateConfig(
                targetLanguage = lane.target.code,
                echoTargetLanguage = false,
                requestResumption = opts.requestResumption,
                resumptionHandle = lane.handle.takeIf { lane.resumable },
            )
            val reason = try {
                lane.session.connect(config) { onEvent(lane, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "오류: ${e.message ?: e::class.simpleName}"
            }
            if (lane.runId != runId) return
            log("$tag 연결 종료: $reason", lane)
            lane.failures++
            if (lane.failures >= MAX_FAILURES) {
                updateLane(lane) { it.copy(status = "중단: $reason") }
                return
            }
            updateLane(lane) { it.copy(status = "재연결 대기") }
            delay(minOf(1000L * lane.failures, 5000L))
        }
    }

    private fun onEvent(lane: Lane, event: ServerEvent) {
        if (lane.runId != runId) return
        val tag = "[${lane.target.code}]"
        when (event) {
            ServerEvent.SetupComplete -> {
                lane.failures = 0
                updateLane(lane) { it.copy(status = "통역 중") }
                log("$tag 세션 준비 완료", lane)
            }
            is ServerEvent.InputTranscript ->
                updateLane(lane) { it.copy(source = (it.source + event.text).takeLast(TEXT_KEEP)) }
            is ServerEvent.OutputTranscript ->
                updateLane(lane) { it.copy(translated = (it.translated + event.text).takeLast(TEXT_KEEP)) }
            is ServerEvent.Audio -> {
                val t = now()
                lane.speechStartedAt?.let { started ->
                    lane.speechStartedAt = null
                    val latency = t - started
                    updateLane(lane) {
                        it.copy(lastLatencyMs = latency, latencies = (it.latencies + latency).takeLast(LATENCY_KEEP))
                    }
                }
                val durationMs = event.pcm.size / 2 * 1000L / OUTPUT_SAMPLE_RATE
                lane.playingUntil = maxOf(t, lane.playingUntil) + durationMs
                lane.player.play(event.pcm)
            }
            ServerEvent.TurnComplete ->
                updateLane(lane) { if (it.translated.endsWith("\n")) it else it.copy(translated = it.translated + "\n") }
            ServerEvent.Interrupted -> {
                lane.player.clear()
                lane.playingUntil = 0
            }
            is ServerEvent.GoAway -> log("$tag GoAway (남은 시간 ${event.timeLeft})", lane)
            is ServerEvent.Resumption -> {
                if (event.newHandle != null) lane.handle = event.newHandle
                if (lane.resumable != event.resumable || _state.value.lanes.getOrNull(lane.index)?.resumable == null) {
                    log("$tag 세션 재개 핸들 수신 (resumable=${event.resumable})", lane)
                }
                lane.resumable = event.resumable
                updateLane(lane) { it.copy(resumable = event.resumable) }
            }
            is ServerEvent.Other -> if (lane.seenOther.add(event.keys)) log("$tag 기타 메시지(처음 1회): ${event.keys}", lane)
        }
    }

    /** Applies [change] only if [lane]'s run still owns the state; the check and write are one CAS. */
    private fun updateLane(lane: Lane, change: (LaneState) -> LaneState) {
        _state.update { s ->
            if (s.run != lane.runId || lane.index >= s.lanes.size) s
            else s.copy(lanes = s.lanes.toMutableList().also { it[lane.index] = change(it[lane.index]) })
        }
    }

    /** Appends a log line; when [lane] is given, only while its run still owns the state. */
    private fun log(message: String, lane: Lane? = null) {
        val seconds = now() / 100 / 10.0
        _state.update { s ->
            if (lane != null && s.run != lane.runId) s
            else s.copy(log = (s.log + "${seconds}s $message").takeLast(LOG_KEEP))
        }
    }

    private companion object {
        const val SPEECH_RMS = 800
        const val SILENCE_GAP_MS = 600L
        const val PLAYBACK_TAIL_MS = 300L
        const val MAX_FAILURES = 5
        const val TEXT_KEEP = 600
        const val LATENCY_KEEP = 50
        const val LOG_KEEP = 60

        fun onOff(value: Boolean) = if (value) "켬" else "끔"

        fun rms(pcm: ByteArray): Int {
            var sum = 0.0
            val samples = pcm.size / 2
            for (i in 0 until samples) {
                val s = ((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xFF)).toShort().toDouble()
                sum += s * s
            }
            return if (samples == 0) 0 else sqrt(sum / samples).toInt()
        }
    }
}
