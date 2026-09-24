package com.chillpill.ui.appblock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chillpill.ChillpillApp
import com.chillpill.data.settings.BlockBackground
import com.chillpill.service.engine.CloseReason
import com.chillpill.service.engine.Input
import com.chillpill.service.engine.SessionEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AppBlockPhase { WAITING, COMPLETED }

sealed class AppBlockEvent {
    data object RequestFinish : AppBlockEvent()
    data object RequestGoHome : AppBlockEvent()
}

class AppBlockViewModel(
    private val app: ChillpillApp,
    val packageName: String,
    val isReIntervention: Boolean
) : ViewModel() {

    private val _phase = MutableStateFlow(AppBlockPhase.WAITING)
    val phase: StateFlow<AppBlockPhase> = _phase.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _openCount24h = MutableStateFlow(0)
    val openCount24h: StateFlow<Int> = _openCount24h.asStateFlow()

    /**
     * Background image for the block screen. Null until the setting has been read, so the screen
     * shows a plain surface for that moment rather than flashing the default image.
     */
    val blockBackground: StateFlow<BlockBackground?> = app.settingsRepository.settings
        .map { it.blockBackground }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _events = Channel<AppBlockEvent>(Channel.BUFFERED)
    val events: kotlinx.coroutines.flow.Flow<AppBlockEvent> = _events.receiveAsFlow()

    init {
        viewModelScope.launch { runWaitTimer() }
    }

    private suspend fun runWaitTimer() {
        if (packageName.isBlank()) return
        val settings = withContext(Dispatchers.IO) {
            app.settingsRepository.settings.first()
        }
        val waitTimeSeconds = settings.waitTimeSeconds.coerceAtLeast(1)
        val tickMs = 100L
        val totalMs = waitTimeSeconds * 1000L
        var elapsedMs = 0L
        while (elapsedMs < totalMs) {
            kotlinx.coroutines.delay(tickMs)
            elapsedMs += tickMs
            _progress.value = (elapsedMs.toFloat() / totalMs).coerceIn(0f, 1f)
        }
        _progress.value = 1f
        _phase.value = AppBlockPhase.COMPLETED
        val count = withContext(Dispatchers.IO) {
            app.usageEventsRepository.getOpenInterceptCountLast24h(packageName)
        }
        _openCount24h.value = count
    }

    /**
     * Identity of this block screen for the session engine. Survives activity recreation (the
     * view model does), so a rotated screen is still the same block; a new block is a new id.
     */
    val instance: Long = android.os.SystemClock.elapsedRealtimeNanos()

    /** Continue: the engine starts the grace period and records WAIT_COMPLETED. */
    fun onContinue() {
        app.sessionEngine.send(Input.ContinueTapped(packageName, SessionEngine.now()))
        viewModelScope.launch { _events.send(AppBlockEvent.RequestFinish) }
    }

    /** Go Home / Back: the engine ends the block and records LEFT_APP exactly once. */
    fun onGoHome(reason: CloseReason = CloseReason.GO_HOME) {
        onClosed(reason)
        viewModelScope.launch { _events.send(AppBlockEvent.RequestGoHome) }
    }

    fun onStarted() = app.sessionEngine.send(Input.BlockStarted(packageName, instance, SessionEngine.now()))

    fun onStopped() = app.sessionEngine.send(Input.BlockStopped(packageName, instance, SessionEngine.now()))

    /**
     * The screen is going away without Continue (system gesture, recents, another block replacing
     * it). Safe to call more than once and after Continue: the engine only acts while this very
     * screen is the app's block.
     */
    fun onClosed(reason: CloseReason) =
        app.sessionEngine.send(Input.BlockClosed(packageName, reason, instance, SessionEngine.now()))
}
