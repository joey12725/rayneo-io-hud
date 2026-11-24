package io.lampyr.glasses.glasses

import kotlinx.coroutines.flow.*

class PreviewTransport : GlassesTransport {
    private val state = MutableStateFlow(ConnectionState.CONNECTED)
    override val connectionState = state.asStateFlow()
    private val latest = MutableStateFlow<Frame?>(null)
    val frames = latest.asStateFlow()
    var masterFrame: Frame? = null
        private set
    fun rememberMaster(frame: Frame) { masterFrame = frame }
    suspend fun showSent(frame: Frame) { latest.value = frame }

    override suspend fun show(frame: Frame) {
        rememberMaster(frame)
        latest.value = frame
    }

    override suspend fun connect() {
        state.value = ConnectionState.CONNECTED
    }

    override suspend fun disconnect() {
        state.value = ConnectionState.DISCONNECTED
    }
}
