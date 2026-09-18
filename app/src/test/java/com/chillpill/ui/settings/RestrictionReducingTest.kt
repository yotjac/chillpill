package com.chillpill.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestrictionReducingTest {

    private fun reducing(
        wait: Int = 12,
        grace: Int = 5,
        restricted: Set<String> = setOf("a", "b")
    ) = isRestrictionReducing(
        originalWaitSeconds = 12,
        draftWaitSeconds = wait,
        originalGraceMinutes = 5,
        draftGraceMinutes = grace,
        originalRestricted = setOf("a", "b"),
        draftRestricted = restricted
    )

    @Test fun unchanged_isNotReducing() = assertFalse(reducing())
    @Test fun longerWait_isNotReducing() = assertFalse(reducing(wait = 30))
    @Test fun shorterGrace_isNotReducing() = assertFalse(reducing(grace = 2))
    @Test fun addedApp_isNotReducing() = assertFalse(reducing(restricted = setOf("a", "b", "c")))
    @Test fun stricterEverything_isNotReducing() =
        assertFalse(reducing(wait = 60, grace = 1, restricted = setOf("a", "b", "c")))

    @Test fun shorterWait_isReducing() = assertTrue(reducing(wait = 11))
    @Test fun longerGrace_isReducing() = assertTrue(reducing(grace = 6))
    @Test fun removedApp_isReducing() = assertTrue(reducing(restricted = setOf("a")))
    @Test fun swappedApp_isReducing() = assertTrue(reducing(restricted = setOf("a", "c")))
    @Test fun stricterWaitButRemovedApp_isReducing() =
        assertTrue(reducing(wait = 60, restricted = setOf("b")))
}
