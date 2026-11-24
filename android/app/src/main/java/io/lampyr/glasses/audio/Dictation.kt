package io.lampyr.glasses.audio

import io.lampyr.glasses.data.DataStore
import kotlinx.coroutines.*

sealed interface DictationEvent {
    data class Level(val value: Float) : DictationEvent

    data object Transcribing : DictationEvent

    data object Enhancing : DictationEvent

    data class Done(val text: String, val mentions: List<io.lampyr.glasses.data.Mention> = emptyList()) : DictationEvent

    data class Error(val message: String) : DictationEvent
}

interface Dictation {
    fun start(target: String, onEvent: (DictationEvent) -> Unit)

    /** [context] tells the gateway where the text goes (Beeper chatId; T3 machine/thread/project) for cleanup. */
    fun start(target: String, context: Map<String, String>, onEvent: (DictationEvent) -> Unit) = start(target, onEvent)

    fun stop()

    fun cancel()
}

/** Simulator timing; a real implementation consumes glasses channel 1 and gateway NDJSON. */
class FakeDictation(private val data: DataStore, private val scope: CoroutineScope) : Dictation {
    private var target = ""
    private var listener: ((DictationEvent) -> Unit)? = null
    private var job: Job? = null

    override fun start(target: String, onEvent: (DictationEvent) -> Unit) {
        cancel()
        this.target = target
        listener = onEvent
    }

    override fun stop() {
        val emit = listener ?: return
        val target = this.target
        job =
            scope.launch {
                if (target != "joy") {
                    emit(DictationEvent.Transcribing)
                    delay(700)
                    if(target != "raw") {
                        emit(DictationEvent.Enhancing)
                        delay(700)
                    }
                }
                emit(DictationEvent.Done(data.dictationSample(target)))
                listener = null
            }
    }

    override fun cancel() {
        job?.cancel()
        job = null
        listener = null
    }
}
