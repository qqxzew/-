package com.meemaw.assist.data

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.meemaw.assist.BuildConfig
import com.meemaw.assist.agent.InstalledApp
import com.meemaw.assist.data.api.AIResponse
import com.meemaw.assist.data.api.AgentAction
import com.meemaw.assist.data.api.ChatMessage
import com.meemaw.assist.data.api.ComposeAction
import com.meemaw.assist.data.api.Mode
import com.meemaw.assist.prompt.PromptBuilder
import com.meemaw.assist.prompt.ShowMePromptBuilder
import com.meemaw.assist.showme.ShowMeVisionResponse
import com.meemaw.assist.ui.MessageItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Talks to Google Gemini (text + vision + image edit).
 *
 *  - `ask()`           → gemini-2.5-flash, JSON output for the chat agent loop.
 *  - `analyzeShowMe()` → gemini-2.5-flash vision for photo troubleshooting.
 *  - `annotateImage()` → gemini-2.5-flash-image-preview ("nano-banana") to
 *    draw a circle / highlight on the user's photo.
 */
class LLMRepository {

    companion object {
        private const val TAG = "LLMRepository"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/"
        private const val CHAT_MODEL = "gemini-2.5-flash"
        private const val VISION_MODEL = "gemini-2.5-flash"
        private const val IMAGE_EDIT_MODEL = "gemini-2.5-flash-image"
    }

    private val gson = Gson()
    private val jsonMediaType = "application/json".toMediaType()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    // ── Chat (text-only) ──────────────────────────────────────────────

    suspend fun ask(
        userMessage: String,
        history: List<MessageItem>,
        installedApps: List<InstalledApp> = emptyList()
    ): AIResponse = withContext(Dispatchers.IO) {
        try {
            val messages = PromptBuilder.buildMessages(userMessage, history, installedApps)
            val body = buildChatRequest(messages, jsonOutput = true, temperature = PromptBuilder.temperature(), maxTokens = PromptBuilder.maxTokens())
            val raw = callGenerateContent(CHAT_MODEL, body, "chat")
                ?: return@withContext fallbackResponse("I didn't quite catch that. Could you say it again?")
            Log.d(TAG, "Raw LLM response: $raw")
            parseResponse(raw)
        } catch (e: Exception) {
            Log.e(TAG, "Chat API call failed", e)
            fallbackResponse("I'm having a little trouble right now. Please try again in a moment.")
        }
    }

    // ── SMS body polishing ────────────────────────────────────────────

