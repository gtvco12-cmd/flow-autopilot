package com.flowautopilot

import android.content.Context
import java.io.File

/** Saves prompts (one per line), progress and settings on the device. */
class PromptStore(ctx: Context) {
    private val file = File(ctx.filesDir, "prompts.txt")
    private val prefs = ctx.getSharedPreferences("flow_autopilot", Context.MODE_PRIVATE)

    fun parse(text: String): List<String> =
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }

    fun all(): List<String> = if (file.exists()) file.readLines() else emptyList()

    /** Replaces the list and resets progress. */
    fun replace(lines: List<String>) {
        file.writeText(lines.joinToString("\n"))
        submitted = 0
    }

    /** Adds to the end, keeps progress. */
    fun append(lines: List<String>) {
        val merged = all() + lines
        file.writeText(merged.joinToString("\n"))
    }

    var submitted: Int
        get() = prefs.getInt("submitted", 0)
        set(v) = prefs.edit().putInt("submitted", v).apply()

    var intervalSec: Int
        get() = prefs.getInt("interval", 10)
        set(v) = prefs.edit().putInt("interval", v).apply()

    var batchSize: Int
        get() = prefs.getInt("batch", 100)
        set(v) = prefs.edit().putInt("batch", v).apply()

    var lastReport: String
        get() = prefs.getString("report", "") ?: ""
        set(v) = prefs.edit().putString("report", v).apply()

    /** Long prompt text with {TOPIC} where each list line goes. */
    var template: String
        get() = prefs.getString("template", "") ?: ""
        set(v) = prefs.edit().putString("template", v).apply()

    /** Builds the full prompt for one list line. */
    fun build(topic: String): String {
        val t = template
        return if (t.isBlank()) topic else t.replace("{TOPIC}", topic)
    }
}
