package com.chillpill.ui.settings

import com.chillpill.data.restricted.AppPolicy
import com.chillpill.service.engine.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestrictionReducingTest {

    private val limit = AppPolicy.DEFAULT
    private val pick = limit.copy(mode = Mode.PICK)
    private val pause = limit.copy(mode = Mode.PAUSE_ONLY)

    private fun reducing(
        restricted: Set<String> = setOf("a", "b"),
        overrides: Map<String, AppPolicy> = mapOf("b" to pause),
        confirmation: Int = 10
    ) = isRestrictionReducing(
        originalRestricted = setOf("a", "b"),
        draftRestricted = restricted,
        originalOverrides = mapOf("b" to pause),
        draftOverrides = overrides,
        originalConfirmationWaitSeconds = 10,
        draftConfirmationWaitSeconds = confirmation
    )

    // ---- whole draft -------------------------------------------------------------------------

    @Test fun noChange_isNotReducing() = assertFalse(reducing())
    @Test fun addingApp_isNotReducing() = assertFalse(reducing(restricted = setOf("a", "b", "c")))
    @Test fun removingApp_isReducing() = assertTrue(reducing(restricted = setOf("a")))
    @Test fun swappingApps_isReducing() = assertTrue(reducing(restricted = setOf("a", "c")))
    @Test fun confirmationWaitDown_isReducing() = assertTrue(reducing(confirmation = 5))
    @Test fun confirmationWaitUp_isNotReducing() = assertFalse(reducing(confirmation = 30))

    @Test fun appWithoutOwnPolicy_comparedAgainstDefault() {
        assertFalse(reducing(overrides = mapOf("a" to limit, "b" to pause))) // "a" explicit = default
        assertTrue(reducing(overrides = mapOf("a" to limit.copy(minutes = 10), "b" to pause)))
        assertFalse(reducing(overrides = mapOf("a" to limit.copy(minutes = 2), "b" to pause)))
    }

    @Test fun pauseOnlyBackToDefault_isNotReducing() =
        assertFalse(reducing(overrides = emptyMap())) // b: PAUSE_ONLY -> TIME_LIMIT

    @Test fun defaultToPauseOnly_isReducing() =
        assertTrue(isRestrictionReducing(setOf("a"), setOf("a"), emptyMap(), mapOf("a" to pause), 10, 10))

    // ---- one policy --------------------------------------------------------------------------

    @Test fun openWait() {
        assertTrue(isPolicyLooser(limit, limit.copy(openWaitSeconds = 9)))
        assertFalse(isPolicyLooser(limit, limit.copy(openWaitSeconds = 13)))
        assertTrue(isPolicyLooser(pick, pick.copy(openWaitSeconds = 0)))
    }

    @Test fun timesUpWait_onlyCountsWhenStopped() {
        assertTrue(isPolicyLooser(limit, limit.copy(timesUpWaitSeconds = 5)))
        assertTrue(isPolicyLooser(pick, pick.copy(timesUpWaitSeconds = 5)))
        assertFalse(isPolicyLooser(pause, pause.copy(timesUpWaitSeconds = 5)))
    }

    @Test fun minutes() {
        assertTrue(isPolicyLooser(limit, limit.copy(minutes = 6)))
        assertFalse(isPolicyLooser(limit, limit.copy(minutes = 4)))
        assertTrue(isPolicyLooser(pause, pause.copy(minutes = 6)))
        assertFalse(isPolicyLooser(pick, pick.copy(minutes = 600))) // unused by PICK
    }

    @Test fun extension() {
        assertTrue(isPolicyLooser(limit.copy(extensionAllowed = false), limit))
        assertFalse(isPolicyLooser(limit, limit.copy(extensionAllowed = false)))
    }

    @Test fun modes() {
        assertTrue(isPolicyLooser(limit, pause))
        assertTrue(isPolicyLooser(pick, pause))
        assertFalse(isPolicyLooser(pause, limit))
        assertFalse(isPolicyLooser(pause, pick))
        assertTrue(isPolicyLooser(limit, pick)) // 5 min -> up to 90
        assertFalse(isPolicyLooser(limit.copy(minutes = 90), pick))
        assertFalse(isPolicyLooser(pick, limit.copy(minutes = 90)))
        assertTrue(isPolicyLooser(pick, limit.copy(minutes = 91)))
    }

    // ---- draft -------------------------------------------------------------------------------

    @Test fun draft_invalidFieldsFallBack() {
        val d = PolicyDraft.of(limit).withText(PolicyField.OPEN_WAIT, "").withText(PolicyField.MINUTES, "99999")
        assertEquals(limit, d.toPolicy(limit))
        assertEquals(limit.copy(openWaitSeconds = 30), PolicyDraft.of(limit).withText(PolicyField.OPEN_WAIT, "30").toPolicy(limit))
    }

    @Test fun draft_zeroWaitOnlyInPick() {
        val d = PolicyDraft.of(pick).withText(PolicyField.OPEN_WAIT, "0")
        assertTrue(d.isValid(PolicyField.OPEN_WAIT))
        assertEquals(0, d.toPolicy(pick).openWaitSeconds)
        val asLimit = d.withMode(Mode.TIME_LIMIT)
        assertEquals("1", asLimit.openWait)
        assertFalse(PolicyDraft.of(limit).withText(PolicyField.OPEN_WAIT, "0").isValid(PolicyField.OPEN_WAIT))
    }
}
