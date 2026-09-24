package com.chillpill.service.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Replays recorded engine traces. To turn a device bug into a test:
 *   adb exec-out run-as com.chillpill cat files/trace/trace-0.jsonl > app/src/test/resources/traces/<name>.jsonl
 * then add a test below that replays it and asserts the expected outcome.
 */
class TraceReplayTest {

    private fun replay(inputs: List<Input>, now: Long = inputs.maxOf { it.at }): Pair<EngineCore, List<Effect>> {
        val core = EngineCore()
        val effects = ArrayList<Effect>()
        for (input in inputs) {
            effects += core.handle(input)
            val v = core.invariantViolations(input.at)
            if (v.isNotEmpty()) fail("Invariant violated after $input: $v")
        }
        core.warning(now)
        return core to effects
    }

    private fun load(name: String): List<Input> {
        val stream = javaClass.classLoader!!.getResourceAsStream("traces/$name")
            ?: error("missing test resource traces/$name")
        return TraceCodec.decodeInputs(stream.bufferedReader().lineSequence())
    }

    @Test fun codec_roundTripsEveryInputType() {
        val inputs = listOf(
            Input.ConfigChanged(Config(setOf(X, N), setOf(N), GRACE), 1),
            Input.ProcessStarted("reason=10 (USER_REQUESTED)", 2),
            Input.Window(X, "a.B", WindowKind.APP, 3),
            Input.ScreenOff(4), Input.ScreenOn(true, 5), Input.UserPresent(6),
            Input.Probe(ProbeResult(X, WindowKind.APP, 7), 1, 8), Input.Probe(null, 2, 9),
            Input.BlockStarted(X, 42, 10), Input.BlockStopped(X, 42, 11),
            Input.BlockClosed(X, CloseReason.GO_HOME, 42, 12), Input.ContinueTapped(X, 13),
            Input.ExtendTapped(X, 14), Input.WarningDismissed(X, 15),
            Input.SuggestionEligible(S, "Name", 16), Input.SuggestionAnswered(S, SuggestionAnswer.NEVER, 17),
            Input.Tick(18)
        )
        val lines = inputs.map { TraceCodec.encodeInput(it, 1_727_000_000_000L) } +
            TraceCodec.encodeEffects(18, listOf(Effect.ShowBlock(X, BlockKind.INITIAL))) + "garbage"
        assertEquals(inputs, TraceCodec.decodeInputs(lines.asSequence()))
    }

    @Test fun traceFile_rotates() {
        val dir = Files.createTempDirectory("trace").toFile()
        val trace = EngineTrace(dir, maxBytes = 1_000)
        repeat(110) { trace.append("x".repeat(50)) }
        trace.flush()
        assertTrue(File(dir, "trace-1.jsonl").exists())
        assertTrue(File(dir, "trace-0.jsonl").length() < 1_000)
        assertEquals(2, dir.listFiles()!!.size)
    }

    /** Hand-written trace of the S1 stale-launcher sequence (see EngineCoreTest for the same in code). */
    @Test fun s1_staleLauncher_trace() {
        val inputs = load("s1-stale-launcher.jsonl")
        val (core, effects) = replay(inputs)
        assertEquals(1, effects.count { it is Effect.ShowBlock })
        assertEquals(N, core.presence.pkg)
    }

    /** Every trace dropped into resources must at least replay without breaking an invariant. */
    @Test fun allTraces_replayCleanly() {
        val dir = javaClass.classLoader!!.getResource("traces")?.let { File(it.toURI()) } ?: return
        dir.listFiles { f -> f.name.endsWith(".jsonl") }!!.forEach { f ->
            replay(TraceCodec.decodeInputs(f.readLines().asSequence()))
        }
    }
}
