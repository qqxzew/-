package com.meemaw.defender

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.google.gson.Gson
import com.meemaw.defender.DefenderSettings.lastAlertHash
import com.meemaw.defender.DefenderSettings.lastAlertTime
import com.meemaw.defender.DefenderSettings.serverUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.*
import java.util.Properties
import java.util.concurrent.TimeUnit
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import kotlin.math.absoluteValue

/**
 * Centralizes reactions to dangerous content:
 *  - quiet log (40-60)       → just push to dashboard
 *  - loud warn (61-89)       → email family + push to dashboard
 *  - critical   (>= 90)      → open full-screen Block + email family
 *
 * Email sending uses JavaMail via smtp.gmail.com.
 * Deduplication: no identical alert within 5 minutes.
 */
object AlertSender {

    private const val TAG = "AlertSender"
    private const val DEDUPE_WINDOW_MS = 5 * 60_000L  // 5 minutes

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    private fun candidateServerUrls(ctx: Context): List<String> {
        val current = ctx.serverUrl.trim().removeSuffix("/")
        val isEmulator = Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
            Build.MODEL.contains("Emulator", ignoreCase = true) ||
            Build.MODEL.contains("sdk", ignoreCase = true) ||
            Build.HARDWARE.contains("ranchu", ignoreCase = true)

        val fallbacks = buildList {
            add(current)
            if (isEmulator) {
                add("http://10.0.2.2:3000")
            }
            add("http://127.0.0.1:3000")
        }

        return fallbacks.distinct()
    }

    private inline fun <T> withReachableServer(ctx: Context, request: (baseUrl: String) -> T?): T? {
        for (baseUrl in candidateServerUrls(ctx)) {
            try {
                val result = request(baseUrl)
                if (result != null) {
                    if (ctx.serverUrl.trim().removeSuffix("/") != baseUrl) {
                        ctx.serverUrl = baseUrl
                        Log.i(TAG, "serverUrl auto-corrected to $baseUrl")
                    }
                    return result
                }
            } catch (e: Exception) {
                Log.w(TAG, "request failed via $baseUrl: ${e.message}")
            }
        }
        return null
    }

    // ── Public entry points ─────────────────────

    fun logLocal(ctx: Context, r: DefenderAnalyzer.Result) {
        pushToDashboard(ctx, r)
    }

    fun handleLoud(ctx: Context, r: DefenderAnalyzer.Result) {
        pushToDashboard(ctx, r)
        if (shouldSend(ctx, r)) sendEmailAsync(ctx, r)
    }

    fun handleCritical(ctx: Context, r: DefenderAnalyzer.Result) {
        pushToDashboard(ctx, r)
        if (shouldSend(ctx, r)) sendEmailAsync(ctx, r)
        openBlockScreen(ctx, r)
    }

    // ── Dashboard push ──────────────────────────

    fun pingServer(ctx: Context) {
        withReachableServer(ctx) { baseUrl ->
            val req = Request.Builder().url("$baseUrl/api/ping").get().build()
            http.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return@use null
                Unit
            }
        }
    }

    private fun pushToDashboard(ctx: Context, r: DefenderAnalyzer.Result) {
        runCatching {
            val body = gson.toJson(
                mapOf(
                    "score"        to r.score,
                    "danger_type"  to r.dangerType,
                    "explanation"  to r.explanation,
                    "original"     to r.original
                )
            ).toRequestBody("application/json".toMediaType())

            withReachableServer(ctx) { baseUrl ->
                val req = Request.Builder()
                    .url("$baseUrl/api/alert")
                    .post(body)
                    .build()

                http.newCall(req).execute().use {
                    if (!it.isSuccessful) return@use null
                    Log.i(TAG, "dashboard push ${it.code}")
                    Unit
                }
            }
        }.onFailure { Log.w(TAG, "dashboard push failed: ${it.message}") }
    }

    // ── Full-screen block ───────────────────────

    private fun openBlockScreen(ctx: Context, r: DefenderAnalyzer.Result) {
        val i = Intent(ctx, BlockActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(BlockActivity.EXTRA_EXPLANATION, r.explanation)
            putExtra(BlockActivity.EXTRA_ORIGINAL,    r.original)
            putExtra(BlockActivity.EXTRA_SCORE,       r.score)
        }
        ctx.startActivity(i)
    }

    // ── Email ───────────────────────────────────

    private fun sendEmailAsync(ctx: Context, r: DefenderAnalyzer.Result) {
        Thread {
            runCatching { sendEmailBlocking(ctx, r) }
                .onFailure { Log.e(TAG, "email failed", it) }
        }.start()
    }

    private fun sendEmailBlocking(ctx: Context, r: DefenderAnalyzer.Result) {
        val senderEmail = BuildConfig.SENDER_EMAIL
        val senderPass  = BuildConfig.SENDER_APP_PASSWORD.replace(" ", "")
        if (senderEmail.isBlank() || senderPass.isBlank()) {
            Log.w(TAG, "sender credentials not configured")
            return
        }

        val recipient = fetchFamilyEmail(ctx)
        if (recipient.isNullOrBlank()) {
            Log.w(TAG, "family email not configured — skipping email")
            return
        }

        val props = Properties().apply {
            put("mail.smtp.host", "smtp.gmail.com")
            put("mail.smtp.port", "587")
            put("mail.smtp.auth", "true")
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.ssl.trust", "smtp.gmail.com")
        }
        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication() =
                PasswordAuthentication(senderEmail, senderPass)
        })

        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.getDefault())
            .format(Date())

        val bodyText = """
            ⚠️ MeemawDefender detected suspicious activity.

            Danger score: ${r.score}/100
            Type:         ${r.dangerType}
            Source:       ${r.source}
            Time:         $ts

            Explanation:
            ${r.explanation}

            Original content:
            ${r.original}

            — MeemawDefender
        """.trimIndent()

        val msg = MimeMessage(session).apply {
            setFrom(InternetAddress(senderEmail, "MeemawDefender"))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipient))
            subject = "⚠️ MeemawDefender: Suspicious activity detected"
            setText(bodyText, "UTF-8")
        }

        Transport.send(msg)
        Log.i(TAG, "email sent to $recipient (score=${r.score})")
    }

    private fun fetchFamilyEmail(ctx: Context): String? {
        return runCatching {
            withReachableServer(ctx) { baseUrl ->
                val req = Request.Builder()
                    .url("$baseUrl/api/family-email")
                    .get().build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val json = resp.body?.string().orEmpty()
                    com.google.gson.JsonParser.parseString(json)
                        .asJsonObject.get("email")?.asString
                }
            }
        }.getOrNull()
    }

    // ── Deduplication ───────────────────────────

    private fun shouldSend(ctx: Context, r: DefenderAnalyzer.Result): Boolean {
        val now = System.currentTimeMillis()
        val hash = hashAlert(r)
        val sameAsLast = hash == ctx.lastAlertHash
        val withinWindow = (now - ctx.lastAlertTime).absoluteValue < DEDUPE_WINDOW_MS
        if (sameAsLast && withinWindow) {
            Log.i(TAG, "dedup: skipping duplicate alert")
            return false
        }
        ctx.lastAlertHash = hash
        ctx.lastAlertTime = now
        return true
    }

    private fun hashAlert(r: DefenderAnalyzer.Result): String =
        "${r.score}|${r.dangerType}|${r.original.take(80)}"
}
