package com.chillpill.service.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Per-app modes (specs/per-app-modes.md): PICK, per-app grace, "+10 s" switch, rule snapshots. */
class PerAppModesTest {

    private val p = "app.pick"
    private val minute = 60_000L

    private fun config(
        pick: AppRule = AppRule(Mode.PICK, 0L),
        x: AppRule = AppRule(Mode.TIME_LIMIT, GRACE),
        n: AppRule = AppRule(Mode.PAUSE_ONLY, 2 * GRACE),
        default: AppRule = AppRule(Mode.TIME_LIMIT, GRACE)
    ) = Config(mapOf(p to pick, X to x, N to n), default)

    private fun h(config: Config = config()) = EngineHarness(config = config)

    // ---- PICK ----------------------------------------------------------------------------

    @Test fun pick_chosenLength_setsDeadline() {
        val h = h()
        h.app(p); h.blockAppears(p); h.advance(1_000)
        val at = h.now
        h.tapContinue(p, 12 * minute)
        assertEquals(at + 12 * minute, h.session(p)!!.graceDeadline)
    }

    @Test fun pick_lengthIsClampedAndRounded() {
        for ((chosen, expected) in listOf(
            0L to minute, -5L to minute, 89_000L to minute, 91_000L to 2 * minute,
            90 * minute to 90 * minute, 91 * minute to 90 * minute, 600 * minute to 90 * minute
        )) {
            val h = h()
            h.app(p); h.blockAppears(p)
            val at = h.now
            h.tapContinue(p, chosen)
            assertEquals("chosen=$chosen", at + expected, h.session(p)!!.graceDeadline)
        }
    }

    @Test fun pick_withoutChoice_getsFallback() {
        val h = h()
        h.app(p); h.blockAppears(p)
        val at = h.now
        h.tapContinue(p)
        assertEquals(at + EngineCore.PICK_FALLBACK_MS, h.session(p)!!.graceDeadline)
    }

    @Test fun pick_chosenMsIgnoredForOtherModes() {
        val h = h()
        h.app(X); h.blockAppears(X)
        val at = h.now
        h.tapContinue(X, 30 * minute)
        assertEquals(at + GRACE, h.session(X)!!.graceDeadline)
    }

    @Test fun pick_expiresLikeTimeLimit_thenAnotherRound() {
        val h = h()
        h.enterWithContinue(p, 2 * minute)
        h.advance(2 * minute)
        assertEquals(Effect.ShowBlock(p, BlockKind.RE_INTERVENTION), h.blocksShown(p).last())
        assertEquals(1, h.usage(UsageKind.GRACE_EXPIRED_WHILE_ACTIVE, p))
        // after the times-up wait the user picks another round
        h.blockAppears(p)
        h.advance(30_000)
        val at = h.now
        h.tapContinue(p, 5 * minute)
        h.blockStops(p); h.app(p)
        assertEquals(at + 5 * minute, h.session(p)!!.graceDeadline)
        assertNull(h.session(p)!!.leftAt)
    }

    @Test fun pick_hasWarningPill() {
        val h = h()
        h.enterWithContinue(p, minute)
        h.advance(h.session(p)!!.graceDeadline - h.now - 8_000)
        assertTrue(h.warning()!!.canExtend)
    }

    // ---- per-app grace / PAUSE_ONLY ----------------------------------------------------------

    @Test fun perAppGrace_isUsed() {
        val h = h()
        h.app(N); h.blockAppears(N)
        val at = h.now
        h.tapContinue(N)
        assertEquals(at + 2 * GRACE, h.session(N)!!.graceDeadline)
    }

    @Test fun pauseOnly_noWarning_noReblock() {
        val h = h()
        h.enterWithContinue(N)
        h.advance(2 * GRACE - 3_000)
        assertNull(h.warning())
        h.advance(10_000)
        assertTrue(h.session(N)!!.graceOver)
        assertEquals(1, h.blocksShown(N).size)
    }

    // ---- "+10 s" switch ----------------------------------------------------------------------

    @Test fun extensionOff_pillWithoutExtend_andExtendIgnored() {
        val h = h(config(x = AppRule(Mode.TIME_LIMIT, GRACE, extensionAllowed = false)))
        h.enterWithContinue(X)
        val deadline = h.session(X)!!.graceDeadline
        h.advance(deadline - h.now - 8_000)
        val w = h.warning()!!
        assertFalse(w.canExtend)
        assertTrue(w.dismissible)
        h.extend(X)
        assertEquals(deadline, h.session(X)!!.graceDeadline)
        h.advance(8_000)
        assertEquals(BlockKind.RE_INTERVENTION, h.blocksShown(X).last().kind)
    }

    // ---- rule snapshot -----------------------------------------------------------------------

    @Test fun modeChangeMidSession_appliesToNextSession() {
        val h = h()
        h.enterWithContinue(X) // TIME_LIMIT
        h.send(Input.ConfigChanged(config(x = AppRule(Mode.PAUSE_ONLY, GRACE)), h.now))
        assertEquals(Mode.TIME_LIMIT, h.session(X)!!.rule.mode)
        h.advance(GRACE)
        // still the old rule: re-intervention, not a free overstay
        assertEquals(BlockKind.RE_INTERVENTION, h.blocksShown(X).last().kind)
        h.blockAppears(X)
        h.tapContinue(X)
        assertEquals(Mode.PAUSE_ONLY, h.session(X)!!.rule.mode)
    }

    @Test fun pauseOnlyBecomesTimeLimit_runningOverstayKeepsGoing() {
        val h = h()
        h.enterWithContinue(N)
        h.advance(2 * GRACE + 1_000)
        h.send(Input.ConfigChanged(config(n = AppRule(Mode.TIME_LIMIT, GRACE)), h.now))
        h.advance(60_000)
        assertEquals(1, h.blocksShown(N).size)
        h.home(); h.advance(11_000); h.app(N)
        assertEquals(2, h.blocksShown(N).size) // next entry: blocked, new rule from here on
    }

    // ---- suggestion "Restrict" with the defaults ---------------------------------------------

    @Test fun suggestionRestrict_pickDefaults_getsFallback() {
        val h = h(config(default = AppRule(Mode.PICK, 0L)))
        h.app(S)
        h.send(Input.SuggestionEligible(S, "Suggest", h.now))
        val at = h.now
        h.send(Input.SuggestionAnswered(S, SuggestionAnswer.RESTRICT, h.now))
        val s = h.session(S)
        assertNotNull(s)
        assertEquals(at + EngineCore.PICK_FALLBACK_MS, s!!.graceDeadline)
        assertEquals(Mode.PICK, s.rule.mode)
    }

    @Test fun suggestionRestrict_pauseOnlyDefaults_neverReblocks() {
        val h = h(config(default = AppRule(Mode.PAUSE_ONLY, GRACE)))
        h.app(S)
        h.send(Input.SuggestionEligible(S, "Suggest", h.now))
        h.send(Input.SuggestionAnswered(S, SuggestionAnswer.RESTRICT, h.now))
        h.advance(GRACE + 5_000)
        assertTrue(h.blocksShown(S).isEmpty())
        assertTrue(h.session(S)!!.graceOver)
    }
}
