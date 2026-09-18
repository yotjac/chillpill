package com.chillpill.service

import android.app.Service
import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.chillpill.R
import com.chillpill.ui.gracewarning.GraceWarningPill
import com.chillpill.ui.theme.ChillpillTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Owns the grace-expiry warning pill: a small [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY]
 * window at the top of the screen showing the last seconds of a grace period plus one "+10 s"
 * (see specs/grace-expiry-warning.md).
 *
 * Deliberately *not* part of [SuggestionOverlayManager]: `processEvent` skips all event handling
 * while that manager's `isShowing` is true, which would stop other restricted apps from being
 * blocked for as long as a pill is up (I2). This manager's own showing state is private and is
 * never consulted by the event pipeline.
 *
 * The window is non-focusable and only touch-modal inside its own bounds, so the app underneath
 * keeps running, keeps its keyboard and immersive mode, and stays touchable everywhere else.
 */
class GraceWarningOverlayManager(
    private val service: Service
) {
    private val tag = "GraceWarningOverlay"

    private val windowManager: WindowManager =
        service.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var overlayView: GraceWarningView? = null
    private var overlayComposeOwner: OverlayComposeOwner? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    /** Drives the pill's content without removing and re-adding the window (no flicker on canExtend). */
    private val warningState = mutableStateOf<BlockingSharedState.GraceWarning?>(null)

    /**
     * Which warning the user tapped away; it must not reappear until the warning itself changes —
     * which it does at the final-seconds mark, where the pill stops being dismissible.
     */
    private var dismissedFor: BlockingSharedState.GraceWarning? = null

    /** Private on purpose — see the class comment (I2). */
    private val isShowing: Boolean
        get() = overlayView != null

    /**
     * Single entry point: shows, updates or dismisses the pill to match [warning].
     * Pass null (or a warning for another package) to take it down.
     */
    suspend fun applyWarning(
        warning: BlockingSharedState.GraceWarning?,
        onExtend: (String) -> Unit
    ) {
        withContext(Dispatchers.Main.immediate) {
            try {
                if (warning == null) {
                    dismissedFor = null
                    dismissInternal()
                    return@withContext
                }
                if (warning == dismissedFor && warning.dismissible) {
                    dismissInternal()
                    return@withContext
                }
                warningState.value = warning
                if (isShowing) {
                    Log.d(tag, "update: pkg=${warning.packageName} canExtend=${warning.canExtend} dismissible=${warning.dismissible}")
                    return@withContext
                }
                showInternal(warning, onExtend)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.e(tag, "apply failed for warning=$warning", t)
            }
        }
    }

    /** Takes the pill down unless it belongs to [packageName] (the user is still in that app). */
    suspend fun dismissIfNot(packageName: String?) {
        withContext(Dispatchers.Main.immediate) {
            if (warningState.value?.packageName != packageName) dismissSafely()
        }
    }

    suspend fun dismiss() {
        withContext(Dispatchers.Main.immediate) { dismissSafely() }
    }

    /** For callers already on the main thread that cannot suspend (e.g. `Service.onDestroy`). */
    fun dismissFromMainThread() {
        dismissSafely()
    }

    private fun dismissSafely() {
        try {
            dismissInternal()
        } catch (t: Throwable) {
            Log.e(tag, "dismiss failed", t)
        }
    }

    private fun showInternal(
        warning: BlockingSharedState.GraceWarning,
        onExtend: (String) -> Unit
    ) {
        val overlayOwner = OverlayComposeOwner().apply { start() }
        try {
            val themedContext = ContextThemeWrapper(service, R.style.Theme_Chillpill)
            val view = GraceWarningView(themedContext) {
                // Read through .value (not `by`): a delegated local cannot be smart-cast.
                val current = warningState.value
                if (current != null) {
                    ChillpillTheme(applyWindowDecor = false) {
                        GraceWarningPill(
                            deadlineWallMs = current.deadlineWallMs,
                            canExtend = current.canExtend,
                            dismissible = current.dismissible,
                            onExtend = { onExtend(current.packageName) },
                            onDismiss = {
                                // Compose click callbacks arrive on the main thread.
                                dismissedFor = warningState.value
                                dismissSafely()
                            }
                        )
                    }
                }
            }.apply {
                // Must be set before the view is attached — otherwise Compose crashes (no Activity).
                setViewTreeLifecycleOwner(overlayOwner)
                setViewTreeSavedStateRegistryOwner(overlayOwner)
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                // Not focusable: the app below keeps its focus, its keyboard and its immersive mode.
                // Not touch-modal: only touches inside the pill are intercepted.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(FALLBACK_STATUS_BAR_DP + PILL_MARGIN_DP)
            }

            // The real status-bar inset is only known once the window is attached; until then the
            // fallback above keeps the pill clear of the status bar on ordinary screens.
            view.setOnApplyWindowInsetsListener { v, insets ->
                try {
                    val top = WindowInsetsCompat.toWindowInsetsCompat(insets, v)
                        .getInsets(WindowInsetsCompat.Type.statusBars()).top
                    val desired = top + dp(PILL_MARGIN_DP)
                    val current = layoutParams
                    if (top > 0 && current != null && current.y != desired) {
                        current.y = desired
                        windowManager.updateViewLayout(v, current)
                    }
                } catch (t: Throwable) {
                    Log.w(tag, "applying status bar inset failed; keeping fallback offset", t)
                }
                insets
            }

            windowManager.addView(view, params)
            overlayView = view
            overlayComposeOwner = overlayOwner
            layoutParams = params
            Log.d(tag, "show: pill added for pkg=${warning.packageName} canExtend=${warning.canExtend}")
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            overlayOwner.destroy()
            Log.e(tag, "show failed for pkg=${warning.packageName}", t)
            try {
                dismissInternal()
            } catch (cleanupError: Throwable) {
                Log.e(tag, "show: cleanup dismissInternal also failed", cleanupError)
            }
        }
    }

    private fun dismissInternal() {
        if (overlayView != null) Log.d(tag, "dismiss: taking the pill down")
        warningState.value = null
        layoutParams = null
        if (overlayView == null && overlayComposeOwner == null) return
        try {
            overlayView?.let { view ->
                windowManager.removeViewImmediate(view)
                view.disposeComposition()
            }
        } catch (t: Throwable) {
            Log.w(tag, "dismissInternal: removeView/dispose failed (pill may already be gone)", t)
        } finally {
            overlayView = null
            overlayComposeOwner?.destroy()
            overlayComposeOwner = null
        }
    }

    private fun dp(value: Int): Int =
        (value * service.resources.displayMetrics.density).toInt()

    /**
     * Minimal owners for a Compose view in a [WindowManager] overlay (no Activity). Deliberately a
     * private twin of the one in [SuggestionOverlayManager]: the two overlays are independent, and
     * this feature must not touch the suggestion path.
     */
    private class OverlayComposeOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateController = SavedStateRegistryController.create(this)

        override val lifecycle: Lifecycle
            get() = lifecycleRegistry

        override val savedStateRegistry: SavedStateRegistry
            get() = savedStateController.savedStateRegistry

        fun start() {
            savedStateController.performAttach()
            savedStateController.performRestore(null)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        fun destroy() {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
    }

    private companion object {
        const val FALLBACK_STATUS_BAR_DP = 24
        const val PILL_MARGIN_DP = 8
    }
}
