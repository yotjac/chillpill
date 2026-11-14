package com.chillpill.service.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Link pass (specs/link-pass.md): opened from another app → a picker with no wait ("how long?",
 * up to the app's limit), then the visit, then the normal block.
 */
class LinkPassTest {

    private val chat = "app.chat"   // not restricted: where the link was tapped
    private val minute = 60_000L
    private val limit = 10 * minute

    private fun h(
        x: AppRule = AppRule(Mode.TIME_LIMIT, GRACE, linkPassMs = limit),
        n: AppRule = AppRule(Mode.PAUSE_ONLY, GRACE, linkPassMs = limit)
    ) = EngineHarness(config = Config(mapOf(X to x, N to n, Y to AppRule(Mode.TIME_LIMIT, GRACE)), AppRule(Mode.TIME_LIMIT, GRACE)))

    /** In [chat], then [pkg]'s activity comes up straight over it. */
    private fun EngineHarness.linkFromChat(pkg: String) {
        app(chat)
        advance(3_000)
        app(pkg)
    }

    /** Link → picker → pick [minutes] → back in the app. */
    private fun EngineHarness.linkVisit(pkg: String, minutes: Long) {
        linkFromChat(pkg)
        blockAppears(pkg)
        advance(1_000)
        tapContinue(pkg, minutes * minute)
        blockStops(pkg)
        app(pkg)
        blockClosed(pkg, CloseReason.SYSTEM_GESTURE)
    }

    private fun EngineHarness.blocking(pkg: String) = state(pkg) as? AppState.Blocking
    private fun EngineHarness.kinds(pkg: String) = blocksShown(pkg).map { it.kind }

    @Test fun fromAnotherApp_showsLinkPicker_notTheBlock() {
        val h = h()
        h.linkFromChat(X)
        assertEquals(listOf(BlockKind.LINK), h.kinds(X))
        assertEquals(BlockKind.LINK, h.blocking(X)!!.kind)
        assertEquals(0, h.usage(UsageKind.OPEN_ATTEMPT, X))
        assertEquals(0, h.usage(UsageKind.LINK_PASS, X)) // only once the user picks
    }

    @Test fun pickedLength_startsLinkSession() {
        val h = h()
        h.linkFromChat(X)
        h.blockAppears(X)
        val at = h.now
        h.tapContinue(X, 4 * minute)
        val s = h.session(X)!!
        assertTrue(s.linkPass)
        assertEquals(at + 4 * minute, s.graceDeadline)
        assertEquals(1, h.usage(UsageKind.LINK_PASS, X))
        assertEquals(0, h.usage(UsageKind.WAIT_COMPLETED, X))
    }

    @Test fun pickedLength_isClampedToTheLimit_andRounded() {
        for ((chosen, expected) in listOf(
            null to limit, 0L to minute, 89_000L to minute, 91_000L to 2 * minute,
            limit to limit, 11 * minute to limit, 90 * minute to limit
        )) {
            val h = h()
            h.linkFromChat(X); h.blockAppears(X)
            val at = h.now
            h.tapContinue(X, chosen)
            assertEquals("chosen=$chosen", at + expected, h.session(X)!!.graceDeadline)
        }
    }

    @Test fun limitTurnedOffWhilePickerShows_givesOneMinute() {
        val h = h()
        h.linkFromChat(X); h.blockAppears(X)
        h.send(Input.ConfigChanged(Config(mapOf(X to AppRule(Mode.TIME_LIMIT, GRACE)), AppRule(Mode.TIME_LIMIT, GRACE)), h.now))
        val at = h.now
        h.tapContinue(X, 8 * minute)
        assertEquals(at + minute, h.session(X)!!.graceDeadline)
    }

    @Test fun offByDefault_blocks() {
        val h = h()
        h.linkFromChat(Y)
        assertEquals(listOf(BlockKind.INITIAL), h.kinds(Y))
    }

    @Test fun fromHome_blocks() {
        val h = h()
        h.home(); h.advance(3_000); h.app(X)
        assertEquals(listOf(BlockKind.INITIAL), h.kinds(X))
    }

    @Test fun afterShade_blocks() {
        // A notification tapped in the shade while chat was in front.
        val h = h()
        h.app(chat); h.advance(2_000); h.shade(); h.advance(1_000); h.app(X)
        assertEquals(listOf(BlockKind.INITIAL), h.kinds(X))
    }

    @Test fun shadeBeforeTheSourceApp_doesNotCount() {
        val h = h()
        h.home(); h.shade(); h.advance(1_000)
        h.linkFromChat(X)
        assertEquals(BlockKind.LINK, h.blocking(X)!!.kind)
    }

    @Test fun systemUiThenMoreOfTheSourceApp_stillALink() {
        // s7: Pixel's back-gesture panel (a SystemUI window) while moving around in WhatsApp,
        // then a link: the user went on inside WhatsApp after it, so it was no notification tap.
        val h = h()
        h.app(chat); h.advance(1_000); h.shade(); h.advance(500)
        h.window(chat, className = "com.whatsapp.home.ui.HomeActivity")
        h.advance(3_000)
        h.app(X)
        assertEquals(BlockKind.LINK, h.blocking(X)!!.kind)
    }

    @Test fun fromOwnUi_orScreenOff_blocks() {
        val h = h()
        h.window(OWN, WindowKind.OWN_MAIN_UI, className = "com.chillpill.MainActivity")
        h.app(X)
        assertEquals(listOf(BlockKind.INITIAL), h.kinds(X))

        val h2 = h()
        h2.app(chat); h2.screenOff(); h2.advance(1_000); h2.screenOn(locked = false); h2.app(X)
        assertEquals(listOf(BlockKind.INITIAL), h2.kinds(X))
    }

    @Test fun fromProbe_blocks() {
        val h = h()
        h.app(chat)
        h.advance(2_000)
        h.truth = ProbeResult(X, WindowKind.APP, resumedAt = h.now)
        h.screenOn(locked = false) // probe burst sees X resumed after chat
        h.advance(2_000)
        assertEquals(listOf(BlockKind.INITIAL), h.kinds(X))
    }

    @Test fun pickerGoHome_endsIt_nextLinkShowsPickerAgain() {
        val h = h()
        h.linkFromChat(X); h.blockAppears(X)
        h.blockClosed(X, CloseReason.GO_HOME)
        assertNull(h.state(X))
        assertEquals(1, h.usage(UsageKind.LEFT_APP, X))
        h.home(); h.advance(1_000)
        h.clearEffects()
        h.linkFromChat(X)
        assertEquals(listOf(BlockKind.LINK), h.kinds(X))
    }

    @Test fun gettingPastThePicker_bringsItBack() {
        // E4 keeps the kind: the picker, not the normal block, comes back.
        val h = h()
        h.linkFromChat(X); h.blockAppears(X)
        h.advance(EngineCore.ABSORB_MS + 500)
        h.blockStops(X)
        h.app(X)
        assertEquals(BlockKind.LINK, h.blocksShown(X).last().kind)
    }

    @Test fun returnDuringVisit_isFree_afterItBlocks() {
        val h = h()
        h.linkVisit(X, 2)
        h.advance(30_000)
        h.app(chat)
        h.advance(30_000)
        h.clearEffects()
        h.app(X)
        assertTrue(h.blocksShown(X).isEmpty())
        assertNotNull(h.session(X))
        h.app(chat)
        h.advance(2 * minute) // runs out away: idle
        assertNull(h.state(X))
        assertEquals(1, h.usage(UsageKind.GRACE_EXPIRED_WHILE_AWAY, X))
        h.home(); h.advance(1_000); h.app(X)
        assertEquals(listOf(BlockKind.INITIAL), h.kinds(X))
    }

    @Test fun expiryInFront_showsNormalBlock_withWarningFirst() {
        val h = h()
        h.linkVisit(X, 2)
        h.clearEffects()
        h.advance(2 * minute - 5_000)
        assertNotNull(h.warning())
        h.advance(5_000)
        assertEquals(listOf(BlockKind.INITIAL), h.kinds(X))
        assertEquals(1, h.usage(UsageKind.GRACE_EXPIRED_WHILE_ACTIVE, X))
        // The normal flow follows: wait, Continue, the app's own time limit.
        h.blockAppears(X); h.advance(1_000)
        val at = h.now
        h.tapContinue(X)
        val s = h.session(X)!!
        assertFalse(s.linkPass)
        assertEquals(at + GRACE, s.graceDeadline)
    }

    @Test fun pauseOnlyApp_visitStillEndsInBlock() {
        val h = h()
        h.linkVisit(N, 2)
        h.clearEffects()
        h.advance(2 * minute)
        assertEquals(listOf(BlockKind.INITIAL), h.kinds(N))
    }

    @Test fun firstLink_offersTheFullLimit() {
        val h = h()
        h.linkFromChat(X)
        assertEquals(Effect.ShowBlock(X, BlockKind.LINK, limit, linkLimited = false), h.blocksShown(X).single())
    }

    @Test fun linkWithin15MinOfTheLastVisit_isCappedAt2Min_andSaysSo() {
        // s8: a second link 9 min after the first.
        val h = h()
        h.linkVisit(X, 3)
        h.app(chat)
        h.advance(9 * minute)
        h.clearEffects()
        h.app(X)
        assertEquals(
            Effect.ShowBlock(X, BlockKind.LINK, EngineCore.LINK_REPEAT_MAX_MS, linkLimited = true),
            h.blocksShown(X).single()
        )
        h.blockAppears(X)
        val at = h.now
        h.tapContinue(X, 8 * minute) // more than offered: clamped
        assertEquals(at + 2 * minute, h.session(X)!!.graceDeadline)
        assertTrue(h.session(X)!!.linkPass)
    }

    @Test fun cappedPicker_keepsItsCapWhenItComesBack() {
        val h = h()
        h.linkVisit(X, 1)
        h.app(chat); h.advance(2 * minute); h.clearEffects()
        h.app(X); h.blockAppears(X)
        h.advance(EngineCore.ABSORB_MS + 500)
        h.blockStops(X); h.app(X)
        assertEquals(
            Effect.ShowBlock(X, BlockKind.LINK, EngineCore.LINK_REPEAT_MAX_MS, linkLimited = true),
            h.blocksShown(X).last()
        )
    }

    @Test fun linkAfter15Min_getsTheFullLimitAgain() {
        val h = h()
        h.linkVisit(X, 2)
        h.app(chat)
        h.advance(EngineCore.LINK_REPEAT_WINDOW_MS)
        h.clearEffects()
        h.app(X)
        assertEquals(Effect.ShowBlock(X, BlockKind.LINK, limit, linkLimited = false), h.blocksShown(X).single())
    }

    @Test fun pickerLeftWithGoHome_doesNotCountAsAVisit() {
        val h = h()
        h.linkFromChat(X); h.blockAppears(X); h.blockClosed(X, CloseReason.GO_HOME)
        h.home(); h.advance(1_000); h.clearEffects()
        h.linkFromChat(X)
        assertEquals(false, h.blocksShown(X).single().linkLimited)
    }

    @Test fun limitBelow2Min_isNotCalledLimited() {
        val h = h(x = AppRule(Mode.TIME_LIMIT, GRACE, linkPassMs = minute))
        h.linkVisit(X, 1)
        h.app(chat); h.advance(2 * minute); h.clearEffects()
        h.app(X)
        assertEquals(Effect.ShowBlock(X, BlockKind.LINK, minute, linkLimited = false), h.blocksShown(X).single())
    }

    @Test fun capIsPerApp() {
        val h = h()
        h.linkVisit(X, 2)
        h.clearEffects()
        h.linkFromChat(N)
        assertEquals(false, h.blocksShown(N).single().linkLimited)
    }

    @Test fun fromAnotherRestrictedApp_counts() {
        val h = h()
        h.enterWithContinue(Y)
        h.advance(3_000)
        h.app(X)
        assertEquals(listOf(BlockKind.LINK), h.kinds(X))
    }

    @Test fun pauseOnlySessionOver_linkGivesPicker_E8() {
        val h = h()
        h.enterWithContinue(N)
        h.advance(GRACE) // grace over, still inside
        h.app(chat)
        h.advance(20_000) // past the 10 s return window
        h.clearEffects()
        h.app(N)
        assertEquals(listOf(BlockKind.LINK), h.kinds(N))
    }
}
