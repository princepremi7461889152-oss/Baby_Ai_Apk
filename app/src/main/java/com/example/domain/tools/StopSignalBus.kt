package com.example.domain.tools

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StopSignalBus @Inject constructor() {
    private val _stopEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val stopEvents: SharedFlow<String> = _stopEvents.asSharedFlow()

    private val _voiceTriggerEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val voiceTriggerEvents: SharedFlow<String> = _voiceTriggerEvents.asSharedFlow()

    private val stopGeneration = AtomicLong(0L)

    fun currentGeneration(): Long = stopGeneration.get()

    fun isStoppedSince(generation: Long): Boolean = stopGeneration.get() != generation

    fun requestStop(reason: String) {
        stopGeneration.incrementAndGet()
        _stopEvents.tryEmit(reason)
    }

    fun triggerVoiceCapture(source: String) {
        _voiceTriggerEvents.tryEmit(source)
    }
}
