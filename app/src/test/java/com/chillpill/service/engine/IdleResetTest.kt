package com.chillpill.service.engine

import com.chillpill.service.engine.EngineCore.Companion.IDLE_RESET_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G4: two minutes away from an app in a row ends its session, so whatever grace, return window or
 * link pass was left no longer lets the user back in without the block.
 */
class IdleResetTest {

    private val minute = 60_000L
    private val chat = "app.chat"

    private fun h(
        x: AppRule = AppRule(Mode.TIME_LIMIT, 10 * minute),
        n: AppRule = AppRule(Mode.PAUSE_ONLY, 10 * minute)
    ) = EngineHarness(
        config = Config(
            mapOf(X to x, N to n, Y to AppRule(Mode.TIME_LIMIT, 10 * minute)),
            AppRule(Mode.TIME_LIMIT, GRACE)
        )
    )

    @Test fun awayJustUnderTwoMinutes_stillAllowed() {
        val h = h()
        h.enterWithContinue(X)
        h.advance(30_000); h.home()
        h.advance(IDLE_RESET_MS - 1)
        assertNotNull(h.session(X))
        h.app(X)
        assertEquals(1, h.blocksShown(X).size)
        assertNull(h.session(X)!!.leftAt)
    }

    @Test fun awayTwoMinutes_timeLimit_blocksOnReturn() {
        val h = h()
        h.enterWithContinue(X)
        h.advance(30_000); h.home()
        h.advance(IDLE_RESET_MS)
        assertNull(h.state(X))
        assertEquals(1, h.usage(UsageKind.GRACE_EXPIRED_WHILE_AWAY, X))
        h.app(X)
        assertEquals(listOf(BlockKind.INITIAL, BlockKind.INITIAL), h.blocksShown(X).map { it.kind })
    }

    @Test fun awayTwoMinutes_pauseOnly_blocksOnReturn() {
        val h = h()
        h.enterWithContinue(N)
        h.advance(30_000); h.app(Y)
        h.advance(IDLE_RESET_MS)
        assertNull(h.state(N))
        h.app(N)
        assertEquals(2, h.blocksShown(N).size)
    }

    @Test fun shortAbsences_doNotAddUp() {
        val h = h()
        h.enterWithContinue(X)
        repeat(3) {
            h.home(); h.advance(90_000)
            h.app(X); h.advance(5_000)
        }
        assertNotNull(h.session(X))
        assertEquals(1, h.blocksShown(X).size)
    }

    @Test fun screenOffTwoMinutes_countsAsAway() {
        val h = h()
        h.enterWithContinue(X)
        h.advance(30_000)
        h.screenOff()
        h.advance(IDLE_RESET_MS)
        assertNull(h.state(X))
        h.screenOn(locked = false)
        h.app(X)
        assertEquals(2, h.blocksShown(X).size)
    }

    @Test fun cpuAsleep_longGap_resetsOnce() {
        val h = h()
        h.enterWithContinue(X)
        h.advance(30_000); h.home()
        h.sleep(30 * minute) // no ticks: deadline and idle reset are both overdue
        h.app(X)
        assertEquals(1, h.usage(UsageKind.GRACE_EXPIRED_WHILE_AWAY, X))
        assertEquals(2, h.blocksShown(X).size)
    }

    @Test fun deadlineBeforeIdleReset_expiresNormally() {
        val h = h(x = AppRule(Mode.TIME_LIMIT, GRACE))
        h.enterWithContinue(X)
        h.advance(GRACE - 10_000); h.home()
        h.advance(IDLE_RESET_MS)
        assertNull(h.state(X))
        assertEquals(1, h.usage(UsageKind.GRACE_EXPIRED_WHILE_AWAY, X))
    }

    @Test fun linkPass_awayTwoMinutes_ends() {
        val h = h(x = AppRule(Mode.TIME_LIMIT, 10 * minute, linkPassMs = 10 * minute))
        h.app(chat); h.advance(3_000); h.app(X)
        h.blockAppears(X); h.advance(1_000)
        h.tapContinue(X, 10 * minute)
        h.blockStops(X); h.app(X); h.blockClosed(X, CloseReason.SYSTEM_GESTURE)
        assertTrue(h.session(X)!!.linkPass)
        h.advance(10_000); h.home()
        h.advance(IDLE_RESET_MS)
        assertNull(h.state(X))
        h.app(X) // from home: the normal block
        assertEquals(BlockKind.INITIAL, h.blocksShown(X).last().kind)
    }

    @Test fun otherAppsSessions_areIndependent() {
        val h = h()
        h.enterWithContinue(X)
        h.advance(10_000)
        h.enterWithContinue(Y) // X left here
        h.advance(IDLE_RESET_MS)
        assertNull(h.state(X))
        assertNotNull(h.session(Y)) // still in Y
    }
}
