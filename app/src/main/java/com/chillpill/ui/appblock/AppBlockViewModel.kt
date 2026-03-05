package com.chillpill.ui.appblock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chillpill.ChillpillApp
import com.chillpill.data.usage.UsageEventType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.first
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
    val className: String?,
    val isReIntervention: Boolean
) : ViewModel() {

    private val _phase = MutableStateFlow(AppBlockPhase.WAITING)
    val phase: StateFlow<AppBlockPhase> = _phase.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _openCount24h = MutableStateFlow(0)
    val openCount24h: StateFlow<Int> = _openCount24h.asStateFlow()

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

    fun onContinue() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                app.usageEventsRepository.recordEvent(
                    packageName = packageName,
                    eventType = UsageEventType.WAIT_COMPLETED,
                    sessionStartTime = System.currentTimeMillis()
                )
            }
            _events.send(AppBlockEvent.RequestFinish)
        }
    }

    fun onGoHome() {
        viewModelScope.launch {
            _events.send(AppBlockEvent.RequestGoHome)
        }
    }
}
