package com.kys.ktransfer

/** Microphone delivering 100 ms chunks of 16 kHz mono PCM16 LE on a background thread. */
interface MicSource {
    fun start(onChunk: (ByteArray) -> Unit)
    fun stop()
}

/** Streaming player for 24 kHz mono PCM16 LE. */
interface PcmPlayer {
    fun play(pcm: ByteArray)
    /** Drops audio that has not been played yet. */
    fun clear()
    fun release()
}

interface AudioDevice {
    /** [echoCancel] selects the platform's voice-communication path (hardware AEC) where available. */
    fun openMic(echoCancel: Boolean): MicSource
    fun openPlayer(echoCancel: Boolean): PcmPlayer
}

interface SettingsStore {
    fun load(key: String): String?
    fun save(key: String, value: String)
}

class Platform(val audio: AudioDevice, val settings: SettingsStore)
