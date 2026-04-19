package com.meemaw.assist.agent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class InstalledApp(
    val label: String,
    val packageName: String
)

object AppScanner {

    private const val TAG = "AppScanner"
    private const val PREFS_NAME = "meemaw_app_catalog"
    private const val KEY_APPS_JSON = "installed_apps_json"

    private val gson = Gson()

    /**
     * Scans all launchable apps actually installed on the device,
     * saves them locally, and returns the fresh list sorted by label.
     */
    fun getInstalledApps(context: Context): List<InstalledApp> {
        return refreshInstalledApps(context)
    }

    fun refreshInstalledApps(context: Context): List<InstalledApp> {
        return try {
            val pm = context.packageManager
            val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }

            val launcherActivities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(
                    launcherIntent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
            }

            val apps = launcherActivities
                .mapNotNull { resolveInfo ->
                    resolveInfo.toInstalledApp(pm)
                }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }

            Log.d(TAG, "Scanned ${apps.size} launchable apps")
            saveInstalledApps(context, apps)
            apps
        } catch (e: Exception) {
            Log.e(TAG, "Failed to scan installed apps", e)
            emptyList()
        }
    }

    private fun ResolveInfo.toInstalledApp(pm: PackageManager): InstalledApp? {
        val label = loadLabel(pm).toString().trim()
        val packageName = activityInfo?.packageName?.trim().orEmpty()

        if (label.isBlank() || packageName.isBlank()) {
            return null
        }

        return InstalledApp(
            label = label,
            packageName = packageName
        )
    }

    fun loadInstalledApps(context: Context): List<InstalledApp> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_APPS_JSON, null) ?: return emptyList()

        return try {
            val type = object : TypeToken<List<InstalledApp>>() {}.type
            val apps: List<InstalledApp> = gson.fromJson(json, type) ?: emptyList()
            apps
                .map {
                    InstalledApp(
                        label = it.label.trim(),
                        packageName = it.packageName.trim()
                    )
                }
                .filter { it.label.isNotBlank() && it.packageName.isNotBlank() }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load cached apps", e)
            emptyList()
        }
    }

    private fun saveInstalledApps(context: Context, apps: List<InstalledApp>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_APPS_JSON, gson.toJson(apps))
            .apply()
    }

    /**
     * Format the installed apps list into a compact string for the LLM context.
     * Each line: "App Label → com.package.name"
     */
    fun formatForPrompt(apps: List<InstalledApp>): String {
        if (apps.isEmpty()) return "(no apps scanned)"
        return apps.joinToString("\n") { "${it.label} → ${it.packageName}" }
    }
}
