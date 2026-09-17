package com.chillpill.service

import com.chillpill.service.SessionPolicy.Decision.ALLOW
import com.chillpill.service.SessionPolicy.Decision.BLOCK
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class SessionPolicyTest {
    private var now = 1_000_000L
    private lateinit var p: SessionPolicy
    private val grace = 5 * 60_000L
    private val x = "app.x"
    private val y = "app.y"

    private fun advance(ms: Long) { now += ms }

    @Before fun setUp() { p = SessionPolicy({ now }, { now }) }

    @Test fun neverContinued_blocks() {
        assertEquals(BLOCK, p.onEnterFromElsewhere(x, true))
    }

    @Test fun withinGrace_allowsAnyApp_evenAfterLongExit() {
        p.startSession(x, grace)
        advance(60_000); p.onLeft(x); advance(120_000)
        assertEquals(ALLOW, p.onEnterFromElsewhere(x, false))
    }

    @Test fun leavingDoesNotExtendGrace() {
        p.startSession(x, grace)
        advance(4 * 60_000L); p.onLeft(x); advance(2 * 60_000L)
        assertEquals(false, p.isInGracePeriod(x))
        assertEquals(BLOCK, p.onEnterFromElsewhere(x, false))
    }

    /** F1: overstayed no-re-block app, real exit, reopen minutes later -> block. */
    @Test fun noReblock_overstay_thenLongExit_blocks() {
        p.startSession(x, grace)
        advance(7 * 60_000L); p.clearGrace(x)
        p.onLeft(x); advance(3 * 60_000L)
        assertEquals(BLOCK, p.onEnterFromElsewhere(x, true))
    }

    @Test fun noReblock_overstay_exit9s_allows_repeatedly() {
        p.startSession(x, grace)
        advance(7 * 60_000L); p.clearGrace(x)
        repeat(3) {
            p.onLeft(x); advance(9_000)
            assertEquals(ALLOW, p.onEnterFromElsewhere(x, true))
            advance(60_000)
        }
    }

    @Test fun noReblock_overstay_exit11s_blocks() {
        p.startSession(x, grace)
        advance(7 * 60_000L); p.clearGrace(x)
        p.onLeft(x); advance(11_000)
        assertEquals(BLOCK, p.onEnterFromElsewhere(x, true))
    }

    @Test fun noReblock_shortExitAcrossGraceExpiry_allows() {
        p.startSession(x, grace)
        advance(grace - 5_000); p.onLeft(x)
        advance(8_000); p.clearGrace(x)
        assertEquals(ALLOW, p.onEnterFromElsewhere(x, true))
    }

    @Test fun normalApp_afterExpiry_shortExit_blocks() {
        p.startSession(x, grace)
        advance(grace + 1); p.clearGrace(x)
        p.onLeft(x); advance(5_000)
        assertEquals(BLOCK, p.onEnterFromElsewhere(x, false))
    }

    /** Hopping between other apps must not reset the exit timestamp. */
    @Test fun repeatedOnLeft_keepsEarliestExit() {
        p.startSession(x, grace)
        advance(grace + 1)
        p.onLeft(x); advance(7_000); p.onLeft(x); advance(5_000)
        assertEquals(BLOCK, p.onEnterFromElsewhere(x, true))
    }

    /** F3: block shown, Home, reopen -> block again, whatever events arrived in between. */
    @Test fun homeFromBlock_thenReopen_blocks() {
        assertEquals(BLOCK, p.onEnterFromElsewhere(x, true))
        p.onLeft(x); advance(2_000)
        assertEquals(BLOCK, p.onEnterFromElsewhere(x, true))
    }

    @Test fun afterBlock_oldSessionDoesNotLeak() {
        p.startSession(x, grace)
        advance(grace + 1); p.onLeft(x); advance(60_000)
        assertEquals(BLOCK, p.onEnterFromElsewhere(x, true))
        p.onLeft(x); advance(1_000)
        assertEquals(BLOCK, p.onEnterFromElsewhere(x, true))
    }

    @Test fun twoApps_areIndependent() {
        p.startSession(x, grace); advance(1_000); p.onLeft(x)
        assertEquals(BLOCK, p.onEnterFromElsewhere(y, true))
        p.startSession(y, grace)
        advance(grace + 1); p.onLeft(y); advance(30_000)
        assertEquals(BLOCK, p.onEnterFromElsewhere(x, true))
        advance(1_000)
        assertEquals(BLOCK, p.onEnterFromElsewhere(y, true))
    }

    @Test fun endSession_onReIntervention_clearsEverything() {
        p.startSession(x, grace); p.endSession(x); p.onLeft(x)
        assertEquals(BLOCK, p.onEnterFromElsewhere(x, true))
    }

    @Test fun hasActiveSession_tracksStartAndEnd_notGraceExpiry() {
        assertEquals(false, p.hasActiveSession(x))
        p.startSession(x, grace)
        assertEquals(true, p.hasActiveSession(x))
        p.clearGrace(x)
        assertEquals(true, p.hasActiveSession(x))
        p.endSession(x)
        assertEquals(false, p.hasActiveSession(x))
    }

    @Test fun graceExpiredWhileAway_sessionEnded_shortReturnStillBlocks() {
        // GracePeriodService ends the session when grace runs out while the user is away; toggling
        // "Block again after grace" off afterwards must not revive it.
        p.startSession(x, grace); p.onLeft(x)
        advance(grace + 1); p.clearGrace(x); p.endSession(x)
        assertEquals(BLOCK, p.onEnterFromElsewhere(x, true))
    }
}
