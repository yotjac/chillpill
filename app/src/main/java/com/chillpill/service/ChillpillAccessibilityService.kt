package com.chillpill.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import com.chillpill.ChillpillApp
import com.chillpill.service.engine.Input
import com.chillpill.service.engine.SessionEngine
import com.chillpill.service.engine.SuggestionAnswer
import com.chillpill.service.engine.SuggestionUi
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Adapter between Android and the session engine (specs/session-engine.md). Holds no pipeline
 * state of its own:
 *
 * - **In:** every TYPE_WINDOW_STATE_CHANGED event, classified and timestamped, and the screen
 *   off / on / user-present broadcasts, are sent to [SessionEngine].
 * - **Out:** renders the engine's `warning` and `suggestion` as TYPE_ACCESSIBILITY_OVERLAY windows,
 *   which only an accessibility service may add. Their buttons send inputs back.
 *
 * Blocking decisions, grace timers and presence all live in [com.chillpill.service.engine.EngineCore].
 */
class ChillpillAccessibilityService : AccessibilityService() {

    private val engine: SessionEngine
        get() = (applicationContext as ChillpillApp).sessionEngine

    /** Overlay windows must be added / removed on the main thread. */
    private val uiScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, t -> Log.e(TAG, "uncaught exception in overlay rendering", t) }
    )
    private var renderJobs: List<Job> = emptyList()

    private val suggestionOverlay: SuggestionOverlayManager by lazy { SuggestionOverlayManager(this) }
    private val warningOverlay: GraceWarningOverlayManager by lazy { GraceWarningOverlayManager(this) }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val now = SessionEngine.now()
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> engine.send(Input.ScreenOff(now))
                Intent.ACTION_SCREEN_ON -> {
                    val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                    engine.send(Input.ScreenOn(keyguardLocked = keyguard?.isKeyguardLocked ?: true, at = now))
                }
                Intent.ACTION_USER_PRESENT -> engine.send(Input.UserPresent(now))
            }
        }
    }
    private var screenReceiverRegistered = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            packageNames = null // all packages; the engine decides what matters
        }
        if (!screenReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                screenReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_USER_PRESENT)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            screenReceiverRegistered = true
        }
        engine.start()
        startRendering()
    }

    private fun startRendering() {
        if (renderJobs.any { it.isActive }) return
        renderJobs = listOf(
            uiScope.launch {
                engine.warning.collect { warning ->
                    warningOverlay.render(
                        warning,
                        onExtend = { pkg -> engine.send(Input.ExtendTapped(pkg, SessionEngine.now())) },
                        onDismiss = { pkg -> engine.send(Input.WarningDismissed(pkg, SessionEngine.now())) }
                    )
                }
            },
            uiScope.launch {
                engine.suggestion.collect { suggestion -> renderSuggestion(suggestion) }
            }
        )
    }

    private suspend fun renderSuggestion(suggestion: SuggestionUi?) {
        if (suggestion == null) {
            suggestionOverlay.dismiss()
            return
        }
        fun answer(answer: SuggestionAnswer) =
            engine.send(Input.SuggestionAnswered(suggestion.pkg, answer, SessionEngine.now()))
        suggestionOverlay.show(
            packageName = suggestion.pkg,
            appName = suggestion.appName,
            onRestrict = { answer(SuggestionAnswer.RESTRICT) },
            onIgnore = { answer(SuggestionAnswer.IGNORE) },
            onNeverAskAgain = { answer(SuggestionAnswer.NEVER) }
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val className = event.className?.toString()
        val kind = engine.classifier.classify(pkg, className)
        engine.send(Input.Window(pkg, className, kind, SessionEngine.elapsedFromUptime(event.eventTime)))
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (screenReceiverRegistered) {
            try {
                unregisterReceiver(screenReceiver)
            } catch (t: Throwable) {
                Log.e(TAG, "unregister screenReceiver failed", t)
            }
            screenReceiverRegistered = false
        }
        uiScope.cancel()
        renderJobs = emptyList()
        warningOverlay.dismissFromMainThread()
        suggestionOverlay.dismissFromMainThread()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "ChillpillA11y"
    }
}
