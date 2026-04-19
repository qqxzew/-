package com.meemaw.assist.agent

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.meemaw.assist.data.api.AgentAction
import com.meemaw.assist.guide.GuideController
import com.meemaw.assist.guide.GuideFlows

class SystemConfigExecutor {

    companion object {
        private const val TAG = "SystemConfigExecutor"

        /** Common app names → package names. Model sends the key, we resolve the package. */
        val APP_PACKAGES = mapOf(
            // Messengers
            "telegram" to "org.telegram.messenger",
            "whatsapp" to "com.whatsapp",
            "viber" to "com.viber.voip",
            "messenger" to "com.facebook.orca",
            "facebook_messenger" to "com.facebook.orca",
            "signal" to "org.thoughtcrime.securesms",
            "discord" to "com.discord",
            "skype" to "com.skype.raider",
            "wechat" to "com.tencent.mm",
            "snapchat" to "com.snapchat.android",
            "tiktok" to "com.zhiliaoapp.musically",
            // Social
            "vk" to "com.vkontakte.android",
            "vkontakte" to "com.vkontakte.android",
            "instagram" to "com.instagram.android",
            "facebook" to "com.facebook.katana",
            "twitter" to "com.twitter.android",
            "x" to "com.twitter.android",
            "ok" to "ru.ok.android",
            "odnoklassniki" to "ru.ok.android",
            "pinterest" to "com.pinterest",
            "reddit" to "com.reddit.frontpage",
            "linkedin" to "com.linkedin.android",
            // Video
            "youtube" to "com.google.android.youtube",
            "rutube" to "ru.rutube.app",
            // Maps & navigation
            "maps" to "com.google.android.apps.maps",
            "google_maps" to "com.google.android.apps.maps",
            "yandex_maps" to "ru.yandex.yandexmaps",
            "yandex_navi" to "ru.yandex.yandexnavi",
            "2gis" to "ru.dublgis.dgismobile",
            // Transport
            "uber" to "com.ubercab",
            "yandex_taxi" to "ru.yandex.taxi",
            // Shopping
            "ozon" to "ru.ozon.app.android",
            "wildberries" to "com.wildberries.ru",
            // Banking
            "sberbank" to "ru.sberbankmobile",
            "sber" to "ru.sberbankmobile",
            "tinkoff" to "com.idamob.tinkoff.android",
            // Google
            "chrome" to "com.android.chrome",
            "gmail" to "com.google.android.gm",
            "google_photos" to "com.google.android.apps.photos",
            "photos" to "com.google.android.apps.photos",
            "calendar" to "com.google.android.calendar",
            "google_drive" to "com.google.android.apps.docs",
            "clock" to "com.google.android.deskclock",
            "calculator" to "com.google.android.calculator",
            "camera" to "com.android.camera",
            "contacts" to "com.google.android.contacts",
            // Yandex
            "yandex_browser" to "com.yandex.browser",
            "alice" to "com.yandex.searchapp",
            "yandex" to "com.yandex.searchapp",
            // Media
            "spotify" to "com.spotify.music",
            "yandex_music" to "ru.yandex.music",
            "netflix" to "com.netflix.mediaclient",
            // Utilities
            "play_store" to "com.android.vending",
            "files" to "com.google.android.apps.nbu.files",
            "notes" to "com.google.android.keep",
            "weather" to "com.google.android.apps.weather",
        )
    }

    fun execute(action: AgentAction, context: Context): String {
        return try {
            when (action.type) {
                "wifi_on" -> setWifi(context, true)
                "wifi_off" -> setWifi(context, false)
                "bluetooth_on" -> setBluetooth(context, true)
                "bluetooth_off" -> setBluetooth(context, false)
                "volume_up" -> adjustVolume(context, AudioManager.ADJUST_RAISE)
                "volume_down" -> adjustVolume(context, AudioManager.ADJUST_LOWER)
                "volume_max" -> setVolumeMax(context)
                "volume_mute" -> adjustVolume(context, AudioManager.ADJUST_MUTE)
                "brightness_max" -> setBrightness(context, 255)
                "brightness_up" -> adjustBrightness(context, 40)
                "brightness_down" -> adjustBrightness(context, -40)
                "open_settings" -> openSettings(context, action.params?.get("section"))
                "open_app" -> {
                    val packageHint = action.params?.get("package")
                    val appName = action.params?.get("app") ?: action.params?.get("label") ?: ""
                    openApp(context, appName, packageHint)
                }
                "open_url" -> {
                    val url = action.params?.get("url")
                    val label = action.params?.get("label") ?: action.params?.get("site") ?: "website"
                    openUrl(context, url, label)
                }
                "restart_suggestion" -> "Try restarting your phone — hold the power button for a few seconds."
                else -> {
                    Log.w(TAG, "Unknown action: ${action.type}")
                    "Done ✓"
                }
            }
        } catch (e: Exception) {
            "Something went wrong while executing that action. Please try again."
        }
    }

