package com.chillpill.service.engine

/**
 * Decides what a window event (or a UsageStats probe, [className] null) means. Pure; the Android
 * lookups are injected.
 *
 * Keyboards: apps that ship an IME (SwiftKey, Grammarly, Samsung Keyboard) also have real
 * activities, so for an enabled-IME package only a window whose class is *not* one of the
 * package's activities is an overlay. An unknown class (null) of an IME package counts as an
 * overlay, which is the safe reading for a probe.
 */
class WindowClassifier(
    private val ownPackage: String,
    private val ownMainUiClasses: Set<String>,
    private val ownBlockUiClasses: Set<String>,
    private val imePackages: () -> Set<String>,
    private val isActivityOf: (pkg: String, className: String) -> Boolean
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
        pkg in imePackages() ->
            if (!className.isNullOrEmpty() && isActivityOf(pkg, className)) WindowKind.APP
            else WindowKind.SYSTEM_OVERLAY
        else -> WindowKind.APP
    }

    companion object {
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    }
}
