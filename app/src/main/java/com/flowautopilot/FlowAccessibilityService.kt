package com.flowautopilot

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FlowAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: FlowAccessibilityService? = null
        private const val MAX_NODES = 1500
        private val GENERATE_HINT = Regex("(?i)generat|send|submit|create|arrow|run|go|start")
    }

    private val handler = Handler(Looper.getMainLooper())

    private class Stats {
        var count = 0
        var textNodes = 0
        var truncated = false
        val editable = ArrayList<String>()
        val generate = ArrayList<String>()
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** Waits [delayMs], then inspects whatever app is on screen and saves a report. */
    fun scheduleDiagnostic(delayMs: Long) {
        handler.postDelayed({ runDiagnostic() }, delayMs)
    }

    private fun runDiagnostic() {
        val store = PromptStore(this)
        val root = rootInActiveWindow
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val head = StringBuilder()
        head.appendLine("FLOW AUTOPILOT DIAGNOSTIC  $time")
        head.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")

        if (root == null) {
            head.appendLine("RESULT: No active window could be read (screen may be protected or Flow blocks access).")
            finish(store, head.toString())
            return
        }

        val pkg = root.packageName?.toString() ?: "unknown"
        head.appendLine("Foreground package: $pkg")
        try {
            val w = windows.joinToString { "${it.type}:${it.root?.packageName}" }
            head.appendLine("Windows: $w")
        } catch (e: Exception) {
            head.appendLine("Windows: unavailable")
        }
        if (pkg == packageName) {
            head.appendLine("WARNING: You were still on Flow AutoPilot, not Flow. Run again and switch to Flow.")
        }

        val stats = Stats()
        val tree = StringBuilder()
        dump(root, 0, tree, stats)

        head.appendLine("Nodes read: ${stats.count}${if (stats.truncated) " (truncated)" else ""}")
        head.appendLine("Nodes with text/description: ${stats.textNodes}")
        head.appendLine()
        head.appendLine("PROMPT BOX CANDIDATES (editable): ${stats.editable.size}")
        stats.editable.forEach { head.appendLine("  $it") }
        head.appendLine()
        head.appendLine("GENERATE BUTTON CANDIDATES (clickable + name hint): ${stats.generate.size}")
        stats.generate.forEach { head.appendLine("  $it") }
        head.appendLine()
        val verdict = when {
            stats.count <= 3 -> "Screen exposes almost nothing. Flow may be drawing its own UI or blocking access."
            stats.editable.isEmpty() -> "No editable prompt box found. Tap into the prompt box first and run again."
            stats.generate.isEmpty() -> "Prompt box found, but no obvious Generate button. Check the full tree below."
            else -> "Prompt box and Generate candidates found. Not yet verified by an actual tap."
        }
        head.appendLine("VERDICT: $verdict")
        head.appendLine()
        head.appendLine("FULL TREE (flags: C=clickable E=enabled T=editable F=focused):")
        head.append(tree)
        finish(store, head.toString())
    }

    private fun finish(store: PromptStore, report: String) {
        store.lastReport = report
        Toast.makeText(this, "Diagnostic captured. Return to Flow AutoPilot.", Toast.LENGTH_LONG).show()
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        } catch (_: Exception) {
            // If Android blocks this, the user switches back manually.
        }
    }

    private fun dump(node: AccessibilityNodeInfo, depth: Int, sb: StringBuilder, st: Stats) {
        if (st.count >= MAX_NODES) {
            st.truncated = true
            return
        }
        st.count++
        val r = Rect()
        node.getBoundsInScreen(r)
        val id = node.viewIdResourceName ?: ""
        val text = node.text?.toString()?.replace("\n", " ")?.take(60) ?: ""
        val desc = node.contentDescription?.toString()?.replace("\n", " ")?.take(60) ?: ""
        val hint = if (Build.VERSION.SDK_INT >= 26) node.hintText?.toString()?.take(60) ?: "" else ""
        val cls = node.className?.toString()?.substringAfterLast('.') ?: ""
        if (text.isNotEmpty() || desc.isNotEmpty()) st.textNodes++

        val flags = buildString {
            if (node.isClickable) append('C')
            if (node.isEnabled) append('E')
            if (node.isEditable) append('T')
            if (node.isFocused) append('F')
        }
        val line = "$cls id=$id text=\"$text\" desc=\"$desc\" hint=\"$hint\" [$flags] ${r.toShortString()}"
        sb.append("  ".repeat(minOf(depth, 12))).appendLine(line)

        if (node.isEditable || cls.contains("EditText")) st.editable.add(line)
        if (node.isClickable && GENERATE_HINT.containsMatchIn("$text $desc $id")) st.generate.add(line)

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { dump(it, depth + 1, sb, st) }
        }
    }
}
