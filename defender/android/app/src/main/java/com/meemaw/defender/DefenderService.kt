package com.meemaw.defender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.meemaw.defender.DefenderSettings.isActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps MeemawDefender alive.
 *
 * - Shows a persistent notification ("🛡️ MeemawDefender is active")
 * - Routes results from SMS / notifications / screen monitoring
 * - Pings the local dashboard so it can show "connected"
 * - Returns START_STICKY so Android restarts it if killed
 */
class DefenderService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pingJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
        startForegroundSafely()
        startDashboardPing()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_ANALYZE) {
            val text   = intent.getStringExtra(EXTRA_TEXT).orEmpty()
            val source = intent.getStringExtra(EXTRA_SOURCE) ?: "unknown"
            handleIncoming(text, source)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ── Notification / Foreground ────────────────

    private fun startForegroundSafely() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MeemawDefender",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent defender notification"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        val openIntent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🛡️ MeemawDefender is active")
            .setContentText("Protecting against scams")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    // ── Dashboard heartbeat ──────────────────────

    private fun startDashboardPing() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (true) {
                try { AlertSender.pingServer(this@DefenderService) } catch (_: Exception) {}
                delay(15_000L)
            }
        }
    }

    // ── Analysis pipeline ────────────────────────

    private fun handleIncoming(text: String, source: String) {
        if (!isActive) return
        if (text.isBlank()) return

        scope.launch {
            val result = DefenderAnalyzer.analyze(text, source) ?: return@launch
            Log.i(TAG, "score=${result.score} type=${result.dangerType} source=$source")

            when {
                result.isSafe       -> { /* do nothing */ }
                result.isQuietWarn  -> AlertSender.logLocal(this@DefenderService, result)
                result.isLoudWarn   -> AlertSender.handleLoud(this@DefenderService, result)
                result.isCritical   -> AlertSender.handleCritical(this@DefenderService, result)
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "defender_persistent"
        const val NOTIF_ID   = 1001
        const val TAG        = "DefenderService"

        const val ACTION_ANALYZE = "com.meemaw.defender.ANALYZE"
        const val EXTRA_TEXT     = "extra_text"
        const val EXTRA_SOURCE   = "extra_source"

        fun start(ctx: android.content.Context) {
            val i = Intent(ctx, DefenderService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun analyze(ctx: android.content.Context, text: String, source: String) {
            val i = Intent(ctx, DefenderService::class.java).apply {
                action = ACTION_ANALYZE
                putExtra(EXTRA_TEXT, text)
                putExtra(EXTRA_SOURCE, source)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }
    }
}
