package com.chillpill.service.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reel finish (specs/reel-finish.md): R1 report, R2 hold, R3 swipe, R4 watch window. */
class ReelFinishTest {

    private val link = "app.link"

    private fun h() = EngineHarness(
        config = Config(
            mapOf(
                X to AppRule(Mode.TIME_LIMIT, GRACE, finishReel = true),
                Y to AppRule(Mode.TIME_LIMIT, GRACE),
                N to AppRule(Mode.PAUSE_ONLY, GRACE, finishReel = true),
                link to AppRule(Mode.TIME_LIMIT, GRACE, linkPassMs = 5 * 60_000L, finishReel = true)
            ),
            AppRule(Mode.TIME_LIMIT, GRACE)
        )
    )

    private fun EngineHarness.reel(pkg: String, remainingMs: Long) = send(Input.ReelProgress(pkg, remainingMs, now))
    private fun EngineHarness.swipe(pkg: String) = send(Input.ReelSwiped(pkg, now))

    /** In [pkg] with a running session, [beforeDeadline] ms before its time runs out. */
    private fun EngineHarness.inSession(pkg: String, beforeDeadline: Long): Long {
        enterWithContinue(pkg)
        clearEffects()
        val deadline = session(pkg)!!.graceDeadline
        advance(deadline - beforeDeadline - now)
        return deadline
    }

    // ---- R2: the hold ---------------------------------------------------------------------

    @Test fun reelPlayingAtDeadline_holdsUntilItEnds_thenReIntervenes() {
        val h = h()
        val deadline = h.inSession(X, 1_000)
        val reelEnd = h.now + 20_000
        h.reel(X, 20_000)
        h.advance(1_000)
        assertEquals(deadline, h.now)
        assertTrue(h.blocksShown().isEmpty())
        val held = h.session(X)!!
        assertTrue(held.reelHeld)
        assertEquals(reelEnd + EngineCore.REEL_END_SLACK_MS, held.graceDeadline)
        val pill = h.warning()!!
        assertTrue(pill.reelHold)
        assertFalse(pill.canExtend)
        assertFalse(pill.dismissible)
        assertEquals(held.graceDeadline, pill.deadline)

        h.advance(held.graceDeadline - h.now - 1)
        assertTrue(h.blocksShown().isEmpty())
        h.advance(1)
        assertEquals(listOf(Effect.ShowBlock(X, BlockKind.RE_INTERVENTION)), h.blocksShown())
        assertEquals(1, h.usage(UsageKind.GRACE_EXPIRED_WHILE_ACTIVE, X))
        assertEquals(BlockKind.RE_INTERVENTION, (h.state(X) as AppState.Blocking).kind)
    }

    @Test fun hold_isFixedWhenItStarts_laterReportsDoNotMoveIt() {
        val h = h()
        h.inSession(X, 1_000)
        h.reel(X, 20_000)
        h.advance(1_000)
        val end = h.session(X)!!.graceDeadline
        h.advance(5_000)
        h.reel(X, 50_000) // scrubbed back, or a longer reading: no effect
        assertEquals(end, h.session(X)!!.graceDeadline)
        h.advance(end - h.now)
        assertEquals(1, h.blocksShown(X).size)
    }

    @Test fun noExtensionDuringHold() {
        val h = h()
        h.inSession(X, 1_000)
        h.reel(X, 20_000)
        h.advance(1_000)
        val end = h.session(X)!!.graceDeadline
        h.extend(X)
        assertEquals(end, h.session(X)!!.graceDeadline)
        assertEquals(0, h.usage(UsageKind.GRACE_EXTENDED))
    }

    @Test fun extensionBeforeDeadline_thenHoldAtTheNewDeadline() {
        val h = h()
        val deadline = h.inSession(X, 8_000)
        assertTrue(h.warning()!!.canExtend)
        assertFalse(h.warning()!!.reelHold)
        h.extend(X)
        val extended = deadline + EngineCore.GRACE_EXTENSION_MS
        assertEquals(extended, h.session(X)!!.graceDeadline)
        h.advance(extended - 1_000 - h.now)
        h.reel(X, 30_000)
        h.advance(1_000)
        assertTrue(h.session(X)!!.reelHeld)
        assertTrue(h.blocksShown().isEmpty())
    }

