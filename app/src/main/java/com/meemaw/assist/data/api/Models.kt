package com.meemaw.assist.data.api

import com.google.gson.annotations.SerializedName

// ── OpenAI API Request ──

data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.3,
    @SerializedName("max_tokens")
    val maxTokens: Int = 1024,
    @SerializedName("response_format")
    val responseFormat: ResponseFormat? = null
)

data class ChatMessage(
    val role: String,
    val content: String
)

data class ResponseFormat(
    val type: String = "json_object"
)

// ── OpenAI API Response ──

data class ChatResponse(
    val choices: List<Choice>
)

data class Choice(
    val message: ChatMessage,
    @SerializedName("finish_reason")
    val finishReason: String?
)

// ── App Domain Models ──

enum class Mode {
    CHAT, AGENT, COMPOSE, SCAM_ALERT
}

data class AIResponse(
    val mode: Mode,
    val message: String,
    val actions: List<AgentAction>? = null,
    val compose: ComposeAction? = null
)

data class AgentAction(
    val type: String,
    val params: Map<String, String>? = null
)

data class ComposeAction(
    val app: String,
    val contact: String? = null,
    val body: String? = null
)
