package com.chillpill.service.engine

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

/**
 * JSON-lines encoding of engine inputs and effects. Inputs are replayable: [decodeInputs] turns a
 * pulled trace back into the exact [Input] sequence (see TraceReplayTest).
 *
 * Line shapes:
 *   {"k":"in","type":"Window","wall":1727190000000,"v":{...Input fields...}}
 *   {"k":"fx","at":123456,"v":["ShowBlock{pkg=..}", ...]}
 */
object TraceCodec {
    private val gson = Gson()

    private val inputTypes: Map<String, Class<out Input>> = listOf(
        Input.Window::class.java,
        Input.ScreenOff::class.java,
        Input.ScreenOn::class.java,
        Input.UserPresent::class.java,
        Input.Probe::class.java,
        Input.BlockStarted::class.java,
        Input.BlockStopped::class.java,
        Input.BlockClosed::class.java,
        Input.ContinueTapped::class.java,
        Input.ExtendTapped::class.java,
        Input.WarningDismissed::class.java,
        Input.SuggestionEligible::class.java,
        Input.SuggestionAnswered::class.java,
        Input.ConfigChanged::class.java,
        Input.Tick::class.java,
        Input.ProcessStarted::class.java
    ).associateBy { it.simpleName }

    fun encodeInput(input: Input, wallMs: Long): String {
        val o = JsonObject()
        o.addProperty("k", "in")
        o.addProperty("type", input.javaClass.simpleName)
        o.addProperty("wall", wallMs)
        o.add("v", gson.toJsonTree(input))
        return o.toString()
    }

    fun encodeEffects(at: Long, effects: List<Effect>): String {
        val o = JsonObject()
        o.addProperty("k", "fx")
        o.addProperty("at", at)
        o.add("v", gson.toJsonTree(effects.map { it.toString() }))
        return o.toString()
    }

    /** Inputs of a trace, in order. Effect lines and malformed lines are skipped. */
    fun decodeInputs(lines: Sequence<String>): List<Input> = lines.mapNotNull { line ->
        if (line.isBlank()) return@mapNotNull null
        val o = try {
            JsonParser.parseString(line).asJsonObject
        } catch (_: Exception) {
            return@mapNotNull null
        }
        if (o.get("k")?.asString != "in") return@mapNotNull null
        val type = inputTypes[o.get("type")?.asString] ?: return@mapNotNull null
        gson.fromJson(o.get("v"), type)
    }.toList()
}

/**
 * Appends trace lines to `dir/trace-0.jsonl`, rotating to `trace-1.jsonl` once the current file
 * passes [maxBytes] (so at most two files, ~2 × maxBytes, exist). Thread-safe. Write failures are
 * swallowed: diagnostics must never break blocking.
 */
class EngineTrace(private val dir: File, private val maxBytes: Long = 512L * 1024L) {
    private var writer: BufferedWriter? = null
    private var size = 0L

    val currentFile: File get() = File(dir, "trace-0.jsonl")

    @Synchronized
    fun append(line: String) {
        try {
            val w = writer ?: open()
            w.write(line)
            w.newLine()
            size += line.length + 1
            if (size >= maxBytes) rotate()
        } catch (_: Exception) {
            closeQuietly()
        }
    }

    @Synchronized
    fun flush() {
        try {
            writer?.flush()
        } catch (_: Exception) {
            closeQuietly()
        }
    }

    private fun open(): BufferedWriter {
        dir.mkdirs()
        val file = currentFile
        size = if (file.exists()) file.length() else 0L
        return BufferedWriter(FileWriter(file, true)).also { writer = it }
    }

    private fun rotate() {
        closeQuietly()
        val old = File(dir, "trace-1.jsonl")
        if (old.exists()) old.delete()
        currentFile.renameTo(old)
        size = 0L
    }

    private fun closeQuietly() {
        try {
            writer?.close()
        } catch (_: Exception) {
        }
        writer = null
    }
}
