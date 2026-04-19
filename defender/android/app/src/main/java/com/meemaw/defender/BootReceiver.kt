package com.meemaw.defender

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.meemaw.defender.DefenderSettings.isActive

/**
 * Auto-starts the Foreground Service on device boot.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            if (ctx.isActive) DefenderService.start(ctx)
        }
    }
}
