package com.meemaw.defender

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Listens to all notifications on the device.
 * Ignores our own notifications to avoid recursion.
 */
class NotificationReceiver : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (sbn.packageName == packageName) return  // ignore own

        val extras = sbn.notification.extras ?: return
        val title  = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text   = extras.getCharSequence("android.text")?.toString()
            ?: extras.getCharSequence("android.bigText")?.toString()
            ?: ""

        val combined = listOf(title, text).filter { it.isNotBlank() }.joinToString(" — ")
        if (combined.isBlank()) return

        val tagged = "Notification from ${sbn.packageName}: $combined"
        Log.i("NotifReceiver", tagged)

        DefenderService.analyze(this, tagged, "notification")
    }
}
