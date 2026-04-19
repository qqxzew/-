package com.meemaw.defender

import android.content.Context
import android.content.SharedPreferences
import android.os.Build

/**
 * Persistent settings for MeemawDefender.
 */
object DefenderSettings {

    private const val PREFS = "defender_prefs"
    private const val KEY_GRANDMA_NAME    = "grandma_name"
    private const val KEY_IS_ACTIVE       = "is_active"
    private const val KEY_LAST_ALERT_TIME = "last_alert_time"
    private const val KEY_LAST_ALERT_HASH = "last_alert_hash"
    private const val KEY_SERVER_URL      = "server_url"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun defaultServerUrl(): String {
        val isEmulator = Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
            Build.MODEL.contains("Emulator", ignoreCase = true) ||
            Build.MODEL.contains("sdk", ignoreCase = true) ||
            Build.HARDWARE.contains("ranchu", ignoreCase = true)
        return if (isEmulator) {
            "http://10.0.2.2:3000"
        } else {
            "http://127.0.0.1:3000"
        }
    }

    var Context.grandmaName: String
        get() = prefs(this).getString(KEY_GRANDMA_NAME, "Meemaw") ?: "Meemaw"
        set(v) { prefs(this).edit().putString(KEY_GRANDMA_NAME, v).apply() }

    var Context.isActive: Boolean
        get() = prefs(this).getBoolean(KEY_IS_ACTIVE, true)
        set(v) { prefs(this).edit().putBoolean(KEY_IS_ACTIVE, v).apply() }

    var Context.lastAlertTime: Long
        get() = prefs(this).getLong(KEY_LAST_ALERT_TIME, 0L)
        set(v) { prefs(this).edit().putLong(KEY_LAST_ALERT_TIME, v).apply() }

    var Context.lastAlertHash: String
        get() = prefs(this).getString(KEY_LAST_ALERT_HASH, "") ?: ""
        set(v) { prefs(this).edit().putString(KEY_LAST_ALERT_HASH, v).apply() }

    /**
     * Base URL of the local Node.js dashboard.
     * On Android emulators the host machine is reachable via 10.0.2.2.
     * On real devices 127.0.0.1 still assumes adb reverse or a locally hosted server.
     */
    var Context.serverUrl: String
        get() = prefs(this).getString(KEY_SERVER_URL, defaultServerUrl()) ?: defaultServerUrl()
        set(v) { prefs(this).edit().putString(KEY_SERVER_URL, v).apply() }
}