    // ---- R3: swipe ------------------------------------------------------------------------

    @Test fun swipeDuringHold_blocksAtOnce() {
        val h = h()
        h.inSession(X, 1_000)
        h.reel(X, 40_000)
        h.advance(1_000)
        h.advance(7_000)
        assertTrue(h.blocksShown().isEmpty())
        h.swipe(X)
        assertEquals(listOf(Effect.ShowBlock(X, BlockKind.RE_INTERVENTION)), h.blocksShown())
        assertEquals(1, h.usage(UsageKind.GRACE_EXPIRED_WHILE_ACTIVE, X))
        // Nothing left to fire at the old hold end.
        h.blockAppears(X)
        h.advance(60_000)
        assertEquals(1, h.blocksShown(X).size)
    }

    @Test fun swipeBeforeDeadline_forgetsThePreviousReel() {
        val h = h()
        h.inSession(X, 2_000)
        h.reel(X, 20_000)
        h.advance(1_000)
        h.swipe(X) // the next reel has not been seen playing yet
        assertNull(h.session(X)!!.reelSeenAt)
        h.advance(1_000)
        assertEquals(1, h.blocksShown(X).size)
    }

    @Test fun swipeWithoutHold_neverBlocks() {
        val h = h()
        h.inSession(X, 30_000)
        h.swipe(X)
        assertTrue(h.blocksShown().isEmpty())
        assertNotNull(h.session(X))
    }

    // ---- when there is no hold --------------------------------------------------------------

    @Test fun nothingReported_blocksOnTime() {
        val h = h()
        h.inSession(X, 1_000)
        h.advance(1_000)
        assertEquals(1, h.blocksShown(X).size)
    }

    @Test fun staleReport_blocksOnTime() {
        val h = h()
        h.inSession(X, EngineCore.REEL_FRESH_MS + 500)
        h.reel(X, 20_000) // then the seek bar stopped moving (paused, left the viewer)
        h.advance(EngineCore.REEL_FRESH_MS + 500)
        assertEquals(1, h.blocksShown(X).size)
    }

    @Test fun reelEndsTooLate_blocksOnTime() {
        val h = h()
        h.inSession(X, 1_000)
        h.reel(X, 1_000 + EngineCore.REEL_HOLD_MAX_MS + 1)
        h.advance(1_000)
        assertEquals(1, h.blocksShown(X).size)
    }

    @Test fun reelEndsExactlyAtTheLimit_holds() {
        val h = h()
        h.inSession(X, 1_000)
        h.reel(X, 1_000 + EngineCore.REEL_HOLD_MAX_MS)
        h.advance(1_000)
        assertTrue(h.blocksShown().isEmpty())
        assertTrue(h.session(X)!!.reelHeld)
    }

    @Test fun reelEndsBeforeDeadline_blocksOnTime() {
        val h = h()
        h.inSession(X, 1_000)
        h.reel(X, 400) // it loops before time runs out
        h.advance(1_000)
        assertEquals(1, h.blocksShown(X).size)
    }

    @Test fun ruleWithoutFinishReel_ignoresReports() {
        val h = h()
        h.inSession(Y, 1_000)
        h.reel(Y, 20_000)
        assertNull(h.session(Y)!!.reelSeenAt)
        assertNull(h.core.reelWatch(h.now))
        h.advance(1_000)
        assertEquals(1, h.blocksShown(Y).size)
    }

    @Test fun pauseOnly_neverHolds() {
        val h = h()
        h.inSession(N, 1_000)
        h.reel(N, 20_000)
        assertNull(h.core.reelWatch(h.now))
        h.advance(1_000)
        val s = h.session(N)!!
        assertTrue(s.graceOver)
        assertFalse(s.reelHeld)
        assertTrue(h.blocksShown().isEmpty())
    }

    @Test fun reportWhileAway_isIgnored() {
        val h = h()
        h.inSession(X, 5_000)
        h.home()
        h.reel(X, 20_000)
        assertNull(h.session(X)!!.reelSeenAt)
    }

