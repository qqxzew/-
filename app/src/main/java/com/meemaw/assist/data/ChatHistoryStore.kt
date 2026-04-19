package com.meemaw.assist.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.meemaw.assist.ui.MessageItem
import java.io.File

data class ChatSession(
    val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<MessageItem>
)

data class ChatHistoryState(
    val currentSessionId: String,
    val sessions: List<ChatSession>
)

object ChatHistoryStore {

    private const val PREFS_NAME = "meemaw_chat_history"
    private const val KEY_CHAT_STATE_JSON = "chat_state_json"
    private const val KEY_MESSAGES_JSON = "messages_json"
    private const val MAX_STORED_SESSIONS = 24
    private const val MAX_STORED_MESSAGES = 120

    private val gson = Gson()

    fun loadState(context: Context): ChatHistoryState {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rawStateJson = prefs.getString(KEY_CHAT_STATE_JSON, null)
        if (!rawStateJson.isNullOrBlank()) {
            val storedState = runCatching {
                gson.fromJson(rawStateJson, StoredChatState::class.java)
            }.getOrNull()

            val sessions = storedState?.sessions
                .orEmpty()
                .mapNotNull { session ->
                    val messages = session.messages.mapStoredMessages()
                    if (messages.isEmpty()) {
                        null
                    } else {
                        ChatSession(
                            id = session.id,
                            createdAt = session.createdAt,
                            updatedAt = session.updatedAt,
                            messages = messages
                        )
                    }
                }
                .sortedByDescending { it.updatedAt }

            val currentSessionId = storedState?.currentSessionId
                ?.takeIf { currentId -> sessions.any { it.id == currentId } }
                ?: sessions.firstOrNull()?.id
                .orEmpty()

            return ChatHistoryState(
                currentSessionId = currentSessionId,
                sessions = sessions
            )
        }

        val legacyMessages = loadLegacyMessages(context)
        if (legacyMessages.isNotEmpty()) {
            val now = System.currentTimeMillis()
            val session = ChatSession(
                id = "legacy-$now",
                createdAt = now,
                updatedAt = now,
                messages = legacyMessages.takeLast(MAX_STORED_MESSAGES)
            )
            return ChatHistoryState(
                currentSessionId = session.id,
                sessions = listOf(session)
            )
        }

        return ChatHistoryState(currentSessionId = "", sessions = emptyList())
    }

    fun saveState(context: Context, state: ChatHistoryState) {
        val sessionsToStore = state.sessions
            .sortedByDescending { it.updatedAt }
            .take(MAX_STORED_SESSIONS)
            .map { session ->
                StoredChatSession(
                    id = session.id,
                    createdAt = session.createdAt,
                    updatedAt = session.updatedAt,
                    messages = session.messages
                        .takeLast(MAX_STORED_MESSAGES)
                        .map { item ->
                            when (item) {
                                is MessageItem.User -> StoredMessage(type = TYPE_USER, text = item.text)
                                is MessageItem.Ai -> StoredMessage(type = TYPE_AI, text = item.text)
                                is MessageItem.ScamWarning -> StoredMessage(type = TYPE_SCAM, text = item.text)
                                is MessageItem.AiImage -> StoredMessage(
                                    type = TYPE_AI_IMAGE,
                                    text = item.text,
                                    imagePath = item.imagePath
                                )
                            }
                        }
                )
            }

        val currentSessionId = state.currentSessionId
            .takeIf { currentId -> sessionsToStore.any { it.id == currentId } }
            ?: sessionsToStore.firstOrNull()?.id
            .orEmpty()

        val storedState = StoredChatState(
            currentSessionId = currentSessionId,
            sessions = sessionsToStore
        )

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CHAT_STATE_JSON, gson.toJson(storedState))
            .apply()
    }

    private fun loadLegacyMessages(context: Context): List<MessageItem> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rawJson = prefs.getString(KEY_MESSAGES_JSON, null) ?: return emptyList()
        val type = object : TypeToken<List<StoredMessage>>() {}.type
        val stored = runCatching {
            gson.fromJson<List<StoredMessage>>(rawJson, type)
        }.getOrNull().orEmpty()

        return stored.mapStoredMessages()
    }

    private fun List<StoredMessage>.mapStoredMessages(): List<MessageItem> {
        return mapNotNull { item ->
            when (item.type) {
                TYPE_USER -> item.text?.let { MessageItem.User(it) }
                TYPE_AI -> item.text?.let { MessageItem.Ai(it) }
                TYPE_SCAM -> item.text?.let { MessageItem.ScamWarning(it) }
                TYPE_AI_IMAGE -> {
                    val text = item.text ?: return@mapNotNull null
                    val imagePath = item.imagePath
                    if (!imagePath.isNullOrBlank() && File(imagePath).exists()) {
                        MessageItem.AiImage(text = text, imagePath = imagePath)
                    } else {
                        MessageItem.Ai(text)
                    }
                }
                else -> null
            }
        }
    }

    private data class StoredChatState(
        val currentSessionId: String? = null,
        val sessions: List<StoredChatSession> = emptyList()
    )

    private data class StoredChatSession(
        val id: String,
        val createdAt: Long,
        val updatedAt: Long,
        val messages: List<StoredMessage>
    )

    private data class StoredMessage(
        val type: String,
        val text: String? = null,
        val imagePath: String? = null
    )

    private const val TYPE_USER = "user"
    private const val TYPE_AI = "ai"
    private const val TYPE_SCAM = "scam"
    private const val TYPE_AI_IMAGE = "ai_image"
}
