package com.meemaw.defender

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Sends text to Google Gemini 2.5 Pro and returns a structured danger assessment.
 * Handles all errors gracefully — never throws.
 */
object DefenderAnalyzer {

    private const val TAG = "DefenderAnalyzer"
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/"
    private const val MODEL = "gemini-2.5-flash-lite"

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    private val allowedDangerTypes = setOf(
        "money_transfer",
        "remote_access",
        "phishing",
        "fake_prize",
        "personal_data",
        "safe"
    )

    private const val SYSTEM_PROMPT = """You are a calm, user-friendly safety assistant that warns the user when a situation looks risky.

Analyze the following text and return ONLY this JSON, no other text:
{
  "score": 0-100,
  "danger_type": "money_transfer" | "remote_access" | "phishing" | "fake_prize" | "personal_data" | "safe",
  "explanation": "2-3 short, clear sentences in the SAME LANGUAGE as the input text, written directly to the user (use 'you'). Avoid harsh words like 'DANGER', 'SCAM', 'FRAUD', 'THREAT'. Describe what is happening in plain language and suggest a safe next step, e.g. 'please take a moment and check with someone you trust before continuing'. Keep the tone calm, respectful, and easy to understand."
}

Scoring:
- 90-100: sending money, sharing bank details / SMS codes, installing remote-control apps (AnyDesk/TeamViewer), giving card numbers, large transfers.
- 61-89: unknown links, urgent pressure, prize claims, personal data requests.
- 40-60: mildly suspicious but unclear.
- 0-39: normal safe messages.

Examples of good 'explanation' text (match this calm, plain tone):
- "It looks like you are about to send a large amount of money. Please pause and double-check with someone you trust before continuing."
- "This message is asking for your bank card details. That is usually not safe to share — please verify who is asking before you reply."
- "This link may not be safe to open. Please take a moment to confirm the sender before tapping it."
"""

    data class Result(
        val score: Int,
        val dangerType: String,
        val explanation: String,
        val original: String,
        val source: String // "sms", "notification", "screen"
    ) {
        val isSafe: Boolean       get() = score < 40
        val isQuietWarn: Boolean  get() = score in 40..60
        val isLoudWarn: Boolean   get() = score in 61..89
        val isCritical: Boolean   get() = score >= 90
    }

    /**
     * Blocking call — run from a coroutine on IO dispatcher.
     * Returns null only if an unrecoverable error occurs.
     */
    fun analyze(text: String, source: String): Result? {
        val trimmed = text.trim()
        if (trimmed.length < 2) return safe(trimmed, source)

        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank()) {
            Log.w(TAG, "GEMINI_API_KEY missing — treating as safe")
            return safe(trimmed, source)
        }

        // Gemini generateContent schema
        val payload = mapOf(
            "systemInstruction" to mapOf(
                "parts" to listOf(mapOf("text" to SYSTEM_PROMPT))
            ),
            "contents" to listOf(
                mapOf(
                    "role" to "user",
                    "parts" to listOf(mapOf("text" to trimmed))
                )
            ),
            "generationConfig" to mapOf(
                "temperature" to 0,
                "responseMimeType" to "application/json",
                "thinkingConfig" to mapOf("thinkingBudget" to 0),
                "maxOutputTokens" to 200
            )
        )
        val body = gson.toJson(payload).toRequestBody("application/json".toMediaType())

        val req = Request.Builder()
            .url("${BASE_URL}${MODEL}:generateContent?key=$apiKey")
            .post(body)
            .build()

        return try {
            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "API ${response.code}: ${response.message}")
                    return safe(trimmed, source)
                }
                val raw = response.body?.string().orEmpty()
                val content = JsonParser.parseString(raw)
                    .asJsonObject
                    .getAsJsonArray("candidates")
                    .firstOrNull()
                    ?.asJsonObject
                    ?.getAsJsonObject("content")
                    ?.getAsJsonArray("parts")
                    ?.firstOrNull()
                    ?.asJsonObject
                    ?.get("text")?.asString
                    .orEmpty()

                parseResult(content, trimmed, source)
            }
        } catch (e: Exception) {
            Log.e(TAG, "analyze() failed", e)
            safe(trimmed, source)
        }
    }

    private fun parseResult(json: String, original: String, source: String): Result {
        return try {
            val obj = JsonParser.parseString(json).asJsonObject
            val score = obj.get("score")?.asInt?.coerceIn(0, 100) ?: 0
            val rawType = obj.get("danger_type")?.asString ?: "safe"
            val exp   = obj.get("explanation")?.asString ?: ""
            val type  = normalizeDangerType(rawType, score, original, exp)
            Result(score, type, exp, original, source)
        } catch (e: Exception) {
            Log.e(TAG, "parseResult failed: $json", e)
            safe(original, source)
        }
    }

    private fun normalizeDangerType(
        rawType: String,
        score: Int,
        original: String,
        explanation: String
    ): String {
        val normalized = rawType.trim().lowercase()
        if (normalized in allowedDangerTypes) {
            return normalized
        }

        val haystack = listOf(rawType, original, explanation)
            .joinToString(" ")
            .lowercase()

        return when {
            score < 40 -> "safe"
            haystack.contains("anydesk") ||
                haystack.contains("teamviewer") ||
                haystack.contains("remote") -> "remote_access"
            haystack.contains("transfer") ||
                haystack.contains("iban") ||
                haystack.contains("western union") ||
                haystack.contains("wire") ||
                haystack.contains("safe account") -> "money_transfer"
            haystack.contains("prize") ||
                haystack.contains("lottery") ||
                haystack.contains("gift card") ||
                haystack.contains("won") -> "fake_prize"
            haystack.contains("password") ||
                haystack.contains("pin") ||
                haystack.contains("card") ||
                haystack.contains("cvv") ||
                haystack.contains("code") -> "personal_data"
            haystack.contains("http") ||
                haystack.contains("link") ||
                haystack.contains("verify") ||
                haystack.contains("login") -> "phishing"
            else -> "phishing"
        }
    }

    private fun safe(original: String, source: String) =
        Result(0, "safe", "", original, source)
}
