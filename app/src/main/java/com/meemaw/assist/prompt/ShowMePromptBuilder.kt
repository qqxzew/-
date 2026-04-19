package com.meemaw.assist.prompt

import com.meemaw.assist.ui.MessageItem

object ShowMePromptBuilder {

    private const val VISION_MODEL = "gemini-2.5-flash"

    private val SYSTEM_PROMPT = """
You are MeemawAssist camera mode.
You must respond ONLY with one valid JSON object and nothing else.

Schema:
{
  "message": "<short simple explanation in the user's language>",
  "annotate": "<optional short English instruction for an image-edit model describing which single element to circle in the photo, e.g. 'Draw a red circle around the yellow LAN port on the back of the router.'>"
}

Rules:
- Detect the language from the user hint and the conversation history, and write the "message" in that same language (English, Russian, Ukrainian, Slovak, Czech, Polish, German, French, Spanish, Italian, and other European languages are all supported). Default to English if the language is unclear.
- This photo is part of an ongoing chat. Use the conversation history as important context.
- The user may show any screen, cable, port, button, paper notice, or physical device. A router is only one example.
- If the user asks where to plug/connect/insert a cable (internet, WAN, LAN, DSL, Ethernet), ALWAYS include the "annotate" field when any plausible target port is visible.
- Read the visible text in the image and combine it with the OCR text provided below.
- Do not assume the image is a router or internet problem unless the chat context or user hint points there.
- If the chat already explains the problem, do not give a generic description like "The image shows a router" unless that is truly necessary.
- Give the next concrete step first.
- Explain what the user should do in 1-3 short simple sentences.
- Describe the visible target in words only.
- If multiple very similar buttons, ports, or connectors are visible, explain which one matters in words.
- If the needed target is not visible, say exactly what new photo the user should take next and OMIT the "annotate" field.
- If ONE specific element is clearly visible and the user would benefit from seeing it circled (a port, a button, a switch, a socket, a jack, a screw, a label field on a form, a specific option in a menu screenshot), include the "annotate" field. Keep the instruction ALWAYS in English, short (under 25 words), and describe the single target unambiguously by its shape / color / position.
- For cable/port questions, the "annotate" instruction must name the exact destination port and include at least 2 visual anchors (color + relative position, or label + shape).
- If annotation is not helpful (blurry photo, target not visible, no specific element to circle), OMIT the "annotate" field entirely.
- Never return coordinates, boxes, labels, arrows, or localization metadata inside "message".
- If the image is blurry, say that simply and omit "annotate".
""".trimIndent()

    fun model(): String = VISION_MODEL

    fun systemPrompt(): String = SYSTEM_PROMPT

    fun userContext(userHint: String?, ocrText: String, history: List<MessageItem>): String {
        val hint = userHint?.trim().takeUnless { it.isNullOrBlank() } ?: "No extra user hint provided."
        val ocr = ocrText.trim().ifBlank { "No readable OCR text found." }
        val conversation = formatConversation(history)
        return """
Conversation history:
$conversation

User hint:
$hint

OCR text from the photo:
$ocr

Continue the same troubleshooting conversation. Give the next practical step and explain the visible target in simple words only.
""".trimIndent()
    }

    private fun formatConversation(history: List<MessageItem>): String {
        val recentHistory = history.takeLast(8)
        if (recentHistory.isEmpty()) {
            return "No previous chat context."
        }

        return recentHistory.joinToString("\n") { item ->
            when (item) {
                is MessageItem.User -> "User: ${item.text.singleLine()}"
                is MessageItem.Ai -> "Assistant: ${item.text.singleLine()}"
                is MessageItem.AiImage -> "Assistant photo reply: ${item.text.singleLine()}"
                is MessageItem.ScamWarning -> "Assistant warning: ${item.text.singleLine()}"
            }
        }
    }

    private fun String.singleLine(maxLength: Int = 180): String {
        val normalized = replace("\n", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (normalized.length <= maxLength) {
            normalized
        } else {
            normalized.take(maxLength - 1).trimEnd() + "…"
        }
    }
}