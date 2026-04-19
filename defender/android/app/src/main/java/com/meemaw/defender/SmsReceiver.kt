package com.meemaw.defender

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

/**
 * Intercepts incoming SMS and forwards the body to DefenderService.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return

        // Join multi-part SMS bodies
        val sender = messages.firstOrNull()?.originatingAddress.orEmpty()
        val body   = messages.joinToString(separator = "") { it.messageBody.orEmpty() }

        if (body.isBlank()) return

        val text = "SMS from $sender: $body"
        Log.i("SmsReceiver", "Incoming SMS: $text")

        DefenderService.analyze(ctx, text, "sms")
    }
}
