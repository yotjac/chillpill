package com.chillpill.service.engine

import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/**
 * Random input sequences: the invariants (checked by the harness after every step) must hold,
 * and a grace deadline may only move on Continue / "+10 s" / suggestion Restrict (I5).
 */
class EngineFuzzTest {

    private val pkgs = listOf(X, N, S, L)

    @Test fun randomSequences_keepInvariants() {
        repeat(SEQUENCES) { seed -> runSequence(seed) }
    }

    private fun runSequence(seed: Int) {
        val r = Random(seed)
        val h = EngineHarness()
        h.autoProbe = r.nextBoolean()
        repeat(STEPS) {
            h.advance(r.nextLong(0, 15_000))
            val pkg = pkgs[r.nextInt(pkgs.size)]
            val before = deadlines(h)
            val input = randomInput(r, h, pkg)
            try {
                h.send(input)
                if (!h.autoProbe && r.nextInt(3) == 0) {
                    val result = if (r.nextBoolean()) null
                    else ProbeResult(pkgs[r.nextInt(pkgs.size)], WindowKind.APP, h.now - r.nextLong(0, 5_000))
                    h.answerProbes(result)
                }
            } catch (e: AssertionError) {
                throw AssertionError("seed=$seed: ${e.message}", e)
            }
            val after = deadlines(h)
            for ((p, d) in after) {
                val old = before[p] ?: continue
                val allowed = input is Input.ContinueTapped || input is Input.ExtendTapped ||
                    input is Input.SuggestionAnswered
                if (d != old && !allowed) fail("seed=$seed I5: deadline of $p moved $old -> $d on $input")
            }
        }
    }

    private fun deadlines(h: EngineHarness): Map<String, Long> =
        h.core.appStates.mapNotNull { (p, s) -> (s as? AppState.Session)?.let { p to it.graceDeadline } }.toMap()

    private fun randomInput(r: Random, h: EngineHarness, pkg: String): Input {
        val now = h.now
        val instance = h.instances[pkg] ?: 1L
        return when (r.nextInt(20)) {
            0, 1, 2, 3 -> Input.Window(pkg, null, WindowKind.APP, now - r.nextLong(0, 500))
            4 -> Input.Window("com.android.systemui", null, WindowKind.SYSTEM_OVERLAY, now)
            5 -> Input.Window(OWN, null, WindowKind.OWN_BLOCK_UI, now)
            6 -> Input.Window(OWN, null, WindowKind.OWN_MAIN_UI, now)
            7 -> Input.ScreenOff(now)
            8 -> Input.ScreenOn(r.nextBoolean(), now)
            9 -> Input.UserPresent(now)
            10 -> {
                val id = instance + 1
                h.instances[pkg] = id
                Input.BlockStarted(pkg, id, now)
            }
            11 -> Input.BlockStopped(pkg, instance, now)
            12 -> Input.BlockClosed(pkg, CloseReason.values()[r.nextInt(3)], instance, now)
            13, 14 -> Input.ContinueTapped(pkg, now)
            15 -> Input.ExtendTapped(pkg, now)
            16 -> Input.WarningDismissed(pkg, now)
            17 -> Input.SuggestionEligible(pkg, "name", now)
            18 -> Input.SuggestionAnswered(pkg, SuggestionAnswer.values()[r.nextInt(3)], now)
            else -> {
                val restricted = setOf(X, N, S).filter { r.nextInt(4) != 0 }.toSet()
                Input.ConfigChanged(Config(restricted, setOf(N).filter { r.nextBoolean() }.toSet(), GRACE), now)
            }
        }
    }

    private companion object {
        const val SEQUENCES = 10_000
        const val STEPS = 60
    }
}
