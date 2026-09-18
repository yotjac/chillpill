package com.chillpill.service

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.AbstractComposeView

/**
 * Root view of the grace-expiry warning pill: an [AbstractComposeView] subclass purely so the
 * window has a class name of its own ([androidx.compose.ui.platform.ComposeView] is final).
 *
 * The pill's window is non-focusable, so Android should never emit a `TYPE_WINDOW_STATE_CHANGED`
 * event for it — and an own-package event that is not one of our activities is ignored by
 * [ChillpillAccessibilityService] anyway (I3 in specs/grace-expiry-warning.md), whatever class name
 * it carries. The distinct class name is what makes such an event recognisable in logcat.
 */
class GraceWarningView(
    context: Context,
    private val content: @Composable () -> Unit
) : AbstractComposeView(context) {

    @Composable
    override fun Content() {
        content()
    }
}
