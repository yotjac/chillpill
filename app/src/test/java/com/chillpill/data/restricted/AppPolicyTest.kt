package com.chillpill.data.restricted

import com.chillpill.service.engine.AppRule
import com.chillpill.service.engine.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppPolicyTest {

    // ---- codec -------------------------------------------------------------------------------

    @Test fun codec_roundTrip() {
        val p = AppPolicy(Mode.PICK, openWaitSeconds = 0, timesUpWaitSeconds = 45, minutes = 3, extensionAllowed = false)
        assertEquals(p, AppPolicyCodec.decode(AppPolicyCodec.encode(p)))
        val map = mapOf("a" to p, "b" to AppPolicy(Mode.PAUSE_ONLY))
        assertEquals(map, AppPolicyCodec.decodeMap(AppPolicyCodec.encodeMap(map)))
    }

    @Test fun codec_toleratesGarbageAndMissingFields() {
        assertNull(AppPolicyCodec.decode(null))
        assertNull(AppPolicyCodec.decode("not json"))
        assertNull(AppPolicyCodec.decodeMap("[1,2]"))
        assertEquals(AppPolicy(), AppPolicyCodec.decode("{}"))
        assertEquals(AppPolicy(mode = Mode.TIME_LIMIT), AppPolicyCodec.decode("""{"mode":"SOMETHING_NEW"}"""))
        assertEquals(mapOf("a" to AppPolicy()), AppPolicyCodec.decodeMap("""{"a":{},"b":3}"""))
    }

    @Test fun sanitized_zeroWaitOnlyForPick() {
        assertEquals(0, AppPolicy(Mode.PICK, openWaitSeconds = 0).sanitized().openWaitSeconds)
        assertEquals(1, AppPolicy(Mode.TIME_LIMIT, openWaitSeconds = 0).sanitized().openWaitSeconds)
        assertEquals(1, AppPolicy(Mode.PAUSE_ONLY, openWaitSeconds = 0).sanitized().openWaitSeconds)
        assertEquals(1, AppPolicy(Mode.PICK, timesUpWaitSeconds = 0).sanitized().timesUpWaitSeconds)
        assertEquals(1440, AppPolicy(minutes = 99_999).sanitized().minutes)
        assertEquals(1, AppPolicyCodec.decode("""{"mode":"TIME_LIMIT","openWait":0}""")!!.openWaitSeconds)
    }

    @Test fun toRule() {
        assertEquals(AppRule(Mode.TIME_LIMIT, 7 * 60_000L, false), AppPolicy(minutes = 7, extensionAllowed = false).toRule())
    }

    // ---- default + legacy resolution ---------------------------------------------------------

    @Test fun default_isTimeLimit10s5min() {
        assertEquals(AppPolicy(Mode.TIME_LIMIT, 10, 10, 5, true), AppPolicy.DEFAULT)
        val r = Restrictions.from(setOf("a"), null, null, null, emptySet())
        assertEquals(emptyMap<String, AppPolicy>(), r.overrides)
        assertEquals(AppPolicy.DEFAULT, r.policyFor("a"))
        assertEquals(AppRule(Mode.TIME_LIMIT, 5 * 60_000L, true), r.config().defaultRule)
    }

    @Test fun legacy_everyAppKeepsOldWaitAndGrace_reInterventionOffBecomesPauseOnly() {
        val r = Restrictions.from(setOf("a", "b"), null, 20, 7, setOf("b", "gone"))
        val old = AppPolicy(Mode.TIME_LIMIT, 20, 20, 7, true)
        assertEquals(mapOf("a" to old, "b" to old.copy(mode = Mode.PAUSE_ONLY)), r.overrides)
        assertEquals(com.chillpill.service.engine.Config(
            rules = mapOf("a" to old.toRule(), "b" to old.copy(mode = Mode.PAUSE_ONLY).toRule()),
            defaultRule = AppPolicy.DEFAULT.toRule()
        ), r.config())
    }

    @Test fun legacy_onlyDisabledSetStored_usesOldDefaults12s() {
        val r = Restrictions.from(setOf("a", "b"), null, null, null, setOf("b"))
        assertEquals(AppPolicy(Mode.TIME_LIMIT, 12, 12, 5), r.policyFor("a"))
        assertEquals(Mode.PAUSE_ONLY, r.policyFor("b").mode)
    }

    @Test fun storedOverrides_winOverLegacy_onlyRestricted_andDefaultEqualDropped() {
        val pick = AppPolicy(Mode.PICK, 0)
        val r = Restrictions.from(setOf("a", "c"), mapOf("a" to pick, "removed" to pick, "c" to AppPolicy.DEFAULT), 20, 7, setOf("a"))
        assertEquals(mapOf("a" to pick), r.overrides)
        val none = Restrictions.from(setOf("a"), emptyMap(), 20, 7, setOf("a"))
        assertEquals(AppPolicy.DEFAULT, none.policyFor("a")) // saved once: legacy no longer applies
    }

    @Test fun confirmationWait_upgradeKeepsOldWait_freshInstallGets10() {
        assertEquals(20, PolicyResolver.confirmationWaitSeconds(null, legacyWaitSeconds = 20, setupCompleted = true))
        assertEquals(12, PolicyResolver.confirmationWaitSeconds(null, null, setupCompleted = true))
        assertEquals(10, PolicyResolver.confirmationWaitSeconds(null, null, setupCompleted = false))
        assertEquals(3, PolicyResolver.confirmationWaitSeconds(3, legacyWaitSeconds = 20, setupCompleted = true))
    }
}
