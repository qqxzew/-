package com.meemaw.defender

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Debug-only trigger so the full detection pipeline can be exercised over adb:
 *
 *     adb shell am broadcast -a com.meemaw.defender.DEMO \
 *         --es text "Your bank account is locked. Transfer 500 EUR to ..." \
 *         --es source sms
 *
 * Forwards the payload into DefenderService exactly like a real SMS would.
 */
class TestTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val text = intent.getStringExtra("text").orEmpty()
        val source = intent.getStringExtra("source") ?: "demo"
        if (text.isBlank()) {
            Log.w("TestTrigger", "empty text, ignoring")
            return
        }
        Log.i("TestTrigger", "demo inject source=$source text=$text")
        DefenderService.analyze(ctx, text, source)
    }

    companion object {
        const val ACTION = "com.meemaw.defender.DEMO"
    }
}
