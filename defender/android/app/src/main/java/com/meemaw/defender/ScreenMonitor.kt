package com.meemaw.defender

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Reads visible on-screen text and sends changed content to the analyzer.
 * Throttles and de-duplicates to avoid spamming the API.
 */
class ScreenMonitor : AccessibilityService() {

    private var lastText: String = ""
    private var lastAnalyzedAt: Long = 0
    private val throttleMs = 4_000L  // at most one analysis per 4s per screen

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        val now = System.currentTimeMillis()
        if (now - lastAnalyzedAt < throttleMs) return

        val root = rootInActiveWindow ?: return
        val pkg  = root.packageName?.toString().orEmpty()
        if (pkg == packageName) return  // ignore our own UI

        val text = collectText(root).trim()
        if (text.length < 20) return
        if (text == lastText) return

        lastText = text
        lastAnalyzedAt = now

        Log.i("ScreenMonitor", "analyzing screen text (${text.length} chars) from $pkg")
        DefenderService.analyze(this, "Screen text ($pkg): $text", "screen")
    }

    override fun onInterrupt() { /* no-op */ }

    private fun collectText(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        val sb = StringBuilder()
        walk(node, sb)
        // Limit length — don't blow the API context
        val full = sb.toString()
        return if (full.length > 2000) full.substring(0, 2000) else full
    }

    private fun walk(node: AccessibilityNodeInfo, sb: StringBuilder) {
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let {
            sb.append(it).append(' ')
        }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let {
            sb.append(it).append(' ')
        }
        val count = node.childCount
        for (i in 0 until count) {
            node.getChild(i)?.let { walk(it, sb) }
        }
    }
}
