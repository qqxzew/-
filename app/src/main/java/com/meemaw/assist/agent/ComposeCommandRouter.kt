package com.meemaw.assist.agent

import com.meemaw.assist.data.api.ComposeAction
import kotlin.math.max

object ComposeCommandRouter {

    private val callKeywords = listOf(
        "call",
        "dial",
        "phone",
        "ring",
        "позвони",
        "позвонить",
        "звони",
        "набери",
        "набрать",
        "вызови"
    )

    private val messageKeywords = listOf(
        "sms",
        "text",
        "message",
        "msg",
        "напиши",
        "смс",
        "смску",
        "эсэмэс",
        "сообщение",
        "текст"
    )

    private val sendKeywords = listOf("send", "write", "отправь", "скинь")
    private val targetLeadWords = setOf(
        "to",
        "contact",
        "contacts",
        "person",
        "number",
        "my",
        "the",
        "a",
        "an",
        "please",
        "контакт",
        "контакту",
        "контактом",
        "кому",
        "на",
        "номер",
        "номеру",
        "моему",
        "моей",
        "мой",
        "моя",
        "моим",
        "пожалуйста"
    ).map(::normalizeWord).toSet()

    fun parse(text: String): ComposeAction? {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) {
            return null
        }

        val first = normalizeWord(words[0])
        if (matchesKeyword(first, callKeywords)) {
            val remainder = cleanComposeRemainder(words.drop(1))
            return ComposeAction(app = "call", contact = remainder.ifBlank { null }, body = null)
        }

        if (matchesKeyword(first, messageKeywords)) {
            val remainder = cleanComposeRemainder(words.drop(1))
            return ComposeAction(app = "sms", contact = remainder.ifBlank { null }, body = null)
        }

        if (matchesKeyword(first, sendKeywords) && words.size >= 2) {
            val second = normalizeWord(words[1])
            if (matchesKeyword(second, messageKeywords)) {
                val remainder = cleanComposeRemainder(words.drop(2))
                return ComposeAction(app = "sms", contact = remainder.ifBlank { null }, body = null)
            }
        }

        return null
    }

    private fun normalizeWord(value: String): String {
        return value
            .lowercase()
            .filter { it.isLetterOrDigit() }
    }

    private fun cleanComposeRemainder(words: List<String>): String {
        var remainingWords = words
            .joinToString(" ")
            .trim()
            .trimStart(':', ',', ';', '-', '—')
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

        while (remainingWords.isNotEmpty()) {
            val normalizedFirst = normalizeWord(remainingWords.first())
            if (normalizedFirst !in targetLeadWords) {
                break
            }
            remainingWords = remainingWords.drop(1)
        }

        return remainingWords.joinToString(" ").trim()
    }

    private fun matchesKeyword(candidate: String, keywords: List<String>): Boolean {
        if (candidate.isBlank()) {
            return false
        }

        return keywords.any { keyword ->
            val normalizedKeyword = normalizeWord(keyword)
            if (candidate == normalizedKeyword) {
                true
            } else {
                val distance = damerauLevenshtein(candidate, normalizedKeyword)
                val maxLength = max(candidate.length, normalizedKeyword.length)
                val similarity = 1.0 - (distance.toDouble() / maxLength.toDouble())
                similarity >= 0.61
            }
        }
    }

    private fun damerauLevenshtein(left: String, right: String): Int {
        if (left == right) {
            return 0
        }
        if (left.isEmpty()) {
            return right.length
        }
        if (right.isEmpty()) {
            return left.length
        }

        val distances = Array(left.length + 1) { IntArray(right.length + 1) }

        for (i in 0..left.length) {
            distances[i][0] = i
        }
        for (j in 0..right.length) {
            distances[0][j] = j
        }

        for (i in 1..left.length) {
            for (j in 1..right.length) {
                val cost = if (left[i - 1] == right[j - 1]) 0 else 1
                var value = minOf(
                    distances[i - 1][j] + 1,
                    distances[i][j - 1] + 1,
                    distances[i - 1][j - 1] + cost
                )

                if (
                    i > 1 &&
                    j > 1 &&
                    left[i - 1] == right[j - 2] &&
                    left[i - 2] == right[j - 1]
                ) {
                    value = minOf(value, distances[i - 2][j - 2] + cost)
                }

                distances[i][j] = value
            }
        }

        return distances[left.length][right.length]
    }
}