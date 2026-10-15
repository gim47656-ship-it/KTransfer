package com.kys.ktransfer.live

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LiveProtocolTest {
    @Test
    fun serverContentYieldsTranscriptsAudioAndTurnEndInOrder() {
        val raw = """
            {"serverContent":{
              "inputTranscription":{"text":"안녕하세요","languageCode":"ko"},
              "outputTranscription":{"text":"Hello","languageCode":"en"},
              "modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"AAEC/w=="}}]},
              "turnComplete":true}}
        """.trimIndent()

        val events = parseServerMessage(raw)

        assertEquals(ServerEvent.InputTranscript("안녕하세요", "ko"), events[0])
        assertEquals(ServerEvent.OutputTranscript("Hello", "en"), events[1])
        assertContentEquals(byteArrayOf(0, 1, 2, -1), assertIs<ServerEvent.Audio>(events[2]).pcm)
        assertEquals(ServerEvent.TurnComplete, events[3])
        assertEquals(4, events.size)
    }

    @Test
    fun sessionControlMessagesAreRecognised() {
        assertEquals(listOf(ServerEvent.SetupComplete), parseServerMessage("""{"setupComplete":{}}"""))
        assertEquals(listOf(ServerEvent.GoAway("50s")), parseServerMessage("""{"goAway":{"timeLeft":"50s"}}"""))
        assertEquals(
            listOf(ServerEvent.Resumption("h1", true)),
            parseServerMessage("""{"sessionResumptionUpdate":{"newHandle":"h1","resumable":true}}"""),
        )
    }

    @Test
    fun unknownMessageIsReportedNotDropped() {
        assertEquals(listOf(ServerEvent.Other(setOf("usageMetadata"))), parseServerMessage("""{"usageMetadata":{"totalTokenCount":5}}"""))
    }

    @Test
    fun transcriptionConfigSitsUnderSetupNotGenerationConfig() {
        // The live server closes with 1007 when these fields are inside generationConfig,
        // even though the Live Translate guide's WebSocket example puts them there.
        val setup = Json.parseToJsonElement(buildSetupMessage(TranslateConfig("en"))).jsonObject.getValue("setup").jsonObject
        val generation = setup.getValue("generationConfig").jsonObject

        assertTrue("inputAudioTranscription" in setup && "outputAudioTranscription" in setup)
        assertFalse("inputAudioTranscription" in generation || "outputAudioTranscription" in generation)
        assertTrue("translationConfig" in generation)
    }
}
