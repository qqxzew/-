package com.meemaw.assist

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meemaw.assist.agent.AgentLoop
import com.meemaw.assist.agent.AppOpener
import com.meemaw.assist.agent.AppScanner
import com.meemaw.assist.agent.ComposeCommandRouter
import com.meemaw.assist.agent.ContactResolver
import com.meemaw.assist.agent.ContactResolution
import com.meemaw.assist.agent.InstalledApp
import com.meemaw.assist.agent.PhoneContact
import com.meemaw.assist.agent.WebsiteLauncher
import com.meemaw.assist.agent.YouTubeLauncher
import com.meemaw.assist.data.ChatHistoryState
import com.meemaw.assist.data.ChatHistoryStore
import com.meemaw.assist.data.ChatSession
import com.meemaw.assist.data.LLMRepository
import com.meemaw.assist.data.api.Mode
import com.meemaw.assist.showme.ShowMeAnalyzer
import com.meemaw.assist.ui.ChatHistoryEntry
import com.meemaw.assist.ui.MessageItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MainViewModel : ViewModel() {

    companion object {
        private const val TAG = "MainViewModel"
        private const val DEFAULT_WELCOME_MESSAGE = "Hello! I'm MeemawAssist. How can I help you today?"
        private const val DEFAULT_NEW_CHAT_TITLE = "New chat"
    }

    private val repository = LLMRepository()
    private val agentLoop = AgentLoop()
    private val showMeAnalyzer = ShowMeAnalyzer(repository)
    private val installedAppsMutex = Mutex()
    private val contactsMutex = Mutex()

    // Cached list of installed apps scanned from the real device and refreshed on startup.
    private var installedApps: List<InstalledApp> = emptyList()
    private var contacts: List<PhoneContact> = emptyList()
    private var persistenceContext: Context? = null
    private var currentSessionId: String = ""
    private var chatSessions: List<ChatSession> = emptyList()

    private val _messages = MutableStateFlow<List<MessageItem>>(
        listOf(MessageItem.Ai(DEFAULT_WELCOME_MESSAGE))
    )
    val messages: StateFlow<List<MessageItem>> = _messages.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _chatHistoryEntries = MutableStateFlow<List<ChatHistoryEntry>>(emptyList())
    val chatHistoryEntries: StateFlow<List<ChatHistoryEntry>> = _chatHistoryEntries.asStateFlow()

    fun restoreChatHistory(context: Context) {
        val appContext = context.applicationContext
        persistenceContext = appContext

        val restoredState = ChatHistoryStore.loadState(appContext)
        if (restoredState.sessions.isEmpty()) {
            val session = createSession()
            chatSessions = listOf(session)
            currentSessionId = session.id
            _messages.value = session.messages
            persistChatState()
        } else {
            chatSessions = restoredState.sessions.sortedByDescending { it.updatedAt }
            currentSessionId = restoredState.currentSessionId
                .takeIf { id -> chatSessions.any { it.id == id } }
                ?: chatSessions.first().id

            _messages.value = currentSession().messages
        }

        refreshChatHistoryEntries()
    }

    fun openChat(sessionId: String) {
        val session = chatSessions.firstOrNull { it.id == sessionId } ?: return
        currentSessionId = session.id
        _messages.value = session.messages
        refreshChatHistoryEntries()
        persistChatState()
    }

    fun startNewChat() {
        val existingSession = currentSessionOrNull()
        if (existingSession != null && !existingSession.hasMeaningfulMessages()) {
            _messages.value = existingSession.messages
            refreshChatHistoryEntries()
            return
        }

        val session = createSession()
        currentSessionId = session.id
        chatSessions = listOf(session) + chatSessions.filterNot { it.id == session.id }
        _messages.value = session.messages
        refreshChatHistoryEntries()
        persistChatState()
    }

    fun preloadInstalledApps(context: Context) {
        val appContext = context.applicationContext
        persistenceContext = appContext

        viewModelScope.launch {
            try {
                val cachedApps = withContext(Dispatchers.IO) {
                    AppScanner.loadInstalledApps(appContext)
                }

                installedAppsMutex.withLock {
                    if (installedApps.isEmpty() && cachedApps.isNotEmpty()) {
                        installedApps = cachedApps
                    }
                }

                val refreshedApps = withContext(Dispatchers.IO) {
                    AppScanner.refreshInstalledApps(appContext)
                }

                installedAppsMutex.withLock {
                    installedApps = refreshedApps
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to preload installed apps", e)
            }
        }
    }

    fun preloadContacts(context: Context) {
        val appContext = context.applicationContext
        persistenceContext = appContext
        if (!ContactResolver.hasPermission(appContext)) {
            return
        }

        viewModelScope.launch {
            try {
                val loadedContacts = withContext(Dispatchers.IO) {
                    ContactResolver.loadContacts(appContext)
                }

                contactsMutex.withLock {
                    contacts = loadedContacts
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to preload contacts", e)
            }
        }
    }

    private suspend fun ensureInstalledApps(context: Context): List<InstalledApp> {
        installedAppsMutex.withLock {
            if (installedApps.isNotEmpty()) {
                return installedApps
            }
        }

        val cachedApps = withContext(Dispatchers.IO) {
            AppScanner.loadInstalledApps(context)
        }
        if (cachedApps.isNotEmpty()) {
            installedAppsMutex.withLock {
                if (installedApps.isEmpty()) {
                    installedApps = cachedApps
                }
                return installedApps
            }
        }

        val scannedApps = withContext(Dispatchers.IO) {
            AppScanner.refreshInstalledApps(context)
        }
        installedAppsMutex.withLock {
            installedApps = scannedApps
            return installedApps
        }
    }

    private suspend fun refreshInstalledApps(context: Context): List<InstalledApp> {
        val scannedApps = withContext(Dispatchers.IO) {
            AppScanner.refreshInstalledApps(context)
        }

        installedAppsMutex.withLock {
            installedApps = scannedApps
            return installedApps
        }
    }

    private suspend fun ensureContacts(context: Context): List<PhoneContact> {
        if (!ContactResolver.hasPermission(context)) {
            return emptyList()
        }

        contactsMutex.withLock {
            if (contacts.isNotEmpty()) {
                return contacts
            }
        }

        val loadedContacts = withContext(Dispatchers.IO) {
            ContactResolver.loadContacts(context)
        }

        contactsMutex.withLock {
            contacts = loadedContacts
            return contacts
        }
    }

    fun onUserMessage(text: String, context: Context) {
        persistenceContext = context.applicationContext
        addMessage(MessageItem.User(text))
        _status.value = "Thinking…"

        val appContext = context.applicationContext

        viewModelScope.launch {
            val availableApps = ensureInstalledApps(appContext)

            val websiteResult = WebsiteLauncher.tryHandle(text, appContext)
            if (websiteResult != null) {
                _status.value = null
                addMessage(MessageItem.Ai(websiteResult))
                return@launch
            }

            val directCompose = ComposeCommandRouter.parse(text)
            if (directCompose != null) {
                val availableContacts = ensureContacts(appContext)
                val polishedCompose = maybePolishSmsBody(directCompose, text, availableContacts, appContext)
                val result = agentLoop.openCompose(polishedCompose, appContext, availableContacts)
                _status.value = null
                addMessage(MessageItem.Ai(result))
                return@launch
            }

            val youTubeResult = YouTubeLauncher.tryHandle(text, appContext)
            if (youTubeResult != null) {
                _status.value = null
                addMessage(MessageItem.Ai(youTubeResult))
                return@launch
            }

            // ── CLIENT-SIDE APP OPEN (bypasses LLM — instant, reliable) ──
            // If the message looks like "открой X" / "open X", try to open directly.
            val appQuery = AppOpener.extractAppName(text)
            if (appQuery != null) {
                var openedLabel = AppOpener.tryOpen(appQuery, availableApps, appContext)
                if (openedLabel == null) {
                    val refreshedApps = refreshInstalledApps(appContext)
                    openedLabel = AppOpener.tryOpen(appQuery, refreshedApps, appContext)
                }
                _status.value = null
                if (openedLabel != null) {
                    addMessage(MessageItem.Ai("Opening $openedLabel ✓"))
                    return@launch
                }
                // Not found in installed apps — let LLM respond (will say not installed)
            }

            try {
                // Drop the last item (the just-added user message) to avoid duplication —
                // PromptBuilder.buildMessages() appends userMessage at the end separately.
                val history = _messages.value.dropLast(1)
                val response = repository.ask(text, history, availableApps)

                when (response.mode) {
                    Mode.SCAM_ALERT -> {
                        _status.value = null
                        addMessage(MessageItem.ScamWarning(response.message))
                    }
                    Mode.AGENT -> {
                        _status.value = "Fixing…"
                        val actions = response.actions ?: emptyList()
                        val result = agentLoop.execute(actions, appContext)
                        _status.value = null
                        addMessage(MessageItem.Ai(result))
                    }
                    Mode.COMPOSE -> {
                        _status.value = null
                        val compose = response.compose
                        if (compose != null) {
                            val availableContacts = if (
                                compose.app.equals("sms", ignoreCase = true) ||
                                compose.app.equals("call", ignoreCase = true) ||
                                compose.app.equals("phone", ignoreCase = true)
                            ) {
                                ensureContacts(appContext)
                            } else {
                                emptyList()
                            }
                            val result = agentLoop.openCompose(compose, appContext, availableContacts)
                            addMessage(MessageItem.Ai(result))
                            return@launch
                        }
                        addMessage(MessageItem.Ai(response.message))
                    }
                    Mode.CHAT -> {
                        _status.value = null
                        addMessage(MessageItem.Ai(response.message))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing message", e)
                _status.value = null
                addMessage(MessageItem.Ai("Something went wrong. Please try again."))
            }
        }
    }

    private suspend fun maybePolishSmsBody(
        compose: com.meemaw.assist.data.api.ComposeAction,
        originalText: String,
        availableContacts: List<PhoneContact>,
        context: Context
    ): com.meemaw.assist.data.api.ComposeAction {
        if (!compose.app.equals("sms", ignoreCase = true)) {
            return compose
        }
        if (!compose.body.isNullOrBlank()) {
            return compose
        }

        val resolution = ContactResolver.resolveForCompose(
            rawContact = compose.contact,
            explicitBody = null,
            contacts = availableContacts,
            hasPermission = ContactResolver.hasPermission(context),
            policy = com.meemaw.assist.agent.ComposeTargetPolicy.ANY_CONTACT_OR_NUMBER
        ) as? ContactResolution.Success ?: return compose

        val rawBody = resolution.remainingText?.trim().orEmpty()
        if (rawBody.length < 2) {
            return compose
        }

        val polished = repository.polishSmsBody(
            originalRequest = originalText,
            rawBody = rawBody,
            contactName = resolution.displayName
        )

        return if (polished.isNullOrBlank()) {
            compose
        } else {
            compose.copy(
                contact = resolution.displayName,
                body = polished
            )
        }
    }

    fun onShowMePhoto(photoUri: Uri, userHint: String, context: Context) {        persistenceContext = context.applicationContext
        val historyForPhoto = _messages.value.takeLast(12)
        val prompt = userHint.trim().ifBlank { "Show me this" }
        addMessage(MessageItem.User(prompt))
        _status.value = "Reading photo…"

        val appContext = context.applicationContext
        viewModelScope.launch {
            try {
                val result = showMeAnalyzer.analyze(
                    context = appContext,
                    photoUri = photoUri,
                    userHint = userHint,
                    history = historyForPhoto
                )
                _status.value = null
                val annotated = result.annotatedImagePath
                if (annotated != null) {
                    addMessage(MessageItem.AiImage(result.message, annotated))
                } else {
                    addMessage(MessageItem.Ai(result.message))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing show-me photo", e)
                _status.value = null
                addMessage(MessageItem.Ai("I couldn't understand that photo clearly. Please try again a little closer."))
            }
        }
    }

    private fun addMessage(item: MessageItem) {
        val current = currentSessionOrNull() ?: createSession().also { session ->
            currentSessionId = session.id
            chatSessions = listOf(session) + chatSessions
        }

        val updatedMessages = (current.messages + item).takeLast(120)
        val updatedSession = current.copy(
            messages = updatedMessages,
            updatedAt = System.currentTimeMillis()
        )

        chatSessions = (chatSessions.filterNot { it.id == updatedSession.id } + updatedSession)
            .sortedByDescending { it.updatedAt }
        _messages.value = updatedMessages
        refreshChatHistoryEntries()
        persistChatState()
    }

    private fun currentSession(): ChatSession {
        return currentSessionOrNull() ?: createSession().also { session ->
            currentSessionId = session.id
            chatSessions = listOf(session) + chatSessions
        }
    }

    private fun currentSessionOrNull(): ChatSession? {
        return chatSessions.firstOrNull { it.id == currentSessionId }
    }

    private fun createSession(now: Long = System.currentTimeMillis()): ChatSession {
        return ChatSession(
            id = "chat-$now",
            createdAt = now,
            updatedAt = now,
            messages = listOf(MessageItem.Ai(DEFAULT_WELCOME_MESSAGE))
        )
    }

    private fun refreshChatHistoryEntries() {
        _chatHistoryEntries.value = chatSessions
            .sortedByDescending { it.updatedAt }
            .map { session ->
                val meaningfulMessages = session.messages.filterNot { item ->
                    item is MessageItem.Ai && item.text == DEFAULT_WELCOME_MESSAGE
                }

                val firstUserText = meaningfulMessages
                    .filterIsInstance<MessageItem.User>()
                    .firstOrNull()
                    ?.text

                val lastPreview = meaningfulMessages
                    .lastOrNull()
                    ?.toPreviewText()
                    ?: DEFAULT_WELCOME_MESSAGE

                ChatHistoryEntry(
                    sessionId = session.id,
                    title = firstUserText?.toSingleLine(maxLength = 40)?.ifBlank { DEFAULT_NEW_CHAT_TITLE } ?: DEFAULT_NEW_CHAT_TITLE,
                    preview = lastPreview.toSingleLine(maxLength = 90),
                    isCurrent = session.id == currentSessionId
                )
            }
    }

    private fun persistChatState() {
        val appContext = persistenceContext ?: return
        val state = ChatHistoryState(
            currentSessionId = currentSessionId,
            sessions = chatSessions
        )

        viewModelScope.launch(Dispatchers.IO) {
            ChatHistoryStore.saveState(appContext, state)
        }
    }

    private fun ChatSession.hasMeaningfulMessages(): Boolean {
        return messages.any { item ->
            item !is MessageItem.Ai || item.text != DEFAULT_WELCOME_MESSAGE
        }
    }

    private fun MessageItem.toPreviewText(): String {
        return when (this) {
            is MessageItem.User -> text
            is MessageItem.Ai -> text
            is MessageItem.AiImage -> text
            is MessageItem.ScamWarning -> text
        }
    }

    private fun String.toSingleLine(maxLength: Int): String {
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
