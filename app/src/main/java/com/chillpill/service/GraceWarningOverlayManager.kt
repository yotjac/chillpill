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
import com.chillpill.service.engine.WarningUi
import com.chillpill.ui.gracewarning.GraceWarningPill
import com.chillpill.ui.theme.ChillpillTheme
import kotlinx.coroutines.CancellationException

/**
 * Owns the grace-expiry warning pill: a small [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY]
 * window at the top of the screen showing the last seconds of a grace period plus one "+10 s"
 * (see specs/grace-expiry-warning.md).
 *
 * What to show is decided entirely by the session engine (`SessionEngine.warning`, see
 * specs/session-engine.md); this class only owns the window.
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
    private val warningState = mutableStateOf<WarningUi?>(null)

    private var onExtend: (String) -> Unit = {}
    private var onDismiss: (String) -> Unit = {}

    private val isShowing: Boolean
        get() = overlayView != null

    /**
     * Single entry point, main thread only: shows, updates or takes down the pill to match
     * [warning], which is the session engine's derived state (already gated on the app in front,
     * re-intervention on, dismissal and extension). No decisions are made here.
     */
    fun render(
        warning: WarningUi?,
        onExtend: (String) -> Unit,
        onDismiss: (String) -> Unit
    ) {
        this.onExtend = onExtend
        this.onDismiss = onDismiss
        try {
            if (warning == null) {
                dismissInternal()
                return
            }
            warningState.value = warning
            if (isShowing) {
                Log.d(tag, "update: pkg=${warning.pkg} canExtend=${warning.canExtend} dismissible=${warning.dismissible}")
                return
            }
            showInternal(warning)
        } catch (t: Throwable) {
            Log.e(tag, "render failed for warning=$warning", t)
        }
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

    private fun showInternal(warning: WarningUi) {
        val overlayOwner = OverlayComposeOwner().apply { start() }
        try {
            val themedContext = ContextThemeWrapper(service, R.style.Theme_Chillpill)
            val view = GraceWarningView(themedContext) {
                // Read through .value (not `by`): a delegated local cannot be smart-cast.
                val current = warningState.value
                if (current != null) {
                    ChillpillTheme(applyWindowDecor = false) {
                        GraceWarningPill(
                            deadlineMs = current.deadline,
                            canExtend = current.canExtend,
                            dismissible = current.dismissible,
                            extensionConfirmed = current.extensionConfirmed,
                            onExtend = { onExtend(current.pkg) },
                            // The engine answers with a new warning (null, or the final countdown).
                            onDismiss = { onDismiss(current.pkg) }
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
            Log.d(tag, "show: pill added for pkg=${warning.pkg} canExtend=${warning.canExtend}")
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            overlayOwner.destroy()
            Log.e(tag, "show failed for pkg=${warning.pkg}", t)
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
