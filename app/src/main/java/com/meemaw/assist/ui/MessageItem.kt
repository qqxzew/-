package com.meemaw.assist.ui

sealed class MessageItem {
    data class User(val text: String) : MessageItem()
    data class Ai(val text: String) : MessageItem()
    data class AiImage(val text: String, val imagePath: String) : MessageItem()
    data class ScamWarning(val text: String) : MessageItem()
}
