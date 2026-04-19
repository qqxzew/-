package com.meemaw.assist.agent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kotlin.math.max

data class PhoneContact(
    val displayName: String,
    val phoneNumber: String
)

enum class ComposeTargetPolicy {
    ANY_CONTACT_OR_NUMBER,
    ONLY_TEST_CONTACT
}

sealed interface ContactResolution {
    data class Success(
        val displayName: String,
        val phoneNumber: String,
        val remainingText: String? = null
    ) : ContactResolution

    data object MissingPermission : ContactResolution
    data object NotFound : ContactResolution
    data object MissingQuery : ContactResolution
    data object Forbidden : ContactResolution
}

object ContactResolver {

    private const val MATCH_THRESHOLD = 0.72
    private val ALLOWED_CALL_CONTACT_KEYS = setOf("test", "тест")

    fun hasPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun loadContacts(context: Context): List<PhoneContact> {
        if (!hasPermission(context)) {
            return emptyList()
        }

        val contacts = mutableListOf<PhoneContact>()
        val seen = LinkedHashSet<String>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC"
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex)?.trim().orEmpty()
                val number = cursor.getString(numberIndex)?.trim().orEmpty()
                val normalizedNumber = normalizePhone(number)
                if (name.isBlank() || normalizedNumber.isBlank()) {
                    continue
                }