    /**
     * Turn a messy natural-language request like "напиши смс дочке что я её люблю"
     * or "tell him thet i love she" into a clean SMS body like "Я тебя люблю" /
     * "I love you". Returns null on any failure — caller falls back to the raw text.
     */
    suspend fun polishSmsBody(
        originalRequest: String,
        rawBody: String,
        contactName: String?
    ): String? = withContext(Dispatchers.IO) {
        try {
            val system = """
You convert an elderly user's spoken request into the exact text of an SMS message.
Rules:
- Reply with ONLY the final SMS text, nothing else. No quotes, no labels, no explanations.
- Write what the sender would actually say to the recipient (first person, addressed to the recipient).
- Keep the language the user wrote in (Russian stays Russian, English stays English).
- Fix obvious typos, grammar and pronouns so the message sounds natural and warm.
- Keep it short (ideally under 160 characters). Do not invent new facts.
- Do not include the recipient's name or greetings unless the user asked for them.
""".trimIndent()

            val user = buildString {
                append("User said: ").append(originalRequest.trim()).append('\n')
                if (!contactName.isNullOrBlank()) {
                    append("Recipient: ").append(contactName.trim()).append('\n')
                }
                append("Rough body fragment extracted from the request: ").append(rawBody.trim()).append('\n')
                append("Return ONLY the final SMS text.")
            }

            val body = JsonObject().apply {
                add("systemInstruction", JsonObject().apply {
                    add("parts", JsonArray().apply {
                        add(JsonObject().apply { addProperty("text", system) })
                    })
                })
                add("contents", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("role", "user")
                        add("parts", JsonArray().apply {
                            add(JsonObject().apply { addProperty("text", user) })
                        })
                    })
                })
                add("generationConfig", JsonObject().apply {
                    addProperty("temperature", 0.3)
                    addProperty("maxOutputTokens", 120)
                })
            }

            val raw = callGenerateContent(CHAT_MODEL, body, "sms-polish") ?: return@withContext null
            raw.trim()
                .trim('"', '\'', '«', '»', '“', '”')
                .lines()
                .firstOrNull { it.isNotBlank() }
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.e(TAG, "SMS polish failed", e)
            null
        }
    }

    // ── Show-me (vision) ──────────────────────────────────────────────

    suspend fun analyzeShowMe(
        imageDataUrl: String,
        ocrText: String,
        userHint: String?,
        history: List<MessageItem>
    ): ShowMeVisionResponse = withContext(Dispatchers.IO) {
        try {
            val (mime, base64) = splitDataUrl(imageDataUrl)
            val body = buildShowMeRequest(mime, base64, ocrText, userHint, history)
            val raw = callGenerateContent(VISION_MODEL, body, "show-me")
                ?: return@withContext fallbackShowMeResponse()
            Log.d(TAG, "Raw show-me LLM response: $raw")
            parseShowMeResponse(raw)
        } catch (e: Exception) {
            Log.e(TAG, "Show-me API call failed", e)
            fallbackShowMeResponse()
        }
    }

    // ── Image annotation (nano-banana) ────────────────────────────────

    /**
     * Ask gemini-2.5-flash-image-preview to edit the given photo and return
     * the edited image bytes (PNG/JPEG, as Gemini returns). Returns null on
     * any failure — caller falls back to plain text.
     */
    suspend fun annotateImage(
        imageDataUrl: String,
        instruction: String
    ): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val (mime, base64) = splitDataUrl(imageDataUrl)
            Log.d(TAG, "annotate: calling $IMAGE_EDIT_MODEL with instruction='$instruction'")

            val firstBody = buildAnnotateRequestBody(
                mime = mime,
                base64 = base64,
                prompt = buildAnnotatePrompt(instruction, strict = false)
            )
            val firstResponse = rawGenerateContent(IMAGE_EDIT_MODEL, firstBody, "annotate")
            val firstImage = firstResponse?.let { extractInlineImage(it) }
            if (firstImage != null) {
                return@withContext firstImage
            }

            Log.w(TAG, "annotate: first attempt returned no image, retrying with stricter prompt")
            val retryBody = buildAnnotateRequestBody(
                mime = mime,
                base64 = base64,
                prompt = buildAnnotatePrompt(instruction, strict = true)
            )
            val retryResponse = rawGenerateContent(IMAGE_EDIT_MODEL, retryBody, "annotate-retry")
            retryResponse?.let { extractInlineImage(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Image annotation call failed", e)
            null
        }
    }

    // ── Request builders ──────────────────────────────────────────────

    private fun buildChatRequest(
        messages: List<ChatMessage>,
        jsonOutput: Boolean,
        temperature: Double,
        maxTokens: Int
    ): JsonObject {
        val systemText = messages.filter { it.role == "system" }
            .joinToString("\n\n") { it.content }
        val contents = JsonArray()
        for (msg in messages) {
            if (msg.role == "system") continue
            val role = if (msg.role == "assistant") "model" else "user"
            contents.add(JsonObject().apply {
                addProperty("role", role)
                add("parts", JsonArray().apply {
                    add(JsonObject().apply { addProperty("text", msg.content) })
                })
            })
        }
        return JsonObject().apply {
            if (systemText.isNotBlank()) {
                add("systemInstruction", JsonObject().apply {
                    add("parts", JsonArray().apply {
                        add(JsonObject().apply { addProperty("text", systemText) })
                    })
                })
            }
            add("contents", contents)
            add("generationConfig", JsonObject().apply {
                addProperty("temperature", temperature)
                addProperty("maxOutputTokens", maxTokens)
                if (jsonOutput) addProperty("responseMimeType", "application/json")
            })
        }
    }

    private fun buildShowMeRequest(
        mime: String,
        base64: String,
        ocrText: String,
        userHint: String?,
        history: List<MessageItem>
    ): JsonObject {
        val userText = ShowMePromptBuilder.userContext(userHint, ocrText, history)
        return JsonObject().apply {
            add("systemInstruction", JsonObject().apply {
                add("parts", JsonArray().apply {
                    add(JsonObject().apply { addProperty("text", ShowMePromptBuilder.systemPrompt()) })
                })
            })
            add("contents", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    add("parts", JsonArray().apply {
                        add(JsonObject().apply { addProperty("text", userText) })
                        add(JsonObject().apply {
                            add("inlineData", JsonObject().apply {
                                addProperty("mimeType", mime)
                                addProperty("data", base64)
                            })
                        })
                    })
                })
            })
            add("generationConfig", JsonObject().apply {
                addProperty("temperature", 0.2)
                addProperty("maxOutputTokens", 700)
                addProperty("responseMimeType", "application/json")
            })
        }
    }

    private fun buildAnnotatePrompt(instruction: String, strict: Boolean): String {
        val retryLine = if (strict) {
            "Choose ONLY one precise target. If unsure, pick the most likely internet/WAN destination port by visible label/color/position."
        } else {
            ""
        }
        return """
Edit this photo to help an elderly user locate the right spot.
$instruction
$retryLine

Draw a bright red circle (about 6% of the shorter image side, 4 px stroke) around the exact element, with a small red arrow pointing to it.
Do NOT add any text, labels, watermarks, captions, or legends.
Keep the rest of the photo unchanged. Return ONLY the edited image.
""".trimIndent()
    }

    private fun buildAnnotateRequestBody(
        mime: String,
        base64: String,
        prompt: String
    ): JsonObject {
        return JsonObject().apply {
            add("contents", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    add("parts", JsonArray().apply {
                        add(JsonObject().apply { addProperty("text", prompt) })
                        add(JsonObject().apply {
                            add("inlineData", JsonObject().apply {
                                addProperty("mimeType", mime)
                                addProperty("data", base64)
                            })
                        })
                    })
                })
            })
            add("generationConfig", JsonObject().apply {
                add("responseModalities", JsonArray().apply {
                    add("IMAGE")
                })
            })
        }
    }

    // ── HTTP ──────────────────────────────────────────────────────────

    private fun callGenerateContent(model: String, body: JsonObject, label: String): String? {
        val json = rawGenerateContent(model, body, label) ?: return null
        return try {
            val candidates = json.getAsJsonArray("candidates") ?: return null
            val first = candidates.firstOrNull()?.asJsonObject ?: return null
            val content = first.getAsJsonObject("content") ?: return null
            val parts = content.getAsJsonArray("parts") ?: return null
            parts.mapNotNull { it.asJsonObject.getStringOrNull("text") }
                .joinToString("")
                .ifBlank { null }
        } catch (e: Exception) {
            Log.e(TAG, "$label: failed to extract text", e)
            null
        }
    }

    private fun rawGenerateContent(model: String, body: JsonObject, label: String): JsonObject? {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank()) {
            Log.e(TAG, "$label: GEMINI_API_KEY is not configured")
            return null
        }
        val url = "$BASE_URL$model:generateContent?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()
        return try {
            okHttpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                if (!response.isSuccessful) {
                    Log.e(TAG, "$label failed: ${response.code} ${responseBody?.take(400)}")
                    return null
                }
                if (responseBody.isNullOrBlank()) return null
                JsonParser.parseString(responseBody).asJsonObject
            }
        } catch (e: Exception) {
            Log.e(TAG, "$label request exception", e)
            null
        }
    }

    private fun extractInlineImage(json: JsonObject): ByteArray? {
        return try {
            val candidates = json.getAsJsonArray("candidates") ?: return null
            for (candidate in candidates) {
                val content = candidate.asJsonObject.getAsJsonObject("content") ?: continue
                val parts = content.getAsJsonArray("parts") ?: continue
                for (part in parts) {
                    val inline = part.asJsonObject.getAsJsonObject("inlineData") ?: continue
                    val data = inline.getStringOrNull("data") ?: continue
                    return Base64.decode(data, Base64.DEFAULT)
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract inline image", e)
            null
        }
    }

    // ── Parsers (unchanged from OpenAI version — same JSON schema) ────

    private fun parseResponse(raw: String): AIResponse {
        return try {
            val json = JsonParser.parseString(raw).asJsonObject
            val modeStr = json.getStringOrNull("mode") ?: "CHAT"
            val mode = parseMode(modeStr)
            val message = json.getStringOrNull("message") ?: "I'm here to help!"
            val actions = if (mode == Mode.AGENT) parseActions(json) else null
            val compose = if (mode == Mode.COMPOSE) parseCompose(json) else null
            AIResponse(mode = mode, message = message, actions = actions, compose = compose)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse response JSON: $raw", e)
            fallbackResponse("I understood you, but got a bit confused. Could you rephrase that?")
        }
    }

    private fun parseMode(modeStr: String): Mode {
        return when (modeStr.uppercase().trim()) {
            "CHAT" -> Mode.CHAT
            "AGENT" -> Mode.AGENT
            "COMPOSE" -> Mode.COMPOSE
            "SCAM_ALERT" -> Mode.SCAM_ALERT
            else -> {
                Log.w(TAG, "Unknown mode: $modeStr, defaulting to CHAT")
                Mode.CHAT
            }
        }
    }

    private fun parseActions(json: JsonObject): List<AgentAction> {
        val actionsArray = json.getAsJsonArray("actions") ?: return emptyList()
        return actionsArray.mapNotNull { element ->
            try {
                val obj = element.asJsonObject
                val type = obj.getStringOrNull("type") ?: return@mapNotNull null
                val params: Map<String, String>? = if (obj.has("params") && obj.get("params").isJsonObject) {
                    val paramsObj = obj.getAsJsonObject("params")
                    paramsObj.entrySet().associate { (k, v) -> k to v.asString }
                } else null
                AgentAction(type = type, params = params)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse action element", e)
                null
            }
        }
    }

    private fun parseCompose(json: JsonObject): ComposeAction? {
        val composeObj = json.getAsJsonObject("compose") ?: return null
        return try {
            ComposeAction(
                app = composeObj.getStringOrNull("app") ?: "sms",
                contact = composeObj.getStringOrNull("contact"),
                body = composeObj.getStringOrNull("body")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse compose object", e)
            null
        }
    }

    private fun parseShowMeResponse(raw: String): ShowMeVisionResponse {
        return try {
            val json = JsonParser.parseString(raw).asJsonObject
            val message = json.getStringOrNull("message")
                ?: "I can see the photo, but I need a clearer look."
            val annotate = json.getStringOrNull("annotate")?.takeIf { it.isNotBlank() }
            ShowMeVisionResponse(message = message, annotationInstruction = annotate)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse show-me response JSON: $raw", e)
            fallbackShowMeResponse()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private fun splitDataUrl(dataUrl: String): Pair<String, String> {
        // data:image/jpeg;base64,XXXX
        val commaIdx = dataUrl.indexOf(',')
        if (!dataUrl.startsWith("data:") || commaIdx <= 0) {
            return "image/jpeg" to dataUrl
        }
        val header = dataUrl.substring(5, commaIdx)
        val mime = header.substringBefore(';').ifBlank { "image/jpeg" }
        val base64 = dataUrl.substring(commaIdx + 1)
        return mime to base64
    }

    private fun JsonObject.getStringOrNull(key: String): String? {
        return try {
            if (has(key) && get(key).isJsonPrimitive) get(key).asString else null
        } catch (_: Exception) {
            null
        }
    }

    private fun fallbackResponse(message: String): AIResponse = AIResponse(
        mode = Mode.CHAT,
        message = message,
        actions = null,
        compose = null
    )

    private fun fallbackShowMeResponse(): ShowMeVisionResponse = ShowMeVisionResponse(
        message = "I could not understand that photo clearly. Please try again a little closer.",
        annotationInstruction = null
    )
}
