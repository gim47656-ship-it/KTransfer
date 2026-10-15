package com.kys.ktransfer.live

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One Live Translate connection. Audio sent before setup completes waits in a short queue;
 * older chunks are dropped so a reconnect never replays more than [QUEUE_CHUNKS] of stale audio.
 *
 * The API key travels in the URL query (as in the official WebSocket example), so every error
 * message and close reason leaving this class is redacted.
 */
class LiveSession(private val http: HttpClient, private val apiKey: String) {
    private val audioQueue = Channel<String>(capacity = QUEUE_CHUNKS, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    fun sendAudio(pcm: ByteArray) {
        audioQueue.trySend(buildAudioMessage(pcm))
    }

    /**
     * Runs until the server or network closes the connection and returns the redacted close
     * description. Closes the connection itself if setup does not complete within [SETUP_TIMEOUT_MS].
     * Connection failures are rethrown as [IllegalStateException] with a redacted message.
     */
    suspend fun connect(config: TranslateConfig, onEvent: suspend (ServerEvent) -> Unit): String =
        try {
            redact(connectOnce(config, onEvent))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException(redact(e.message ?: e::class.simpleName ?: "connection error"))
        }

    private suspend fun connectOnce(config: TranslateConfig, onEvent: suspend (ServerEvent) -> Unit): String {
        var reason = "closed"
        http.webSocket(urlString = "$LIVE_ENDPOINT?key=$apiKey") {
            send(Frame.Text(buildSetupMessage(config)))
            val ready = CompletableDeferred<Unit>()
            var setupTimedOut = false
            val watchdog = launch {
                delay(SETUP_TIMEOUT_MS)
                if (!ready.isCompleted) {
                    setupTimedOut = true
                    close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "setup timeout"))
                }
            }
            val sender = launch {
                ready.await()
                for (message in audioQueue) send(Frame.Text(message))
            }
            try {
                for (frame in incoming) {
                    val text = when (frame) {
                        is Frame.Text -> frame.readText()
                        is Frame.Binary -> frame.readBytes().decodeToString()
                        else -> continue
                    }
                    for (event in parseServerMessage(text)) {
                        if (event is ServerEvent.SetupComplete) ready.complete(Unit)
                        onEvent(event)
                    }
                }
            } finally {
                watchdog.cancel()
                sender.cancel()
            }
            reason = if (setupTimedOut) {
                "setup timeout"
            } else {
                closeReason.await()?.let { "${it.code} ${it.message}" } ?: "closed"
            }
        }
        return reason
    }

    private fun redact(text: String): String = if (apiKey.isEmpty()) text else text.replace(apiKey, "***")

    private companion object {
        const val QUEUE_CHUNKS = 10
        const val SETUP_TIMEOUT_MS = 15_000L
    }
}