                val dedupeKey = "${normalize(name)}|$normalizedNumber"
                if (seen.add(dedupeKey)) {
                    contacts.add(PhoneContact(displayName = name, phoneNumber = normalizedNumber))
                }
            }
        }

        return contacts
    }

    fun resolveForCompose(
        rawContact: String?,
        explicitBody: String?,
        contacts: List<PhoneContact>,
        hasPermission: Boolean,
        policy: ComposeTargetPolicy = ComposeTargetPolicy.ANY_CONTACT_OR_NUMBER
    ): ContactResolution {
        val query = rawContact?.trim().orEmpty()
        val body = explicitBody?.trim().takeUnless { it.isNullOrBlank() }

        if (query.isBlank()) {
            return ContactResolution.MissingQuery
        }

        if (looksLikePhoneNumber(query)) {
            return when (policy) {
                ComposeTargetPolicy.ANY_CONTACT_OR_NUMBER -> resolvePhoneNumber(query, body, contacts)
                ComposeTargetPolicy.ONLY_TEST_CONTACT -> {
                    if (!hasPermission) {
                        ContactResolution.MissingPermission
                    } else if (contacts.isEmpty()) {
                        ContactResolution.NotFound
                    } else {
                        resolveAllowedPhoneNumber(query, body, contacts)
                    }
                }
            }
        }

        if (!hasPermission) {
            return ContactResolution.MissingPermission
        }

        if (contacts.isEmpty()) {
            return ContactResolution.NotFound
        }

        if (body != null) {
            val exact = resolveExactContact(query, contacts)
            if (exact != null) {
                return applyPolicy(exact.copy(remainingText = body), policy)
            }

            val leading = resolveLeadingContact(query, contacts)
            if (leading != null) {
                return applyPolicy(leading.copy(remainingText = body), policy)
            }
        } else {
            val leading = resolveLeadingContact(query, contacts)
            if (leading != null) {
                return applyPolicy(leading, policy)
            }

            val exact = resolveExactContact(query, contacts)
            if (exact != null) {
                return applyPolicy(exact, policy)
            }
        }

        return ContactResolution.NotFound
    }

    private fun resolvePhoneNumber(
        query: String,
        body: String?,
        contacts: List<PhoneContact>
    ): ContactResolution {
        val number = normalizePhone(query)
        if (number.isBlank()) {
            return ContactResolution.NotFound
        }

        val byNumber = contacts.firstOrNull { it.phoneNumber == number }
        return ContactResolution.Success(
            displayName = byNumber?.displayName ?: query.trim(),
            phoneNumber = byNumber?.phoneNumber ?: number,
            remainingText = body
        )
    }

    private fun resolveAllowedPhoneNumber(
        query: String,
        body: String?,
        contacts: List<PhoneContact>
    ): ContactResolution {
        val number = normalizePhone(query)
        if (number.isBlank()) {
            return ContactResolution.NotFound
        }

        val byNumber = contacts.firstOrNull { it.phoneNumber == number }
            ?: return ContactResolution.NotFound

        return applyPolicy(
            ContactResolution.Success(
                displayName = byNumber.displayName,
                phoneNumber = byNumber.phoneNumber,
                remainingText = body
            ),
            ComposeTargetPolicy.ONLY_TEST_CONTACT
        )
    }

    private fun applyPolicy(match: ContactResolution.Success, policy: ComposeTargetPolicy): ContactResolution {
        return when (policy) {
            ComposeTargetPolicy.ANY_CONTACT_OR_NUMBER -> match
            ComposeTargetPolicy.ONLY_TEST_CONTACT -> {
                if (isAllowedContact(match.displayName)) {
                    match
                } else {
                    ContactResolution.Forbidden
                }
            }
        }
    }

    private fun isAllowedContact(displayName: String): Boolean {
        val keys = searchKeys(displayName)
        return keys.any { key ->
            key.split(' ')
                .map { token -> token.trim() }
                .filter { token -> token.isNotBlank() }
                .any { token -> token in ALLOWED_CALL_CONTACT_KEYS }
        }
    }

    private fun resolveExactContact(query: String, contacts: List<PhoneContact>): ContactResolution.Success? {
        val match = contacts
            .map { contact -> contact to score(query, contact.displayName) }
            .maxByOrNull { it.second }
            ?: return null

        if (match.second < MATCH_THRESHOLD) {
            return null
        }

        return ContactResolution.Success(
            displayName = match.first.displayName,
            phoneNumber = match.first.phoneNumber,
            remainingText = null
        )
    }

    private fun resolveLeadingContact(query: String, contacts: List<PhoneContact>): ContactResolution.Success? {
        val candidates = buildLeadingCandidates(query)
        var bestMatch: Triple<PhoneContact, Double, String?>? = null

        for ((prefix, remainder) in candidates) {
            val match = contacts
                .map { contact -> contact to score(prefix, contact.displayName) }
                .maxByOrNull { it.second }
                ?: continue

            if (match.second < MATCH_THRESHOLD) {
                continue
            }

            val currentBest = bestMatch
            if (
                currentBest == null ||
                match.second > currentBest.second ||
                (match.second == currentBest.second && prefix.length > currentBest.first.displayName.length)
            ) {
                bestMatch = Triple(match.first, match.second, remainder)
            }
        }

        return bestMatch?.let { (contact, _, remainder) ->
            ContactResolution.Success(
                displayName = contact.displayName,
                phoneNumber = contact.phoneNumber,
                remainingText = remainder?.takeUnless { it.isBlank() }
            )
        }
    }

    private fun buildLeadingCandidates(text: String): List<Pair<String, String?>> {
        val words = text
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

        if (words.isEmpty()) {
            return emptyList()
        }

        val maxWords = minOf(4, words.size)
        val candidates = mutableListOf<Pair<String, String?>>()
        for (count in maxWords downTo 1) {
            val prefix = words.take(count).joinToString(" ")
            val remainder = words.drop(count).joinToString(" ").trim().ifBlank { null }
            candidates.add(prefix to remainder)
        }
        return candidates
    }

    private fun score(left: String, right: String): Double {
        val leftKeys = searchKeys(left)
        val rightKeys = searchKeys(right)
        var best = 0.0

        for (leftKey in leftKeys) {
            for (rightKey in rightKeys) {
                best = maxOf(best, scoreKey(leftKey, rightKey))
            }
        }

        return best
    }

    private fun searchKeys(value: String): Set<String> {
        val normalized = normalize(value)
        val transliterated = transliterate(normalized)
        return linkedSetOf(normalized, transliterated)
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun scoreKey(left: String, right: String): Double {
        if (left.isBlank() || right.isBlank()) {
            return 0.0
        }
        if (left == right) {
            return 1.0
        }
        if (right.startsWith(left) || left.startsWith(right)) {
            val minLength = minOf(left.length, right.length).toDouble()
            val maxLength = maxOf(left.length, right.length).toDouble()
            return 0.86 + (minLength / maxLength) * 0.1
        }

        val distance = damerauLevenshtein(left, right)
        val maxLength = max(left.length, right.length)
        if (maxLength == 0) {
            return 1.0
        }

        return 1.0 - (distance.toDouble() / maxLength.toDouble())
    }

    private fun looksLikePhoneNumber(value: String): Boolean {
        val digits = value.count { it.isDigit() }
        return digits >= 3
    }

    private fun normalizePhone(value: String): String {
        val trimmed = value.trim()
        val builder = StringBuilder(trimmed.length)
        for ((index, char) in trimmed.withIndex()) {
            if (char.isDigit()) {
                builder.append(char)
            } else if (char == '+' && index == 0) {
                builder.append(char)
            }
        }
        return builder.toString()
    }

    private fun normalize(value: String): String {
        return value
            .lowercase()
            .map { char -> if (char.isLetterOrDigit() || char == ' ') char else ' ' }
            .joinToString(separator = "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun transliterate(value: String): String {
        val builder = StringBuilder(value.length)
        for (char in value) {
            builder.append(
                when (char) {
                    'а' -> "a"
                    'б' -> "b"
                    'в' -> "v"
                    'г' -> "g"
                    'д' -> "d"
                    'е', 'ё' -> "e"
                    'ж' -> "zh"
                    'з' -> "z"
                    'и', 'й' -> "i"
                    'к' -> "k"
                    'л' -> "l"
                    'м' -> "m"
                    'н' -> "n"
                    'о' -> "o"
                    'п' -> "p"
                    'р' -> "r"
                    'с' -> "s"
                    'т' -> "t"
                    'у' -> "u"
                    'ф' -> "f"
                    'х' -> "h"
                    'ц' -> "ts"
                    'ч' -> "ch"
                    'ш' -> "sh"
                    'щ' -> "shch"
                    'ъ', 'ь' -> ""
                    'ы' -> "y"
                    'э' -> "e"
                    'ю' -> "yu"
                    'я' -> "ya"
                    else -> char.toString()
                }
            )
        }
        return builder.toString()
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