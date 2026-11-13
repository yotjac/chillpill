package com.chillpill.service.engine

import com.chillpill.service.engine.ForegroundResolver.Kind.PAUSED
import com.chillpill.service.engine.ForegroundResolver.Kind.RESUMED
import com.chillpill.service.engine.ForegroundResolver.Kind.STOPPED
import com.chillpill.service.engine.ForegroundResolver.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundResolverTest {

    private val insta = "com.instagram.android"
    private val main = "com.instagram.mainactivity.InstagramMainActivity"
    private val modal = "com.instagram.modal.TransparentModalActivity"
    private val launcher = "com.google.android.apps.nexuslauncher"
    private val home = "com.google.android.apps.nexuslauncher.NexusLauncherActivity"
    private val own = "com.chillpillapp"
    private val block = "com.chillpill.AppBlockActivity"
    private val whatsapp = "com.whatsapp"

    private fun resolver() = ForegroundResolver(windowMs = 600_000, overlapMs = 2_000)

    /**
     * The UsageStats events of the device incident (dumpsys usagestats, 2026-10-04 09:01–09:05):
     * the launcher resumes and pauses again under Instagram, which is never paused. Tracking only
     * "the last resumed package" reported nothing here.
     */
    @Test fun launcherBlip_appThatNeverPausedIsStillTheForeground() {
        val r = resolver()
        r.onEvent(RESUMED, insta, main, 1_000)
        r.onEvent(PAUSED, insta, main, 1_200)
        r.onEvent(RESUMED, own, block, 1_300)
        r.onEvent(STOPPED, insta, main, 2_000)
        r.onEvent(PAUSED, own, block, 19_000)
        r.onEvent(RESUMED, insta, main, 19_100)
        r.onEvent(STOPPED, own, block, 19_600)
        assertEquals(Snapshot(insta, 19_100, onlyResumed = true), r.snapshot())

        r.onEvent(RESUMED, launcher, home, 107_000)
        // During the blip both are resumed: not a statement the engine may act on.
        assertEquals(Snapshot(launcher, 107_000, onlyResumed = false), r.snapshot())

        r.onEvent(PAUSED, launcher, home, 107_300)
        r.onEvent(STOPPED, launcher, home, 107_400)
        assertEquals(Snapshot(insta, 19_100, onlyResumed = true), r.snapshot())
    }

    @Test fun realSwitch_pauseThenResume() {
        val r = resolver()
        r.onEvent(RESUMED, whatsapp, "a.Home", 1_000)
        r.onEvent(PAUSED, whatsapp, "a.Home", 5_000)
        r.onEvent(RESUMED, insta, main, 5_100)
        assertEquals(Snapshot(insta, 5_100, onlyResumed = true), r.snapshot())
    }

    /** Going home is logged in this order on the device: the launcher resumes, then the app pauses. */
    @Test fun realGoHome_resumeBeforePause() {
        val r = resolver()
        r.onEvent(RESUMED, whatsapp, "a.Home", 1_000)
        r.onEvent(RESUMED, launcher, home, 5_000)
        r.onEvent(PAUSED, whatsapp, "a.Home", 5_050)
        r.onEvent(STOPPED, whatsapp, "a.Home", 5_600)
        assertEquals(Snapshot(launcher, 5_000, onlyResumed = true), r.snapshot())
    }

    @Test fun everythingPaused_isNull() {
        val r = resolver()
        r.onEvent(RESUMED, insta, main, 1_000)
        r.onEvent(PAUSED, insta, main, 9_000) // screen off
        assertNull(r.snapshot())
    }

    /** A launch-behind activity is paused / stopped without ever being reported resumed. */
    @Test fun unmatchedPauseAndStop_changeNothing() {
        val r = resolver()
        r.onEvent(RESUMED, insta, main, 1_000)
        r.onEvent(PAUSED, launcher, home, 5_000)
        r.onEvent(STOPPED, launcher, home, 5_100)
        assertEquals(Snapshot(insta, 1_000, onlyResumed = true), r.snapshot())
    }

    @Test fun activitiesOfOnePackage_areTrackedSeparately() {
        val r = resolver()
        r.onEvent(RESUMED, insta, main, 1_000)
        r.onEvent(PAUSED, insta, main, 4_000)
        r.onEvent(RESUMED, insta, modal, 4_050)
        assertEquals(Snapshot(insta, 4_050, onlyResumed = true), r.snapshot())
        r.onEvent(PAUSED, insta, modal, 8_000)
        r.onEvent(RESUMED, insta, main, 8_050)
        r.onEvent(STOPPED, insta, modal, 8_500)
        assertEquals(Snapshot(insta, 8_050, onlyResumed = true), r.snapshot())
    }

    @Test fun splitScreen_twoResumedPackages_isNotOnlyResumed() {
        val r = resolver()
        r.onEvent(RESUMED, whatsapp, "a.Home", 1_000)
        r.onEvent(RESUMED, insta, main, 2_000)
        assertEquals(Snapshot(insta, 2_000, onlyResumed = false), r.snapshot())
    }

    @Test fun equalTimestamps_laterEventWins() {
        val r = resolver()
        r.onEvent(RESUMED, launcher, home, 1_000)
        r.onEvent(RESUMED, insta, main, 1_000)
        assertEquals(insta, r.snapshot()!!.pkg)
    }

    // ---- continuity across queries ----------------------------------------------------------

    @Test fun firstQuery_readsTheWindow_laterQueriesOnlyTheTail() {
        val r = resolver()
        val t0 = 10_000_000L
        assertEquals(t0 - 600_000, r.beginQuery(t0))
        r.onEvent(RESUMED, insta, main, t0 - 30_000)
        assertEquals(Snapshot(insta, t0 - 30_000, onlyResumed = true), r.endQuery(t0))
        assertEquals(t0 - 2_000, r.beginQuery(t0 + 5_000))
    }

    /** The old query forgot an activity ten minutes after its resume; probes went blind. */
    @Test fun longSession_staysKnownWhileQueriesContinue() {
        val r = resolver()
        var now = 10_000_000L
        r.beginQuery(now)
        r.onEvent(RESUMED, insta, main, now - 1_000)
        r.endQuery(now)
        val resumedAt = now - 1_000
        repeat(720) { // one hour of 5 s probes with no events at all
            now += 5_000
            r.beginQuery(now)
            assertEquals(Snapshot(insta, resumedAt, onlyResumed = true), r.endQuery(now))
        }
    }

    @Test fun overlap_reappliedEvents_giveTheSameResult() {
        val r = resolver()
        val t0 = 10_000_000L
        r.beginQuery(t0)
        r.onEvent(RESUMED, insta, main, t0 - 60_000)
        r.onEvent(RESUMED, launcher, home, t0 - 1_000)
        r.onEvent(PAUSED, launcher, home, t0 - 800)
        val first = r.endQuery(t0)
        assertEquals(Snapshot(insta, t0 - 60_000, onlyResumed = true), first)

        // The next query starts 2 s before the cursor and sees the launcher pair again.
        r.beginQuery(t0 + 5_000)
        r.onEvent(RESUMED, launcher, home, t0 - 1_000)
        r.onEvent(PAUSED, launcher, home, t0 - 800)
        assertEquals(first, r.endQuery(t0 + 5_000))
    }

    @Test fun gapLongerThanWindow_startsOver() {
        val r = resolver()
        val t0 = 10_000_000L
        r.beginQuery(t0)
        r.onEvent(RESUMED, insta, main, t0 - 1_000)
        r.endQuery(t0)
        val later = t0 + 600_001
        assertEquals(later - 600_000, r.beginQuery(later))
        assertNull(r.endQuery(later)) // nothing fed: the old state is gone
    }

    @Test fun clockWentBackwards_startsOver() {
        val r = resolver()
        val t0 = 10_000_000L
        r.beginQuery(t0)
        r.onEvent(RESUMED, insta, main, t0 - 1_000)
        r.endQuery(t0)
        val earlier = t0 - 60_000
        assertEquals(earlier - 600_000, r.beginQuery(earlier))
        assertNull(r.endQuery(earlier))
    }

    @Test fun failedQuery_isRepeatedFromTheSameCursor() {
        val r = resolver()
        val t0 = 10_000_000L
        r.beginQuery(t0)
        r.onEvent(RESUMED, insta, main, t0 - 1_000)
        r.endQuery(t0)
        r.beginQuery(t0 + 5_000) // the query throws: endQuery is never called
        assertEquals(t0 - 2_000, r.beginQuery(t0 + 10_000))
    }
}
