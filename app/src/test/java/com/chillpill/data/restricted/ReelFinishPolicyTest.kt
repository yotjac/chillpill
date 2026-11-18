package com.chillpill.data.restricted

import com.chillpill.service.engine.Mode
import com.chillpill.service.engine.ReelApps
import com.chillpill.ui.settings.PolicyDraft
import com.chillpill.ui.settings.isPolicyLooser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Let the current reel finish" in the policy layer (specs/reel-finish.md). */
class ReelFinishPolicyTest {

    @Test fun offByDefault_andOldStoredPoliciesDecodeAsOff() {
        assertFalse(AppPolicy.DEFAULT.finishReel)
        val old = """{"mode":"TIME_LIMIT","openWait":10,"timesUpWait":10,"minutes":5,"extension":true}"""
        assertFalse(AppPolicyCodec.decode(old)!!.finishReel)
    }

    @Test fun codecRoundTrip() {
        val p = AppPolicy(finishReel = true)
        assertEquals(p, AppPolicyCodec.decode(AppPolicyCodec.encode(p)))
        assertEquals(mapOf("a" to p), AppPolicyCodec.decodeMap(AppPolicyCodec.encodeMap(mapOf("a" to p))))
    }

    @Test fun ruleCarriesIt_exceptForPauseOnly() {
        assertTrue(AppPolicy(finishReel = true).toRule().finishReel)
        assertTrue(AppPolicy(mode = Mode.PICK, finishReel = true).toRule().finishReel)
        assertFalse(AppPolicy(mode = Mode.PAUSE_ONLY, finishReel = true).toRule().finishReel)
        assertFalse(AppPolicy().toRule().finishReel)
    }

    @Test fun configOnlyKeepsItForAppsWithAKnownReelViewer() {
        val on = AppPolicy(finishReel = true)
        val config = PolicyResolver.config(
            setOf(ReelApps.INSTAGRAM, "app.other"),
            mapOf(ReelApps.INSTAGRAM to on, "app.other" to on)
        )
        assertTrue(config.rules.getValue(ReelApps.INSTAGRAM).finishReel)
        assertFalse(config.rules.getValue("app.other").finishReel)
        assertFalse(config.defaultRule.finishReel)
    }

    @Test fun draftRoundTrip() {
        val p = AppPolicy(finishReel = true)
        assertEquals(p, PolicyDraft.of(p).toPolicy(AppPolicy.DEFAULT))
        assertEquals(p, PolicyDraft.of(AppPolicy.DEFAULT).copy(finishReel = true).toPolicy(AppPolicy.DEFAULT))
    }

    @Test fun switchingItNeverNeedsTheConfirmationWait() {
        assertFalse(isPolicyLooser(AppPolicy(), AppPolicy(finishReel = true)))
        assertFalse(isPolicyLooser(AppPolicy(finishReel = true), AppPolicy()))
        // Other loosenings still count when it is switched in the same save.
        assertTrue(isPolicyLooser(AppPolicy(minutes = 5), AppPolicy(minutes = 6, finishReel = true)))
    }
}
