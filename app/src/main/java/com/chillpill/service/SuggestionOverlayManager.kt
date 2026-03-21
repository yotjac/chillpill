package com.chillpill.service

import android.app.Service
import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.chillpill.R
import com.chillpill.ui.suggestion.SuggestRestrictionScreen
import com.chillpill.ui.theme.ChillpillTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Hosts the suggestion UI as a system accessibility overlay so it can't be displaced by apps
 * rapidly changing activities/windows (e.g. Waze).
 */
class SuggestionOverlayManager(
    private val service: Service
) {
    private val tag = "SuggestionOverlay"

    private val windowManager: WindowManager =
        service.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var overlayView: ComposeView? = null

    /** Lifecycle + saved state owners required by Compose when the view is not under an Activity. */
    private var overlayComposeOwner: OverlayComposeOwner? = null

    val isShowing: Boolean
        get() = overlayView != null

    suspend fun show(
        packageName: String,
        appName: String,
        onRestrict: () -> Unit,
        onIgnore: () -> Unit,
        onNeverAskAgain: () -> Unit,
    ) {
        withContext(Dispatchers.Main.immediate) {
            try {
                dismissInternal()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.e(tag, "dismissInternal before show failed (pkg=$packageName)", t)
            }
            val overlayOwner = OverlayComposeOwner().apply { start() }
            try {
                Log.d(tag, "show: begin pkg=$packageName appName=$appName")
                val themedContext = ContextThemeWrapper(service, R.style.Theme_Chillpill)
                val composeView = ComposeView(themedContext).apply {
                    // Must be set before setContent / attach — otherwise Compose crashes (no Activity).
                    // Use KTX extensions on View (lifecycle-runtime-ktx / savedstate-ktx), not ViewTree*.set.
                    setViewTreeLifecycleOwner(overlayOwner)
                    setViewTreeSavedStateRegistryOwner(overlayOwner)
                    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                    setContent {
                        ChillpillTheme(applyWindowDecor = false) {
                            SuggestRestrictionScreen(
                                appName = appName,
                                packageName = packageName,
                                onRestrict = onRestrict,
                                onIgnore = onIgnore,
                                onNeverAskAgain = onNeverAskAgain
                            )
                        }
                    }
                }

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT
                )
                windowManager.addView(composeView, params)

                overlayView = composeView
                overlayComposeOwner = overlayOwner
                Log.d(tag, "show: addView succeeded pkg=$packageName")
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                overlayOwner.destroy()
                Log.e(
                    tag,
                    "show failed: ComposeView/setContent/addView (pkg=$packageName, appName=$appName)",
                    t
                )
                try {
                    dismissInternal()
                } catch (dismissErr: Throwable) {
                    Log.e(tag, "show: cleanup dismissInternal also failed pkg=$packageName", dismissErr)
                }
            }
        }
    }

    suspend fun dismiss() {
        withContext(Dispatchers.Main.immediate) {
            try {
                dismissInternal()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.e(tag, "dismiss() failed", t)
            }
        }
    }

    private fun dismissInternal() {
        if (overlayView == null && overlayComposeOwner == null) return

        try {
            overlayView?.let { view ->
                windowManager.removeViewImmediate(view)
                view.disposeComposition()
            }
        } catch (t: Throwable) {
            Log.w(tag, "dismissInternal: removeView/dispose failed (overlay may already be gone)", t)
        } finally {
            overlayView = null
            overlayComposeOwner?.destroy()
            overlayComposeOwner = null
        }
    }

    /**
     * Minimal owners for [ComposeView] in a [WindowManager] overlay (no Activity).
     * Compose requires both [LifecycleOwner] and [SavedStateRegistryOwner] on the view tree.
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
}

