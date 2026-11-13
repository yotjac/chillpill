package com.chillpill.service.engine

import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/**
 * Random input sequences: the invariants (checked by the harness after every step) must hold,
 * and a grace deadline may only move on Continue / "+10 s" / suggestion Restrict (I5). Configs mix
 * the three per-app modes and change them mid-session; a session keeps the rule it started with.
 */
class EngineFuzzTest {

    private val pkgs = listOf(X, N, S, L)

    @Test fun randomSequences_keepInvariants() {
        repeat(SEQUENCES) { seed -> runSequence(seed) }
    }

    /** Rule of each session before the current step. */
    private val rules = HashMap<String, AppRule>()

    private fun runSequence(seed: Int) {
        rules.clear()
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
                    else ProbeResult(
                        pkgs[r.nextInt(pkgs.size)], WindowKind.APP, h.now - r.nextLong(0, 5_000),
                        queriedAt = h.now - r.nextLong(0, 3_000), onlyResumed = r.nextBoolean()
                    )
                    h.answerProbes(result)
                }
            } catch (e: AssertionError) {
                throw AssertionError("seed=$seed: ${e.message}", e)
            }
            for ((p, st) in h.core.appStates) {
                val s = st as? AppState.Session ?: continue
                val wasSession = before.containsKey(p)
                if (wasSession && input !is Input.ContinueTapped && input !is Input.SuggestionAnswered &&
                    rules[p] != null && rules[p] != s.rule
                ) fail("seed=$seed: session rule of $p changed ${rules[p]} -> ${s.rule} on $input")
                val startedNow = before[p] != s.graceDeadline
                if (startedNow && s.rule.mode == Mode.PICK && input is Input.ContinueTapped && input.pkg == p) {
                    val len = s.graceDeadline - input.at
                    if (len !in EngineCore.PICK_MIN_MS..EngineCore.PICK_MAX_MS || len % 60_000L != 0L) {
                        fail("seed=$seed: PICK length $len out of range on $input")
                    }
                }
            }
            rules.clear()
            for ((p, st) in h.core.appStates) (st as? AppState.Session)?.let { rules[p] = it.rule }
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
            4 -> if (r.nextBoolean()) Input.Window("com.android.systemui", null, WindowKind.SYSTEM_OVERLAY, now)
                 else Input.Window(pkg, "android.app.Dialog", WindowKind.APP_OVERLAY, now)
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
            13, 14 -> Input.ContinueTapped(
                pkg, now,
                chosenMs = when (r.nextInt(4)) {
                    0 -> null
                    1 -> r.nextLong(-60_000, 200 * 60_000L) // out of range on purpose
                    else -> r.nextLong(1, 91) * 60_000L
                }
            )
            15 -> Input.ExtendTapped(pkg, now)
            16 -> Input.WarningDismissed(pkg, now)
            17 -> Input.SuggestionEligible(pkg, "name", now)
            18 -> Input.SuggestionAnswered(pkg, SuggestionAnswer.values()[r.nextInt(3)], now)
            else -> {
                fun rule() = AppRule(
                    Mode.values()[r.nextInt(3)],
                    r.nextLong(1, 4) * 30_000L,
                    extensionAllowed = r.nextBoolean()
                )
                val restricted = setOf(X, N, S).filter { r.nextInt(4) != 0 }
                Input.ConfigChanged(Config(restricted.associateWith { rule() }, rule()), now)
            }
        }
    }

    private companion object {
        const val SEQUENCES = 10_000
        const val STEPS = 60
    }
}
