package com.kys.ktransfer.live

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add
import kotlin.io.encoding.Base64

const val LIVE_TRANSLATE_MODEL = "gemini-3.5-live-translate-preview"
const val LIVE_ENDPOINT =
    "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

/** Input: 16 kHz mono PCM16 LE. Output: 24 kHz mono PCM16 LE (Live Translate guide). */
const val INPUT_SAMPLE_RATE = 16_000
const val OUTPUT_SAMPLE_RATE = 24_000
const val CHUNK_MS = 100
const val INPUT_CHUNK_BYTES = INPUT_SAMPLE_RATE * 2 * CHUNK_MS / 1000

data class TranslateConfig(
    val targetLanguage: String,
    val echoTargetLanguage: Boolean = false,
    /** Ask the server for session resumption handles. Support on this model is unverified. */
    val requestResumption: Boolean = false,
    val resumptionHandle: String? = null,
)

sealed interface ServerEvent {
    data object SetupComplete : ServerEvent
    data class InputTranscript(val text: String, val languageCode: String?) : ServerEvent
    data class OutputTranscript(val text: String, val languageCode: String?) : ServerEvent
    class Audio(val pcm: ByteArray) : ServerEvent
    data object TurnComplete : ServerEvent
    data object Interrupted : ServerEvent
    data class GoAway(val timeLeft: String?) : ServerEvent
    data class Resumption(val newHandle: String?, val resumable: Boolean) : ServerEvent
    data class Other(val keys: Set<String>) : ServerEvent
}

/**
 * The server rejects `inputAudioTranscription`/`outputAudioTranscription` inside `generationConfig`
 * (1007 "Unknown name ... at 'setup.generation_config'", observed 2026-10-15) although the
 * Live Translate guide's WebSocket example puts them there; they belong directly under `setup`.
 */
fun buildSetupMessage(config: TranslateConfig): String = buildJsonObject {
    putJsonObject("setup") {
        put("model", "models/$LIVE_TRANSLATE_MODEL")
        putJsonObject("generationConfig") {
            putJsonArray("responseModalities") { add("AUDIO") }
            putJsonObject("translationConfig") {
                put("targetLanguageCode", config.targetLanguage)
                put("echoTargetLanguage", config.echoTargetLanguage)
            }
        }
        putJsonObject("inputAudioTranscription") {}
        putJsonObject("outputAudioTranscription") {}
        if (config.requestResumption) {
            putJsonObject("sessionResumption") {
                config.resumptionHandle?.let { put("handle", it) }
            }
        }
    }
}.toString()

fun buildAudioMessage(pcm: ByteArray): String =
    """{"realtimeInput":{"audio":{"data":"${Base64.encode(pcm)}","mimeType":"audio/pcm;rate=$INPUT_SAMPLE_RATE"}}}"""

fun parseServerMessage(raw: String): List<ServerEvent> {
    val root = Json.parseToJsonElement(raw).jsonObject
    val events = mutableListOf<ServerEvent>()
    if ("setupComplete" in root) events += ServerEvent.SetupComplete
    root.obj("serverContent")?.let { content ->
        content.obj("inputTranscription")?.let { t ->
            t.str("text")?.let { events += ServerEvent.InputTranscript(it, t.str("languageCode")) }
        }
        content.obj("outputTranscription")?.let { t ->
            t.str("text")?.let { events += ServerEvent.OutputTranscript(it, t.str("languageCode")) }
        }
        content.obj("modelTurn")?.get("parts")?.jsonArray?.forEach { part ->
            part.jsonObject.obj("inlineData")?.str("data")?.let { events += ServerEvent.Audio(Base64.decode(it)) }
        }
        if (content.bool("interrupted")) events += ServerEvent.Interrupted
        if (content.bool("turnComplete")) events += ServerEvent.TurnComplete
    }
    root.obj("goAway")?.let { events += ServerEvent.GoAway(it.str("timeLeft")) }
    root.obj("sessionResumptionUpdate")?.let {
        events += ServerEvent.Resumption(it.str("newHandle"), it.bool("resumable"))
    }
    if (events.isEmpty()) {
        events += ServerEvent.Other(
            root.entries.flatMapTo(mutableSetOf()) { (key, value) ->
                if (key == "serverContent" && value is JsonObject) value.keys.map { "serverContent.$it" } else listOf(key)
            },
        )
    }
    return events
}

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true
