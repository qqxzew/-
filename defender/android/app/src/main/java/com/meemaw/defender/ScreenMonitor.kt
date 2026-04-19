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
    private val throttleMs = 200L  // near-live: only de-duplicate rapid scroll events

    // Only analyze screen content when the user is inside an SMS or email app.
    // This keeps Defender from reacting to random apps (browsers, games, our own Assist chat, etc.).
    private val allowedPackages = setOf(
        // SMS / messaging
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "com.android.mms",
        "com.textra",
        // Email
        "com.google.android.gm",
        "com.samsung.android.email.provider",
        "com.microsoft.office.outlook",
        "com.yahoo.mobile.client.android.mail",
        "ru.mail.mailapp",
        "me.bluemail.mail",
        "ch.protonmail.android"
    )

    // Never scan these — our own apps.
    private val blockedPackages = setOf(
        "com.meemaw.defender",
        "com.meemaw.assist"
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        val now = System.currentTimeMillis()
        if (now - lastAnalyzedAt < throttleMs) return

        val root = rootInActiveWindow ?: return
        val pkg  = root.packageName?.toString().orEmpty()

        if (pkg in blockedPackages) return       // our own apps — never analyze
        if (pkg !in allowedPackages) return      // only SMS / email apps

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
