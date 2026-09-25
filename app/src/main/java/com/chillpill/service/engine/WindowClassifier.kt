package com.chillpill.service.engine

/**
 * Decides what a window event (or a UsageStats probe, [className] null) means. Pure; the Android
 * lookups are injected.
 *
 * The rule is *positive evidence*: presence only moves for a window that is known to be an
 * activity of some app. TYPE_WINDOW_STATE_CHANGED is also sent for dialogs, bottom sheets, popups,
 * toasts and keyboards, and those float over the app in front without pausing it. Counting one of
 * them as a foreground change makes the engine believe the user left while they did not, and
 * because the app underneath never resumes again, no UsageStats probe can ever correct that
 * belief: its session then quietly ends "while away" and the next real event of the app blocks it.
 * So a class that [isActivityOf] says is *not* an activity of its package is an overlay. A probe
 * (no class) is always an activity. A package we cannot see (package visibility: it has no launcher
 * activity and is not an IME, e.g. `com.android.pixeldisplayservice`, the permission controller)
 * cannot be checked; its windows are overlays when the class is a framework class (`android.*`:
 * FrameLayout, View, Dialog, VoiceInteractionWindow... never an app's own activity) and apps
 * otherwise. Presence that is wrong that way is repaired by the next probe, since an activity does
 * get an ACTIVITY_RESUMED.
 *
 * Keyboards: apps that ship an IME (SwiftKey, Grammarly, Samsung Keyboard) also have real
 * activities, so the same rule applies to them with the safe default flipped: an unknown class of
 * an enabled-IME package is an overlay.
 */
class WindowClassifier(
    private val ownPackage: String,
    private val ownMainUiClasses: Set<String>,
    private val ownBlockUiClasses: Set<String>,
    private val imePackages: () -> Set<String>,
    /**
     * Whether [className] is an activity of [pkg]; null when [pkg] is not visible to us (package
     * visibility filtering) or the lookup failed.
     */
    private val isActivityOf: (pkg: String, className: String) -> Boolean?
) {
    fun classify(pkg: String, className: String?): WindowKind = when {
        pkg == ownPackage -> when (className) {
            in ownMainUiClasses -> WindowKind.OWN_MAIN_UI
            in ownBlockUiClasses -> WindowKind.OWN_BLOCK_UI
            // Anything else of ours is an overlay window (pill, suggestion popup). Their window
            // events do not reliably carry the root view's class, hence the inverted rule.
            else -> WindowKind.OWN_OVERLAY
        }
        pkg == SYSTEM_UI_PACKAGE -> WindowKind.SYSTEM_OVERLAY
        else -> {
            if (className.isNullOrEmpty()) {
                if (pkg in imePackages()) WindowKind.SYSTEM_OVERLAY else WindowKind.APP
            } else when (isActivityOf(pkg, className)) {
                true -> WindowKind.APP
                false -> if (pkg in imePackages()) WindowKind.SYSTEM_OVERLAY else WindowKind.APP_OVERLAY
                null -> when {
                    pkg in imePackages() -> WindowKind.SYSTEM_OVERLAY
                    isFrameworkClass(className) -> WindowKind.APP_OVERLAY
                    else -> WindowKind.APP
                }
            }
        }
    }

    companion object {
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"

        /** `android.*` (not `androidx.*`): framework views, dialogs and service windows. */
        fun isFrameworkClass(className: String): Boolean = className.startsWith("android.")
    }
}
