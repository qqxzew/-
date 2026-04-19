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
 * Sends text to OpenAI GPT-4o-mini and returns a structured danger assessment.
 * Handles all errors gracefully — never throws.
 */
object DefenderAnalyzer {

    private const val TAG = "DefenderAnalyzer"
    private const val ENDPOINT = "https://api.openai.com/v1/chat/completions"
    private const val MODEL = "gpt-4o-mini"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
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

    private const val SYSTEM_PROMPT = """You are a scam detector protecting an elderly person.
Analyze the following text and return ONLY this JSON, no other text:
{
  "score": 0-100,
  "danger_type": "money_transfer" | "remote_access" | "phishing" | "fake_prize" | "personal_data" | "safe",
  "explanation": "one short sentence in the same language as the input text explaining why dangerous"
}
Score 90-100: transfer money, install AnyDesk/TeamViewer, share SMS codes, give bank details.
Score 61-89: unknown links, urgent pressure, prize claims, personal data requests.
Score 40-60: mildly suspicious but unclear.
Score 0-39: normal safe messages."""

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

        val apiKey = BuildConfig.OPENAI_API_KEY
        if (apiKey.isBlank()) {
            Log.w(TAG, "OPENAI_API_KEY missing — treating as safe")
            return safe(trimmed, source)
        }

        val payload = mapOf(
            "model" to MODEL,
            "temperature" to 0,
            "response_format" to mapOf("type" to "json_object"),
            "messages" to listOf(
                mapOf("role" to "system", "content" to SYSTEM_PROMPT),
                mapOf("role" to "user",   "content" to trimmed)
            )
        )
        val body = gson.toJson(payload).toRequestBody("application/json".toMediaType())

        val req = Request.Builder()
            .url(ENDPOINT)
            .addHeader("Authorization", "Bearer $apiKey")
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
                    .getAsJsonArray("choices")
                    .firstOrNull()
                    ?.asJsonObject
                    ?.getAsJsonObject("message")
                    ?.get("content")?.asString
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
