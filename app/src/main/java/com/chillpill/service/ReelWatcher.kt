package com.chillpill.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.chillpill.service.engine.Input
import com.chillpill.service.engine.ReelApps
import com.chillpill.service.engine.SessionEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The accessibility service's eyes on a reel viewer (specs/reel-finish.md). A sensor only: it
 * reports what is on screen as [Input.ReelProgress] / [Input.ReelSwiped] and decides nothing.
 *
 * Runs only while the session engine names an app in `SessionEngine.reelWatch` (the last seconds
 * of a session that may wait for a reel, and the wait itself). While it runs:
 * - once a second it reads the viewer's seek bar; two readings of the same reel that moved
 *   forward at a plausible playback speed mean "a reel is playing and ends in N ms";
 * - a scroll event of the viewer's pager whose page index changed means "moved to another reel".
 *
 * Everything else (no seek bar, a paused or stale one, another unit than milliseconds, a changed
 * view id after an app update) reports nothing, and the engine then blocks on time as before.
 *
 * Main thread only. View ids come from a device recording, see the spec.
 */
internal class ReelWatcher(
    private val service: AccessibilityService,
    private val scope: CoroutineScope,
    private val send: (Input) -> Unit
) {
    private class Views(val pager: String, val seekBar: String)
    private class Sample(val position: Float, val duration: Float, val at: Long)

    private var watched: String? = null
    private var views: Views? = null
    private var job: Job? = null
    private var lastSample: Sample? = null
    private var lastPage: Int? = null

    /** True while an app is being followed: the service then also listens to scroll events. */
    val active: Boolean get() = views != null

    /** Follow [pkg]'s reel viewer, or stop (null, or an app without a known viewer). */
    fun watch(pkg: String?) {
        if (pkg == watched) return
        job?.cancel()
        job = null
        watched = pkg
        lastSample = null
        lastPage = null
        val v = pkg?.let { VIEWS[it] }
        views = v
        if (pkg == null || v == null) return
        Log.d(TAG, "watching $pkg")
        job = scope.launch {
            while (isActive) {
                try {
                    sample(pkg, v)
                } catch (t: Throwable) {
                    Log.w(TAG, "sampling the reel failed", t)
                    lastSample = null
                }
                delay(SAMPLE_INTERVAL_MS)
            }
        }
    }

    private fun sample(pkg: String, v: Views) {
        val root = service.rootInActiveWindow
        val range = root
            ?.takeIf { it.packageName?.toString() == pkg }
            ?.findAccessibilityNodeInfosByViewId("$pkg:id/${v.seekBar}")
            ?.firstOrNull { it.isVisibleToUser }
            ?.rangeInfo
        if (range == null) {
            lastSample = null
            return
        }
        val now = SessionEngine.now()
        val current = Sample(range.current, range.max, now)
        val previous = lastSample
        lastSample = current
        if (previous == null || previous.duration != current.duration) return // first look, or another reel
        val elapsed = now - previous.at
        val moved = current.position - previous.position
        if (elapsed <= 0L || elapsed > MAX_SAMPLE_GAP_MS || moved <= 0f) return // paused, looped or stale
        // Position units per millisecond: 1.0 when the bar counts milliseconds at normal speed.
        val rate = moved / elapsed
        if (rate < MIN_RATE || rate > MAX_RATE) return
        val remainingMs = ((current.duration - current.position) / rate).toLong().coerceAtLeast(0L)
        send(Input.ReelProgress(pkg, remainingMs, now))
    }

    /** Every TYPE_VIEW_SCROLLED event the service gets while [active]. */
    fun onScrolled(event: AccessibilityEvent) {
        val pkg = watched ?: return
        val v = views ?: return
        if (event.packageName?.toString() != pkg) return
        val sourceId = try {
            event.source?.viewIdResourceName
        } catch (_: Throwable) {
            null
        }
        if (sourceId != "$pkg:id/${v.pager}") return
        val page = event.fromIndex
        if (page < 0) return
        val previous = lastPage
        lastPage = page
        // A swipe sends several events; the index changes once, when the next reel takes over.
        if (previous != null && previous != page) {
            lastSample = null
            send(Input.ReelSwiped(pkg, SessionEngine.elapsedFromUptime(event.eventTime)))
        }
    }

    companion object {
        private const val TAG = "ReelWatcher"

        /** Added to the service's event types while [active]. */
        const val EVENT_TYPES = AccessibilityEvent.TYPE_VIEW_SCROLLED

        /** View ids, and views marked "not important for accessibility" (the recording had both on). */
        const val FLAGS = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS

        private const val SAMPLE_INTERVAL_MS = 1_000L
        private const val MAX_SAMPLE_GAP_MS = 2_500L
        private const val MIN_RATE = 0.25f
        private const val MAX_RATE = 4f

        /** Instagram 2026-11: `clips_viewer_view_pager` (ViewPager), `scrubber` (SeekBar, range in ms). */
        private val VIEWS = mapOf(
            ReelApps.INSTAGRAM to Views(pager = "clips_viewer_view_pager", seekBar = "scrubber")
        )
    }
}
