package com.flowautopilot

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/** One-prompt test: inserts the next prompt into Flow, taps Generate, confirms the box cleared. */
object FlowTest {
    private const val FLOW_PACKAGE = "com.google.android.apps.labs.whisk"
    private const val BOX_HINT = "What do you want to make?"
    @Volatile
    private var running = false

    fun start(svc: FlowAccessibilityService, startDelayMs: Long) {
        if (running) return
        running = true
        Thread {
            val log = StringBuilder("FLOW AUTOPILOT 1-PROMPT TEST\n")
            try {
                execute(svc, startDelayMs, log)
            } catch (e: Exception) {
                log.appendLine("RESULT: ERROR $e")
            }
            running = false
            finish(svc, log.toString())
        }.start()
    }

    private fun norm(s: String) = s.replace(Regex("\\s+"), " ").trim()

    private fun collect(
        node: AccessibilityNodeInfo?,
        pred: (AccessibilityNodeInfo) -> Boolean,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        if (node == null) return
        if (pred(node)) out.add(node)
        for (i in 0 until node.childCount) collect(node.getChild(i), pred, out)
    }

    private fun nodes(
        svc: FlowAccessibilityService,
        pred: (AccessibilityNodeInfo) -> Boolean
    ): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        collect(svc.rootInActiveWindow, pred, out)
        return out
    }

    private fun findBox(svc: FlowAccessibilityService): AccessibilityNodeInfo? {
        val eds = nodes(svc) {
            it.isEditable && (it.className?.toString()?.contains("EditText") == true)
        }
        return eds.firstOrNull { it.hintText?.toString() == BOX_HINT } ?: eds.singleOrNull()
    }

    private fun boxText(n: AccessibilityNodeInfo): String {
        val t = n.text?.toString() ?: ""
        val h = n.hintText?.toString() ?: ""
        return if (t == h) "" else t
    }

    private fun findGenerate(svc: FlowAccessibilityService): AccessibilityNodeInfo? {
        val l = nodes(svc) { it.contentDescription?.toString() == "Generate" }
        return l.firstOrNull { it.isEnabled && it.isClickable } ?: l.firstOrNull()
    }

    private fun waitFor(timeoutMs: Long, stepMs: Long = 500, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (cond()) return true
            if (System.currentTimeMillis() >= end) return false
            Thread.sleep(stepMs)
        }
    }

    private fun execute(svc: FlowAccessibilityService, startDelayMs: Long, log: StringBuilder) {
        val store = PromptStore(svc)
        val prompts = store.all()
        val index = store.submitted
        if (index >= prompts.size) {
            log.appendLine("RESULT: STOPPED - no unsent prompt in the list.")
            return
        }
        val topic = prompts[index].trim()
        val prompt = store.build(topic)
        log.appendLine("Prompt #${index + 1} topic: ${topic.take(80)}")
        log.appendLine("Full prompt length: ${prompt.length} characters")
        Thread.sleep(startDelayMs)

        val pkg = svc.rootInActiveWindow?.packageName?.toString()
        if (pkg != FLOW_PACKAGE) {
            log.appendLine("RESULT: STOPPED - Flow is not in front (found: $pkg).")
            return
        }

        val box = findBox(svc)
        if (box == null) {
            log.appendLine("RESULT: STOPPED - prompt box not found. Open the screen with the prompt box.")
            return
        }
        val existing = boxText(box)
        if (existing.isNotEmpty()) {
            log.appendLine("RESULT: STOPPED - box already has text, not overwriting: ${existing.take(60)}")
            return
        }
        log.appendLine("Step: prompt box found and empty.")

        box.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, prompt)
        val set = box.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        log.appendLine("Step: set-text returned $set.")
        if (!set) {
            log.appendLine("RESULT: STOPPED - Flow refused the text insertion.")
            return
        }
        val inserted = waitFor(5000) {
            findBox(svc)?.let { norm(boxText(it)) == norm(prompt) } == true
        }
        if (!inserted) {
            val now = findBox(svc)?.let { boxText(it) }
            log.appendLine("RESULT: STOPPED - text not accepted. Box length=${now?.length}, starts: ${now?.take(80)}")
            return
        }
        log.appendLine("Step: prompt verified in the box.")

        Thread.sleep(store.intervalSec * 1000L)
        val stillOk = waitFor(5000) {
            findBox(svc)?.let { norm(boxText(it)) == norm(prompt) } == true
        }
        if (!stillOk) {
            val b = findBox(svc)
            val pkgNow = svc.rootInActiveWindow?.packageName
            log.appendLine(
                "RESULT: STOPPED - box check failed after wait. Box found=${b != null}, " +
                    "length=${b?.let { boxText(it).length }}, starts=${b?.let { boxText(it) }?.take(80)}, " +
                    "window=$pkgNow. Generate not tapped."
            )
            return
        }

        val ready = waitFor(5000) {
            findGenerate(svc)?.let { it.isEnabled && it.isClickable } == true
        }
        val gen = findGenerate(svc)
        if (!ready || gen == null) {
            log.appendLine(
                "RESULT: STOPPED - Generate not ready (found=${gen != null}, " +
                    "enabled=${gen?.isEnabled}, clickable=${gen?.isClickable}). Generate not tapped."
            )
            return
        }
        val tapped = gen.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        log.appendLine("Step: Generate tap returned $tapped.")
        if (!tapped) {
            log.appendLine("RESULT: STOPPED - Generate tap was refused.")
            return
        }

        val t0 = System.currentTimeMillis()
        val cleared = waitFor(20000, 250) {
            findBox(svc)?.let { boxText(it).trim().isEmpty() } == true
        }
        if (!cleared) {
            log.appendLine("RESULT: NOT CONFIRMED - box did not clear in 20 s, or the screen changed. Check Flow. Progress NOT saved.")
            return
        }
        log.appendLine("Step: box cleared after ${System.currentTimeMillis() - t0} ms.")
        store.submitted = index + 1
        log.appendLine("RESULT: SUCCESS - prompt #${index + 1} confirmed, progress saved. Check Flow that a generation started.")
    }

    private fun finish(svc: FlowAccessibilityService, report: String) {
        PromptStore(svc).lastReport = report
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(svc, "Test finished. Return to Flow AutoPilot.", Toast.LENGTH_LONG).show()
        }
        try {
            svc.startActivity(
                Intent(svc, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        } catch (_: Exception) {
        }
    }
}