    // ---- hold and presence ----------------------------------------------------------------

    @Test fun leftDuringHold_endsWhileAway_nextEntryBlocks() {
        val h = h()
        h.inSession(X, 1_000)
        h.reel(X, 20_000)
        h.advance(1_000)
        h.advance(3_000)
        h.home()
        h.advance(30_000)
        assertTrue(h.blocksShown().isEmpty())
        assertNull(h.state(X))
        assertEquals(1, h.usage(UsageKind.GRACE_EXPIRED_WHILE_AWAY, X))
        h.app(X)
        assertEquals(listOf(Effect.ShowBlock(X, BlockKind.INITIAL)), h.blocksShown())
    }

    @Test fun backDuringHold_stillEndsAtTheReelsEnd() {
        val h = h()
        h.inSession(X, 1_000)
        h.reel(X, 20_000)
        h.advance(1_000)
        val end = h.session(X)!!.graceDeadline
        h.home()
        h.advance(4_000)
        h.app(X)
        assertTrue(h.blocksShown().isEmpty())
        h.advance(end - h.now)
        assertEquals(listOf(Effect.ShowBlock(X, BlockKind.RE_INTERVENTION)), h.blocksShown())
    }

    @Test fun longSleepAcrossDeadlineAndHold_blocksOnce() {
        val h = h()
        h.inSession(X, 1_000)
        h.reel(X, 20_000)
        h.sleep(5 * 60_000L)
        h.send(Input.Tick(h.now))
        assertEquals(listOf(Effect.ShowBlock(X, BlockKind.RE_INTERVENTION)), h.blocksShown())
    }

    @Test fun linkPass_heldThenNormalBlock() {
        val h = h()
        h.app(S)
        h.app(link)
        assertEquals(BlockKind.LINK, (h.state(link) as AppState.Blocking).kind)
        h.blockAppears(link)
        h.tapContinue(link, 60_000L)
        h.blockStops(link)
        h.app(link)
        h.clearEffects()
        val deadline = h.session(link)!!.graceDeadline
        h.advance(deadline - 1_000 - h.now)
        h.reel(link, 10_000)
        h.advance(1_000)
        assertTrue(h.blocksShown().isEmpty())
        h.advance(10_000)
        assertEquals(listOf(Effect.ShowBlock(link, BlockKind.INITIAL)), h.blocksShown())
    }

    // ---- R4: when the host should look -----------------------------------------------------

    @Test fun reelWatch_onlyNearTheDeadlineAndDuringTheHold() {
        val h = h()
        val deadline = h.inSession(X, EngineCore.REEL_WATCH_LEAD_MS + 1_000)
        assertNull(h.core.reelWatch(h.now))
        h.advance(1_000) // a wake-up is scheduled for exactly this moment
        assertEquals(X, h.core.reelWatch(h.now))
        h.home()
        assertNull(h.core.reelWatch(h.now))
        h.app(X)
        assertEquals(X, h.core.reelWatch(h.now))
        h.advance(deadline - 1_000 - h.now)
        h.reel(X, 20_000)
        h.advance(1_000)
        assertEquals(X, h.core.reelWatch(h.now)) // held
        h.swipe(X)
        assertNull(h.core.reelWatch(h.now)) // blocked
    }

    @Test fun reelWatch_wakeUpIsScheduled() {
        val h = h()
        val deadline = h.inSession(X, 40_000)
        val wakeUps = ArrayList<Long>()
        while (h.now < deadline - 10_000) {
            val next = h.core.nextWakeUp(h.now)!!
            wakeUps += next
            h.advance(next - h.now)
        }
        assertTrue(wakeUps.contains(deadline - EngineCore.REEL_WATCH_LEAD_MS))
    }

    @Test fun traceCodec_roundTripsReelInputs() {
        val inputs = listOf(Input.ReelProgress(X, 12_345L, 99L), Input.ReelSwiped(X, 100L))
        val lines = inputs.map { TraceCodec.encodeInput(it, 0L) }
        assertEquals(inputs, TraceCodec.decodeInputs(lines.asSequence()))
    }
}