    /**
     * Open any installed app.
     * Priority order:
     *   1. Exact package name from model (packageNameHint) — always fastest & most accurate
     *   2. Known APP_PACKAGES map
     *   3. Literal package name if appName contains "."
     *   4. Label search across all launcher apps
     */
    fun openApp(context: Context, appName: String, packageNameHint: String? = null): String {
        val pm = context.packageManager

        // 1. Model gave us the exact package name (from installed apps list in prompt)
        if (!packageNameHint.isNullOrBlank()) {
            val intent = pm.getLaunchIntentForPackage(packageNameHint)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return "Opening the app ✓"
            }
        }

        val key = appName.lowercase().trim().replace(" ", "_")

        // 2. Known APP_PACKAGES map
        val mappedPackage = APP_PACKAGES[key]
        if (mappedPackage != null) {
            val intent = pm.getLaunchIntentForPackage(mappedPackage)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return "Opening the app ✓"
            }
        }

        // 3. Try as literal package name (e.g. "com.example.app")
        if (key.contains(".")) {
            val intent = pm.getLaunchIntentForPackage(key)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return "Opening the app ✓"
            }
        }

        // 4. Search installed apps by label (case-insensitive substring)
        val searchTerm = appName.lowercase().trim()
        val mainIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        @Suppress("DEPRECATION")
        val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
        val match = resolveInfos.firstOrNull { ri ->
            val label = ri.loadLabel(pm).toString().lowercase()
            label.contains(searchTerm) || searchTerm.contains(label)
        }
        if (match != null) {
            val launchIntent = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                return "Opening ${match.loadLabel(pm)} ✓"
            }
        }

        return "I couldn't find that app on your phone. It may not be installed."
    }

    private fun openUrl(context: Context, rawUrl: String?, label: String): String {
        val url = rawUrl?.trim().orEmpty()
        if (url.isBlank()) {
            return "I couldn't find that website address."
        }

        val normalizedUrl = if (url.startsWith("http://") || url.startsWith("https://")) {
            url
        } else {
            "https://$url"
        }

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(normalizedUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
            "Opening $label in your browser ✓"
        } else {
            "I couldn't open that website right now."
        }
    }

    @Suppress("DEPRECATION")
    private fun setWifi(context: Context, enabled: Boolean): String {
        // WifiManager.setWifiEnabled() is a no-op on API 29+ (Android 10)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val intent = Intent(Settings.Panel.ACTION_WIFI).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            GuideController.start(context, if (enabled) GuideFlows.wifiOn() else GuideFlows.wifiOff())
            return if (enabled) "I opened Wi-Fi settings for you. Follow the hint at the top."
            else "I opened Wi-Fi settings for you. Follow the hint at the top."
        }
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiManager.isWifiEnabled = enabled
        return if (enabled) "Wi-Fi turned on ✓" else "Wi-Fi turned off ✓"
    }

    @Suppress("MissingPermission")
    private fun setBluetooth(context: Context, enabled: Boolean): String {
        // BluetoothAdapter.enable()/disable() requires BLUETOOTH_CONNECT on API 31+
        // and is fully deprecated on API 33+. Fall back to settings.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            GuideController.start(context, if (enabled) GuideFlows.bluetoothOn() else GuideFlows.bluetoothOff())
            return if (enabled) "I opened Bluetooth settings for you. Follow the hint at the top."
            else "I opened Bluetooth settings for you. Follow the hint at the top."
        }
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = btManager.adapter ?: return "Bluetooth is not available on this device."
        @Suppress("DEPRECATION")
        if (enabled) adapter.enable() else adapter.disable()
        return if (enabled) "Bluetooth turned on ✓" else "Bluetooth turned off ✓"
    }

    private fun adjustVolume(context: Context, direction: Int): String {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        return "Volume adjusted ✓"
    }

    private fun setVolumeMax(context: Context): String {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, max, AudioManager.FLAG_SHOW_UI)
        return "Volume set to maximum ✓"
    }

    private fun setBrightness(context: Context, value: Int): String {
        // WRITE_SETTINGS is a special permission — must check canWrite()
        if (!Settings.System.canWrite(context)) {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            GuideController.start(context, GuideFlows.brightnessWriteSettings())
            return "Please allow MeemawAssist to change system settings, then try again."
        }
        Settings.System.putInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        )
        Settings.System.putInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS,
            value.coerceIn(0, 255)
        )
        return "Brightness adjusted ✓"
    }

    private fun adjustBrightness(context: Context, delta: Int): String {
        if (!Settings.System.canWrite(context)) {
            return setBrightness(context, 128) // will trigger the permission prompt
        }
        val current = Settings.System.getInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS, 128
        )
        return setBrightness(context, current + delta)
    }

    private fun openSettings(context: Context, section: String?): String {
        val action = when (section?.lowercase()) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        val intent = Intent(action).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        when (section?.lowercase()) {
            "wifi" -> GuideController.start(context, GuideFlows.wifiOn())
            "bluetooth" -> GuideController.start(context, GuideFlows.bluetoothOn())
        }
        return "Please check the Settings screen I just opened."
    }
}
