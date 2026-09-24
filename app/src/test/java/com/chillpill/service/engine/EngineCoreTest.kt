package com.chillpill.service.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineCoreTest {

    private fun h() = EngineHarness()

    // ---- basic block / Continue (ported from SessionPolicyTest) ----------------------------

    @Test fun neverContinued_blocks() {
        val h = h()
        h.app(X)
        assertEquals(listOf(Effect.ShowBlock(X, BlockKind.INITIAL)), h.blocksShown())
        assertEquals(1, h.usage(UsageKind.OPEN_ATTEMPT, X))
    }

    @Test fun continue_letsUserIn_andRecordsWaitCompleted() {
        val h = h()
        h.enterWithContinue(X)
        assertEquals(1, h.blocksShown(X).size)
        assertEquals(1, h.usage(UsageKind.WAIT_COMPLETED, X))
        assertEquals(0, h.usage(UsageKind.LEFT_APP, X))
        assertNull(h.session(X)!!.leftAt)
    }

    @Test fun splashToMain_lateEvent_doesNotBlockTwice() {
        val h = h()
        h.app(X)
        h.blockAppears(X)
        h.advance(300)
        h.app(X) // late splash -> main event while the block is up
        h.advance(3_000)
        h.app(X)
        assertEquals(1, h.blocksShown(X).size)
    }

    @Test fun lateEvent_beforeBlockActivityReports_isAbsorbedByGuard() {
        val h = h()
        h.app(X)
        h.advance(200)
        h.app(X) // before the block window event: same app, within guard
        h.window(OWN, WindowKind.OWN_BLOCK_UI)
        h.advance(300)
        h.app(X) // after block window, before onStart
        assertEquals(1, h.blocksShown(X).size)
    }

    @Test fun withinGrace_returnAfterLongExit_allowed() {
        val h = h()
        h.enterWithContinue(X)
        h.advance(10_000); h.home(); h.advance(30_000); h.app(X)
        assertEquals(1, h.blocksShown(X).size)
    }

    @Test fun leavingDoesNotExtendGrace() {
        val h = h()
        h.enterWithContinue(X)
        h.advance(30_000); h.home()
        val deadline = h.session(X)!!.graceDeadline
        h.advance(40_000)
        assertNull(h.state(X)) // expired while away (G3)
        assertEquals(1, h.usage(UsageKind.GRACE_EXPIRED_WHILE_AWAY, X))
        h.app(X)
        assertEquals(2, h.blocksShown(X).size)
        assertTrue(deadline < h.now)
    }

    @Test fun reblockApp_expiryInside_showsReIntervention() {
        val h = h()
        h.enterWithContinue(X)
        h.advance(GRACE)
        assertEquals(Effect.ShowBlock(X, BlockKind.RE_INTERVENTION), h.blocksShown(X).last())
        assertEquals(1, h.usage(UsageKind.GRACE_EXPIRED_WHILE_ACTIVE, X))
        assertTrue(h.state(X) is AppState.Blocking)
    }

    @Test fun reIntervention_continue_startsNewGrace() {
        val h = h()
        h.enterWithContinue(X)
        h.advance(GRACE)
        h.blockAppears(X)
        h.advance(1_000)
        h.tapContinue(X)
        h.app(X)
        assertNotNull(h.session(X))
        assertEquals(2, h.blocksShown(X).size)
    }

    // ---- no-re-block apps (10 s return window) --------------------------------------------

    @Test fun noReblock_expiryInside_noScreen_sessionStays() {
        val h = h()
        h.enterWithContinue(N)
        h.advance(GRACE + 5_000)
        assertEquals(1, h.blocksShown(N).size)
        assertEquals(1, h.usage(UsageKind.GRACE_EXPIRED_WHILE_ACTIVE, N))
        assertTrue(h.session(N)!!.graceOver)
    }

    @Test fun noReblock_overstay_shortExit_allowed_longExit_blocked() {
        val h = h()
        h.enterWithContinue(N)
        h.advance(GRACE + 60_000)
        h.home(); h.advance(9_000); h.app(N)
        assertEquals(1, h.blocksShown(N).size)
        h.home(); h.advance(11_000); h.app(N)
        assertEquals(2, h.blocksShown(N).size)
    }

    @Test fun noReblock_exitAcrossExpiry_under10s_allowed() {
        val h = h()
        h.enterWithContinue(N)
        h.advance(GRACE - 3_000)
        h.home(); h.advance(6_000); h.app(N)
        assertEquals(1, h.blocksShown(N).size)
        assertEquals(1, h.usage(UsageKind.GRACE_EXPIRED_WHILE_AWAY, N))
    }

    @Test fun noReblock_chillpillMainUi_countsAsLeaving() {
        val h = h()
        h.enterWithContinue(N)
        h.advance(GRACE + 1_000)
        h.window(OWN, WindowKind.OWN_MAIN_UI, className = "com.chillpill.MainActivity")
        h.advance(20_000)
        h.app(N)
        assertEquals(2, h.blocksShown(N).size)
    }

    @Test fun overlays_neverChangePresence() {
        val h = h()
        h.enterWithContinue(N)
        h.advance(GRACE + 1_000)
        h.shade(); h.advance(60_000)
        h.window("ime.pkg", WindowKind.SYSTEM_OVERLAY); h.advance(20_000)
        h.window(OWN, WindowKind.OWN_OVERLAY); h.advance(20_000)
        h.app(N)
        assertEquals(1, h.blocksShown(N).size)
        assertEquals(N, h.core.presence.pkg)
    }

    // ---- S1: stale presence after unlock --------------------------------------------------

    /** Launcher flashes after unlock, the app resumes without a window event; UsageStats fixes it. */
    @Test fun s1_staleLauncherAfterUnlock_probeCorrects_noBlock() {
        val h = h()
        h.enterWithContinue(N)
        h.advance(GRACE + 60_000) // overstayed: only the 10 s window protects the session
        h.screenOff()
        h.advance(3_000)
        h.autoProbe = false
        h.userPresent()
        h.advance(100)
        h.home() // launcher flash
        val resumed = h.now + 200
        h.advance(400)
        h.truth = ProbeResult(N, WindowKind.APP, resumed)
        h.answerProbes()
        assertEquals(N, h.core.presence.pkg)
        h.advance(30_000)
        h.app(N) // a dialog inside the app, much later
        assertEquals(1, h.blocksShown(N).size)
        // and the real next exit is recorded again
        h.autoProbe = true
        h.home(); h.advance(5_000); h.app(N)
        assertEquals(1, h.blocksShown(N).size)
    }

    @Test fun s1_control_longScreenOff_blocks() {
        val h = h()
        h.enterWithContinue(N)
        h.advance(GRACE + 60_000)
        h.screenOff()
        h.advance(15_000)
        h.userPresent()
        h.app(N)
        assertEquals(2, h.blocksShown(N).size)
    }

    @Test fun screenOff_short_withKeyguard_allowed() {
        val h = h()
        h.enterWithContinue(N)
        h.advance(GRACE + 1_000)
        h.screenOff(); h.advance(3_000)
        h.screenOn(locked = true); h.advance(2_000)
        h.userPresent(); h.advance(100)
        h.app(N)
        assertEquals(1, h.blocksShown(N).size)
    }

    @Test fun probeBurst_inconclusive_fallsBackToScreenOffApp() {
        val h = h()
        h.enterWithContinue(N)
        h.advance(GRACE + 1_000)
        h.screenOff(); h.advance(4_000)
        h.screenOn(locked = false)
        h.advance(2_000) // four inconclusive probes, no window event
        assertEquals(N, h.core.presence.pkg)
        assertNull(h.session(N)!!.leftAt)
        assertEquals(1, h.blocksShown(N).size)
    }

    @Test fun olderProbe_neverOverridesNewerWindowEvent() {
        val h = h()
        h.enterWithContinue(X)
        h.home()
        h.truth = ProbeResult(X, WindowKind.APP, h.now - 5_000)
        h.advance(20_000) // reconciliation probes answer with the old resume
        assertEquals(L, h.core.presence.pkg)
    }

    // ---- S2 / B1: warning pill ------------------------------------------------------------

    @Test fun s2_pillAndReInterventionAgree_afterStaleUnlock() {
        val h = h()
        h.enterWithContinue(X)
        h.advance(20_000)
        h.screenOff(); h.advance(2_000)
        h.autoProbe = false
        h.userPresent(); h.advance(50)
        h.home()
        h.truth = ProbeResult(X, WindowKind.APP, h.now + 100)
        h.advance(400)
        h.answerProbes()
        h.autoProbe = true
        val deadline = h.session(X)!!.graceDeadline
        h.advance(deadline - 9_000 - h.now)
        assertNotNull(h.warning())
        h.advance(9_000)
        assertEquals(BlockKind.RE_INTERVENTION, h.blocksShown(X).last().kind)
    }

    @Test fun b1_ignoreFirstWarning_itStaysToTheEnd() {
        val h = h()
        h.enterWithContinue(X)
        val d = h.session(X)!!.graceDeadline
        h.advance(d - 10_001 - h.now); assertNull(h.warning())
        h.advance(1); assertEquals(WarningUi(X, d, canExtend = true, dismissible = true), h.warning())
        h.advance(7_000); assertTrue(h.warning()!!.canExtend) // the 10 s pill is still up at 3 s
    }

    @Test fun b1_dismissedFirstWarning_finalCountdownStillShows() {
        val h = h()
        h.enterWithContinue(X)
        val d = h.session(X)!!.graceDeadline
        h.advance(d - 8_000 - h.now)
        h.dismissWarning(X)
        assertNull(h.warning())
        h.advance(3_000)
        assertEquals(WarningUi(X, d, canExtend = false, dismissible = false), h.warning())
    }

    @Test fun b1_afterExtension_finalCountdownAtFive() {
        val h = h()
        h.enterWithContinue(X)
        val d = h.session(X)!!.graceDeadline
        h.advance(d - 4_000 - h.now)
        h.extend(X)
        assertEquals(1, h.usage(UsageKind.GRACE_EXTENDED, X))
        assertEquals(d + 10_000, h.session(X)!!.graceDeadline)
        assertTrue(h.warning()!!.extensionConfirmed) // "10 s added" for a moment
        h.advance(EngineCore.EXTEND_CONFIRMATION_MS)
        assertNull(h.warning()) // then nothing: ~13 s left
        h.advance(8_999 - EngineCore.EXTEND_CONFIRMATION_MS); assertNull(h.warning())
        h.advance(1); assertEquals(WarningUi(X, d + 10_000, canExtend = false, dismissible = false), h.warning())
        h.extend(X) // only once
        assertEquals(d + 10_000, h.session(X)!!.graceDeadline)
        h.advance(5_000)
        assertEquals(BlockKind.RE_INTERVENTION, h.blocksShown(X).last().kind)
    }

    @Test fun extensionConfirmation_goesWithTheApp_andNeedsAnAcceptedTap() {
        val h = h()
        h.enterWithContinue(X)
        val d = h.session(X)!!.graceDeadline
        h.advance(d - 3_000 - h.now)
        h.extend(X)
        assertTrue(h.warning()!!.extensionConfirmed)
        h.home()
        assertNull(h.warning()) // leaving takes the confirmation away at once

        val late = h()
        late.enterWithContinue(X)
        late.advance(late.session(X)!!.graceDeadline - late.now)
        late.extend(X) // at the deadline: too late, re-intervention instead
        assertNull(late.warning())
        assertEquals(0, late.usage(UsageKind.GRACE_EXTENDED))
    }

    @Test fun b1_enterWithFewSecondsLeft() {
        val h = h()
        h.enterWithContinue(X)
        val d = h.session(X)!!.graceDeadline
        h.home()
        h.advance(d - 7_000 - h.now)
        h.app(X)
        assertTrue(h.warning()!!.canExtend)
        h.dismissWarning(X)
        h.home(); h.advance(4_000); h.app(X)
        assertEquals(false, h.warning()!!.dismissible)
    }

    @Test fun warning_onlyOverItsOwnApp_andNeverForNoReblock() {
        val h = h()
        h.enterWithContinue(X)
        val d = h.session(X)!!.graceDeadline
        h.advance(d - 8_000 - h.now)
        assertNotNull(h.warning())
        h.shade(); assertNotNull(h.warning())
        h.home(); assertNull(h.warning())
        h.app(X); assertNotNull(h.warning())
        h.screenOff(); assertNull(h.warning())

        val n = h()
        n.enterWithContinue(N)
        n.advance(GRACE - 3_000)
        assertNull(n.warning())
    }

    // ---- block screen lifecycle -----------------------------------------------------------

    @Test fun goHome_recordsLeftAppOnce_andReopenBlocks() {
        val h = h()
        h.app(X); h.blockAppears(X); h.advance(2_000)
        h.blockClosed(X, CloseReason.GO_HOME)
        h.home()
        h.blockClosed(X, CloseReason.SYSTEM_GESTURE) // onUserLeaveHint / onDestroy after Go Home
        assertEquals(1, h.usage(UsageKind.LEFT_APP, X))
        assertEquals(1, h.blocksShown(X).size)
        h.advance(5_000); h.app(X)
        assertEquals(2, h.blocksShown(X).size)
    }

    /** The app's late window event while its block is up must not make Go Home look like "in the app". */
    @Test fun lateEventThenGoHome_noSecondBlock() {
        val h = h()
        h.app(X); h.blockAppears(X)
        h.advance(400); h.app(X) // splash -> main while the block is visible
        h.advance(3_000)
        h.blockClosed(X, CloseReason.GO_HOME)
        h.advance(100); h.home()
        h.advance(2_000)
        assertEquals(1, h.blocksShown(X).size)
        assertNull(h.state(X))
    }

    /** Block -> shade -> tap the app's notification: the app's event is absorbed, probes find it. */
    @Test fun shadeNotificationPastBlock_blocksAgain() {
        val h = h()
        h.app(X); h.blockAppears(X); h.advance(3_000)
        h.shade()
        h.app(X) // absorbed: block still reported visible
        h.truth = ProbeResult(X, WindowKind.APP, h.now)
        h.advance(50)
        h.blockStops(X)
        h.blockClosed(X, CloseReason.SYSTEM_GESTURE)
        h.advance(1_000)
        assertEquals(2, h.blocksShown(X).size)
        assertTrue(h.state(X) is AppState.Blocking)
    }

    /** Same, with no usage access (every probe inconclusive): the shade itself arms the block. */
    @Test fun shadeNotificationPastBlock_withoutUsageAccess_blocksAgain() {
        val h = h()
        h.app(X); h.blockAppears(X); h.advance(3_000)
        h.shade()
        h.app(X)
        h.advance(50)
        h.blockStops(X)
        h.blockClosed(X, CloseReason.SYSTEM_GESTURE)
        h.advance(2_000)
        assertEquals(2, h.blocksShown(X).size)
        assertTrue(h.state(X) is AppState.Blocking)
    }

    /** Heads-up tap without a shade event, long after the block came up, no usage access. */
    @Test fun appOverBlockWithoutShade_blocksAgainOnStop() {
        val h = h()
        h.app(X); h.blockAppears(X); h.advance(5_000)
        h.app(X) // not absorbed: too long after the block appeared
        h.blockStops(X)
        assertEquals(2, h.blocksShown(X).size)
    }

    @Test fun lateEventAfterAbsorbWindow_thenGoHome_noSecondBlock() {
        val h = h()
        h.app(X); h.blockAppears(X)
        h.advance(2_500); h.app(X) // a late event that is no longer absorbed
        h.advance(1_000)
        h.blockClosed(X, CloseReason.GO_HOME) // explicit exit never re-blocks
        h.home()
        assertEquals(1, h.blocksShown(X).size)
    }

    @Test fun screenOffWhileBlocked_blockGoneAfterUnlock_fallbackBlocksAgain() {
        val h = h()
        h.app(X); h.blockAppears(X); h.advance(3_000)
        h.screenOff()
        h.blockStops(X)
        h.blockClosed(X, CloseReason.SYSTEM_GESTURE) // NO_HISTORY screen finished during sleep
        h.advance(20_000)
        h.userPresent()
        h.advance(2_000) // no window event, no usage access
        assertEquals(2, h.blocksShown(X).size)
    }

    @Test fun recentsPastBlock_reshows_andOldInstanceCloseIgnored() {
        val h = h()
        h.app(X); h.blockAppears(X); h.advance(3_000)
        val old = h.instances.getValue(X)
        h.home() // recents
        h.advance(500)
        h.app(X)
        h.blockStops(X)
        assertEquals(2, h.blocksShown(X).size)
        h.blockClosed(X, CloseReason.SYSTEM_GESTURE, instance = old)
        assertTrue(h.state(X) is AppState.Blocking)
        h.blockAppears(X)
        assertTrue((h.state(X) as AppState.Blocking).uiVisible)
    }

    @Test fun blockNeverAppears_nextEventReshowsAfterGuard() {
        val h = h()
        h.app(X)
        h.advance(500); h.app(X)
        assertEquals(1, h.blocksShown(X).size)
        h.advance(2_000); h.app(X)
        assertEquals(2, h.blocksShown(X).size)
    }

    @Test fun doubleContinue_ignored() {
        val h = h()
        h.app(X); h.blockAppears(X)
        h.tapContinue(X)
        val d = h.session(X)!!.graceDeadline
        h.advance(500)
        h.tapContinue(X)
        assertEquals(d, h.session(X)!!.graceDeadline)
        assertEquals(1, h.usage(UsageKind.WAIT_COMPLETED, X))
    }

    // ---- time -----------------------------------------------------------------------------

    @Test fun expiryDuringDeepSleep_caughtUpOnScreenOn() {
        val h = h()
        h.enterWithContinue(X)
        h.screenOff()
        h.sleep(5 * 60_000L)
        h.screenOn(locked = true)
        assertNull(h.state(X))
        assertEquals(1, h.usage(UsageKind.GRACE_EXPIRED_WHILE_AWAY, X))
        h.userPresent(); h.app(X)
        assertEquals(BlockKind.INITIAL, h.blocksShown(X).last().kind)
    }

    @Test fun twoAppsInGrace_independent() {
        val h = h()
        h.enterWithContinue(X)
        h.advance(20_000)
        h.enterWithContinue(Y)
        val dx = h.session(X)!!.graceDeadline
        h.advance(dx - 5_000 - h.now)
        assertNull(h.warning()) // X's pill never shows over Y
        h.advance(5_000)
        assertNull(h.state(X)) // X expired while away
        assertNotNull(h.session(Y))
        h.app(X)
        assertEquals(2, h.blocksShown(X).size)
    }

    @Test fun noReblock_overstayThenAway_sessionEnds_andProbingStops() {
        val h = h()
        h.enterWithContinue(N)
        h.advance(GRACE + 30_000)
        h.home()
        h.advance(EngineCore.RETURN_WINDOW_MS)
        assertNull(h.state(N))
        h.clearEffects()
        h.advance(10 * 60_000L)
        assertTrue(h.effects.none { it is Effect.RequestProbe })
        h.app(N)
        assertEquals(1, h.blocksShown(N).size) // effects were cleared: this is the new block
    }

    // ---- config / suggestions / restart ----------------------------------------------------

    @Test fun configRemovesApp_dropsState() {
        val h = h()
        h.enterWithContinue(X)
        h.send(Input.ConfigChanged(Config(setOf(Y, N), setOf(N), GRACE), h.now))
        assertNull(h.state(X))
        h.clearEffects()
        h.home(); h.app(X)
        assertTrue(h.effects.contains(Effect.EvaluateSuggestion(X)))
        assertTrue(h.blocksShown().isEmpty())
    }

    @Test fun suggestion_shownOnlyWhileInApp_closesOnLeave() {
        val h = h()
        h.app(S)
        assertTrue(h.effects.contains(Effect.EvaluateSuggestion(S)))
        h.send(Input.SuggestionEligible(S, "Suggest", h.now))
        assertEquals(SuggestionUi(S, "Suggest"), h.core.suggestion)
        h.home()
        assertNull(h.core.suggestion)
        h.send(Input.SuggestionEligible(S, "Suggest", h.now)) // late answer, user gone
        assertNull(h.core.suggestion)
    }

    @Test fun suggestion_restrict_startsSession_noBlock() {
        val h = h()
        h.app(S)
        h.send(Input.SuggestionEligible(S, "Suggest", h.now))
        h.send(Input.SuggestionAnswered(S, SuggestionAnswer.RESTRICT, h.now))
        assertNull(h.core.suggestion)
        assertTrue(h.effects.contains(Effect.PersistSuggestionAnswer(S, SuggestionAnswer.RESTRICT)))
        assertNotNull(h.session(S))
        h.app(S)
        assertTrue(h.blocksShown(S).isEmpty())
        // DataStore catches up; the pending override goes away, the session stays
        h.send(Input.ConfigChanged(Config(setOf(X, Y, N, S), setOf(N), GRACE), h.now))
        assertNotNull(h.session(S))
        h.advance(GRACE)
        assertEquals(BlockKind.RE_INTERVENTION, h.blocksShown(S).last().kind)
    }

    @Test fun suggestion_doesNotFreezePipeline() {
        val h = h()
        h.app(S)
        h.send(Input.SuggestionEligible(S, "Suggest", h.now))
        h.app(X)
        assertEquals(1, h.blocksShown(X).size)
        assertNull(h.core.suggestion)
    }

    @Test fun processRestart_insideApp_blocksOnNextEvent() {
        val h = h()
        h.app(X) // fresh engine, user was mid-grace before the crash
        assertEquals(1, h.blocksShown(X).size)
    }

    @Test fun reconciliation_onlyWhileSessionsExist() {
        val h = h()
        h.home()
        h.advance(60_000)
        assertFalse(h.effects.any { it is Effect.RequestProbe })
        h.enterWithContinue(X)
        h.clearEffects()
        h.advance(20_000)
        assertTrue(h.effects.count { it is Effect.RequestProbe } >= 3)
    }
}
