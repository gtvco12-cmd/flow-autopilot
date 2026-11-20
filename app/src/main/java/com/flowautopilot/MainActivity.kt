package com.flowautopilot

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var store: PromptStore
    private lateinit var a11yStatus: TextView
    private lateinit var summary: TextView
    private lateinit var intervalEt: EditText
    private lateinit var batchEt: EditText
    private lateinit var reportTv: TextView
    private val reqImport = 1

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = PromptStore(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }
        fun add(v: View) = root.addView(
            v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(8) }
        )
        fun label(t: String, bold: Boolean = false) = TextView(this).apply {
            text = t
            textSize = if (bold) 18f else 15f
            if (bold) setTypeface(null, Typeface.BOLD)
        }
        fun btn(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t
            isAllCaps = false
            setOnClickListener { onClick() }
        }

        add(label("Flow AutoPilot", true))

        a11yStatus = label("")
        add(a11yStatus)
        add(btn("Open Accessibility Settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        add(label("Prompts", true))
        summary = label("")
        add(summary)
        add(btn("Paste prompts") { showPasteDialog() })
        add(btn("Import TXT file") {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "text/*"
                }, reqImport
            )
        })

        add(label("Settings", true))
        add(label("Seconds between prompts"))
        intervalEt = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        add(intervalEt)
        add(label("Batch size (pause after this many)"))
        batchEt = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        add(batchEt)
        add(btn("Save settings") { saveSettings() })

        add(label("Diagnostic", true))
        add(label("Tap, then switch to Google Flow within 10 seconds and leave the prompt box and Generate button visible."))
        add(btn("Run diagnostic (10 s)") { runDiagnostic() })
        add(btn("Test: submit 1 prompt") { val s = FlowAccessibilityService.instance; if (s == null || store.all().size <= store.submitted) { Toast.makeText(this, "Turn on accessibility and add a prompt first", Toast.LENGTH_LONG).show() } else { FlowTest.start(s, 10_000); Toast.makeText(this, "Switch to Google Flow now, on the prompt screen with an EMPTY box", Toast.LENGTH_LONG).show(); moveTaskToBack(true) } })
        add(btn("Copy report") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Flow diagnostic", store.lastReport))
            Toast.makeText(this, "Report copied", Toast.LENGTH_SHORT).show()
        })
        reportTv = label("").apply { typeface = Typeface.MONOSPACE; textSize = 10f; setTextIsSelectable(true) }
        add(reportTv)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val on = FlowAccessibilityService.instance != null
        a11yStatus.text = if (on) "Accessibility service: ON" else "Accessibility service: OFF (enable Flow AutoPilot in Installed apps)"
        val total = store.all().size
        val done = store.submitted
        summary.text = "Total: $total\nSubmitted: $done\nRemaining: ${maxOf(total - done, 0)}"
        intervalEt.setText(store.intervalSec.toString())
        batchEt.setText(store.batchSize.toString())
        val r = store.lastReport
        reportTv.text = if (r.isEmpty()) "No report yet." else r
    }

    private fun saveSettings() {
        val i = intervalEt.text.toString().toIntOrNull()
        val b = batchEt.text.toString().toIntOrNull()
        if (i == null || i < 1 || b == null || b < 1) {
            Toast.makeText(this, "Enter whole numbers of 1 or more", Toast.LENGTH_SHORT).show()
            return
        }
        store.intervalSec = i
        store.batchSize = b
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
    }

    private fun runDiagnostic() {
        val svc = FlowAccessibilityService.instance
        if (svc == null) {
            Toast.makeText(this, "Turn on the accessibility service first", Toast.LENGTH_LONG).show()
            return
        }
        svc.scheduleDiagnostic(10_000)
        Toast.makeText(this, "Switch to Google Flow now (10 seconds)", Toast.LENGTH_LONG).show()
        moveTaskToBack(true)
    }

    private fun showPasteDialog() {
        val et = EditText(this).apply {
            hint = "Paste prompts here, one per line"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 6
            gravity = android.view.Gravity.TOP
        }
        AlertDialog.Builder(this)
            .setTitle("Paste prompts")
            .setView(et)
            .setPositiveButton("Next") { _, _ -> applyPrompts(et.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyPrompts(text: String) {
        val lines = store.parse(text)
        if (lines.isEmpty()) {
            Toast.makeText(this, "No prompts found", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("${lines.size} prompts found")
            .setMessage("Replace the current list (progress resets) or add to the end?")
            .setPositiveButton("Replace") { _, _ -> store.replace(lines); refresh() }
            .setNeutralButton("Add to end") { _, _ -> store.append(lines); refresh() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqImport && resultCode == RESULT_OK) {
            val uri: Uri = data?.data ?: return
            try {
                val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                applyPrompts(text)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not read file", Toast.LENGTH_LONG).show()
            }
        }
    }
}
