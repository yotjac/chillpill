package com.chillpill.ui.appblock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chillpill.ChillpillApp
import com.chillpill.data.restricted.AppPolicy
import com.chillpill.data.settings.BlockBackground
import com.chillpill.service.engine.Mode
import com.chillpill.service.engine.CloseReason
import com.chillpill.service.engine.EngineCore
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
    val isReIntervention: Boolean,
    /**
     * Link picker (specs/link-pass.md): no wait, the picker goes up to the app's link limit
     * (no "need longer?"), and Continue starts a link visit.
     */
    val isLink: Boolean = false,
    /** Link picker maximum chosen by the engine (0 = unknown: use the app's setting). */
    private val linkMaxMinutes: Int = 0,
    /** Link picker capped to 2 min because a link was opened in the last 15 min (L3). */
    val linkLimited: Boolean = false
) : ViewModel() {

    private val _phase = MutableStateFlow(AppBlockPhase.WAITING)
    val phase: StateFlow<AppBlockPhase> = _phase.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    /**
     * PICK app: after the wait the screen shows the duration picker instead of Continue
     * (specs/per-app-modes.md). Known once the policy has been read.
     */
    private val _isPick = MutableStateFlow(false)
    val isPick: StateFlow<Boolean> = _isPick.asStateFlow()

    /** The wait is 0 s (PICK only): show the completed content without the fill animation. */
    private val _instant = MutableStateFlow(false)
    val instant: StateFlow<Boolean> = _instant.asStateFlow()

    /**
     * Picker value in minutes; kept here so it survives rotation. Null until the user picks: a
     * PICK app starts with nothing chosen and cannot be opened before (specs/deliberate-choices.md).
     * The link picker starts at a value (set in [runWaitTimer]).
     */
    private val _pickMinutes = MutableStateFlow<Int?>(null)
    val pickMinutes: StateFlow<Int?> = _pickMinutes.asStateFlow()

    /** Picker range: 1–30 min, or 1–90 after "need longer?". Resets with every new block screen. */
    private val _pickExtended = MutableStateFlow(false)
    val pickExtended: StateFlow<Boolean> = _pickExtended.asStateFlow()

    /** Link picker: the app's link limit in minutes (the picker's whole range); null otherwise. */
    private val _pickLimit = MutableStateFlow<Int?>(null)
    val pickLimit: StateFlow<Int?> = _pickLimit.asStateFlow()

    /**
     * Open attempts in the last 24 h, shown on the block screen. Read when the screen opens (the
     * text is revealed by the wait's fill, so it must be known from the start) and once more when
     * the wait ends; null until the first read.
     */
    private val _openCount24h = MutableStateFlow<Int?>(null)
    val openCount24h: StateFlow<Int?> = _openCount24h.asStateFlow()

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

    private suspend fun loadOpenCount() {
        _openCount24h.value = withContext(Dispatchers.IO) {
            app.usageEventsRepository.getOpenInterceptCountLast24h(packageName)
        }
    }

    private suspend fun runWaitTimer() {
        if (packageName.isBlank()) return
        viewModelScope.launch { loadOpenCount() }
        val policy = withContext(Dispatchers.IO) {
            app.restrictions.first().policyFor(packageName)
        }
        if (isLink) {
            val limit = linkMaxMinutes.takeIf { it > 0 } ?: policy.linkPassMinutes
            _pickLimit.value = limit
            _pickMinutes.value = minOf(PICK_START_MINUTES, limit)
        }
        _isPick.value = isLink || policy.mode == Mode.PICK
        // Opening wait, or the "time's up" wait on a re-intervention. Only PICK may wait 0 s; a
        // link picker never waits.
        val waitTimeSeconds = if (isLink) {
            0
        } else {
            policy.waitSeconds(isReIntervention).coerceAtLeast(AppPolicy.minOpenWaitSeconds(policy.mode))
        }
        _instant.value = waitTimeSeconds == 0
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
        // Again: this screen's own OPEN_ATTEMPT may not have been written yet at the first read.
        loadOpenCount()
    }

    /**
     * Identity of this block screen for the session engine. Survives activity recreation (the
     * view model does), so a rotated screen is still the same block; a new block is a new id.
     */
    val instance: Long = android.os.SystemClock.elapsedRealtimeNanos()

    fun onPickMinutesChanged(minutes: Int) {
        _pickMinutes.value = minutes.coerceIn(1, pickMaxMinutes())
    }

    fun onPickRangeToggled() {
        _pickExtended.value = !_pickExtended.value
        _pickMinutes.value = _pickMinutes.value?.coerceAtMost(pickMaxMinutes())
    }

    private fun pickMaxMinutes() = _pickLimit.value
        ?: if (_pickExtended.value) PICK_EXTENDED_MAX_MINUTES else PICK_MAX_MINUTES

    /**
     * Continue: the engine starts the grace period and records WAIT_COMPLETED. For a PICK app or
     * a link picker it gets the picked length (the engine clamps it again, so a stale policy here
     * is harmless).
     */
    fun onContinue() {
        val picked = _pickMinutes.value
        // The button is disabled until a length is picked; ignore a stray tap all the same.
        if (_isPick.value && picked == null) return
        val chosenMs = if (_isPick.value) picked!! * 60_000L else null
        app.sessionEngine.send(Input.ContinueTapped(packageName, SessionEngine.now(), chosenMs))
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

    companion object {
        const val PICK_START_MINUTES = 5
        const val PICK_MAX_MINUTES = 30
        /** The engine's own limit (it clamps whatever the picker sends). */
        val PICK_EXTENDED_MAX_MINUTES = (EngineCore.PICK_MAX_MS / 60_000L).toInt()
        val PICK_QUICK_MINUTES = listOf(5, 10, 15, 20, 25, 30)
        val PICK_EXTENDED_QUICK_MINUTES = listOf(15, 30, 45, 60, 75, 90)
        private val LINK_QUICK_CANDIDATES = listOf(1, 2, 3, 5, 10, 15, 20, 30, 45, 60, 75, 90)

        /** Quick chips of a link picker limited to [limit] minutes: up to six, ending at the limit. */
        fun linkQuickMinutes(limit: Int): List<Int> =
            (LINK_QUICK_CANDIDATES.filter { it < limit } + limit).takeLast(6)
    }
}
