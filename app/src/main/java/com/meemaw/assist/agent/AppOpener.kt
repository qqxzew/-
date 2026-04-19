package com.meemaw.assist.agent

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Client-side app opening — bypasses the LLM entirely for "open X" requests.
 * Much more reliable than asking the model to pick the right package name.
 */
object AppOpener {

    private const val TAG = "AppOpener"
    private const val MATCH_THRESHOLD = 0.74
    private const val OPEN_KEYWORD_SIMILARITY_THRESHOLD = 0.61

    private val CYRILLIC_TO_LATIN = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d",
        'е' to "e", 'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y",
        'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o",
        'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
        'ф' to "f", 'х' to "h", 'ц' to "c", 'ч' to "ch", 'ш' to "sh",
        'щ' to "sch", 'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e",
        'ю' to "yu", 'я' to "ya"
    )

    // Words that signal "open app" intent (Russian + English)
    private val OPEN_KEYWORDS = listOf(
        "открой", "открыть", "запусти", "запустить",
        "зайди в", "зайди на", "включи", "покажи",
        "перейди в", "перейди на",
        "open", "launch", "start", "run", "show"
    )

    private val QUESTION_PREFIXES = setOf(
        "how", "what", "why", "when", "where", "who", "which",
        "can", "could", "would", "should", "do", "does", "did",
        "is", "are", "am", "will", "may",
        "как", "что", "почему", "зачем", "где", "когда", "кто",
        "какой", "какая", "какие", "можно", "могу", "умею", "помоги"
    )

    /**
     * If the user message looks like an "open app" request, extract and return the app name.
     * Returns null if this is not an app-opening request.
     */
    fun extractAppName(text: String): String? {
        val t = normalize(text)
        if (t.isBlank()) return null
        if (looksLikeQuestion(t)) return null

        for (keyword in OPEN_KEYWORDS) {
            val normalizedKeyword = normalize(keyword)
            if (t.startsWith("$normalizedKeyword ")) {
                val appName = t.removePrefix(normalizedKeyword).trim()
                if (appName.isNotBlank()) return appName
            }
        }

        val words = t.split(' ').filter { it.isNotBlank() }
        if (words.size < 2) return null

        for (keyword in OPEN_KEYWORDS) {
            val keywordWords = normalize(keyword).split(' ').filter { it.isNotBlank() }
            if (words.size <= keywordWords.size) continue

            val candidate = words.take(keywordWords.size).joinToString(" ")
            val target = keywordWords.joinToString(" ")
            if (isLikelyOpenKeyword(candidate, target)) {
                val appName = words.drop(keywordWords.size).joinToString(" ").trim()
                if (appName.isNotBlank()) return appName
            }
        }

        return null
    }

    private fun isLikelyOpenKeyword(candidate: String, keyword: String): Boolean {
        if (candidate == keyword) return true
        if (candidate.isBlank() || keyword.isBlank()) return false
        if (candidate.firstOrNull() != keyword.firstOrNull()) return false

        val maxLength = max(candidate.length, keyword.length)
        val distance = damerauLevenshtein(candidate, keyword)
        val similarity = 1.0 - (distance.toDouble() / maxLength.toDouble())

        return when {
            maxLength <= 5 -> distance <= 1
            maxLength <= 8 -> distance <= 2 || similarity >= OPEN_KEYWORD_SIMILARITY_THRESHOLD
            else -> distance <= 3 || similarity >= 0.68
        }
    }

    private fun looksLikeQuestion(text: String): Boolean {
        val firstWord = text.substringBefore(' ').trim()
        if (firstWord in QUESTION_PREFIXES) {
            return true
        }

        return text.endsWith("?") || text.startsWith("how ") || text.startsWith("как ")
    }

    private fun damerauLevenshtein(left: String, right: String): Int {
        if (left == right) return 0
        if (left.isEmpty()) return right.length
        if (right.isEmpty()) return left.length

        val rows = left.length + 1
        val cols = right.length + 1
        val matrix = Array(rows) { IntArray(cols) }

        for (i in 0 until rows) matrix[i][0] = i
        for (j in 0 until cols) matrix[0][j] = j

        for (i in 1 until rows) {
            for (j in 1 until cols) {
                val cost = if (left[i - 1] == right[j - 1]) 0 else 1

                matrix[i][j] = min(
                    min(
                        matrix[i - 1][j] + 1,
                        matrix[i][j - 1] + 1
                    ),
                    matrix[i - 1][j - 1] + cost
                )

                if (
                    i > 1 &&
                    j > 1 &&
                    left[i - 1] == right[j - 2] &&
                    left[i - 2] == right[j - 1]
                ) {
                    matrix[i][j] = min(matrix[i][j], matrix[i - 2][j - 2] + 1)
                }
            }
        }

        return matrix[left.length][right.length]
    }

    private fun normalize(value: String): String {
        return value
            .lowercase()
            .replace('ё', 'е')
            .replace(Regex("[^\\p{L}\\p{Nd}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    private fun transliterate(value: String): String {
        var source = normalize(value)
            .replace("дж", "g")
            .replace("кс", "x")

        val builder = StringBuilder(source.length)
        for (char in source) {
            if (char == ' ') {
                builder.append(' ')
            } else {
                builder.append(CYRILLIC_TO_LATIN[char] ?: char)
            }
        }

        return builder.toString()
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    private fun buildSearchKeys(value: String): Set<String> {
        val normalized = normalize(value)
        if (normalized.isBlank()) return emptySet()

        val transliterated = transliterate(normalized)
        return buildSet {
            add(normalized)
            add(normalized.replace(" ", ""))
            add(transliterated)
            add(transliterated.replace(" ", ""))

            normalized.split(' ')
                .filter { it.length > 1 }
                .forEach { add(it) }

            transliterated.split(' ')
                .filter { it.length > 1 }
                .forEach { add(it) }
        }
    }

    private fun buildPackageKeys(packageName: String): Set<String> {
        val parts = packageName.lowercase()
            .split('.', '_', '-')
            .filter { it.length > 1 }

        return buildSet {
            add(packageName.lowercase())
            add(packageName.lowercase().replace(".", ""))

            for (part in parts) {
                add(part)
                add(normalize(part).replace(" ", ""))
                add(transliterate(part).replace(" ", ""))
            }
        }
    }

    private fun scoreKey(queryKey: String, candidateKey: String): Double {
        if (queryKey.isBlank() || candidateKey.isBlank()) return 0.0

        if (queryKey == candidateKey) return 1.0

        val maxLength = max(queryKey.length, candidateKey.length)
        val delta = abs(queryKey.length - candidateKey.length).toDouble() / maxLength.toDouble()

        if (candidateKey.startsWith(queryKey) || queryKey.startsWith(candidateKey)) {
            return 0.94 - (delta * 0.10)
        }

        if (candidateKey.contains(queryKey) || queryKey.contains(candidateKey)) {
            return 0.86 - (delta * 0.12)
        }

        if (min(queryKey.length, candidateKey.length) < 4) return 0.0

        val distance = levenshtein(queryKey, candidateKey)
        val similarity = 1.0 - (distance.toDouble() / maxLength.toDouble())
        return if (similarity >= MATCH_THRESHOLD) similarity else 0.0
    }

    private fun levenshtein(left: String, right: String): Int {
        if (left == right) return 0
        if (left.isEmpty()) return right.length
        if (right.isEmpty()) return left.length

        val costs = IntArray(right.length + 1) { it }
        for (i in left.indices) {
            var previousDiagonal = i
            costs[0] = i + 1

            for (j in right.indices) {
                val current = costs[j + 1]
                val substitutionCost = if (left[i] == right[j]) 0 else 1

                costs[j + 1] = min(
                    min(costs[j + 1] + 1, costs[j] + 1),
                    previousDiagonal + substitutionCost
                )

                previousDiagonal = current
            }
        }

        return costs[right.length]
    }

    private fun scoreApp(queryKeys: Set<String>, app: InstalledApp): Double {
        val appKeys = buildSearchKeys(app.label) + buildPackageKeys(app.packageName)
        var bestScore = 0.0

        for (queryKey in queryKeys) {
            for (appKey in appKeys) {
                bestScore = max(bestScore, scoreKey(queryKey, appKey))
            }
        }

        return bestScore
    }

    /**
     * Find the best matching installed app for the given user query.
     * Returns the first match or null.
     */
    fun findBestMatch(query: String, installedApps: List<InstalledApp>): InstalledApp? {
        if (installedApps.isEmpty()) return null
        val queryKeys = buildSearchKeys(query)
        if (queryKeys.isEmpty()) return null

        return installedApps
            .map { app -> app to scoreApp(queryKeys, app) }
            .filter { (_, score) -> score >= MATCH_THRESHOLD }
            .maxWithOrNull(compareBy<Pair<InstalledApp, Double>> { it.second }.thenByDescending { it.first.label.length })
            ?.first
    }

    /**
     * Try to open an app by user query against the installed apps list.
     * Returns the app label if successfully launched, or null if not found.
     */
    fun tryOpen(query: String, installedApps: List<InstalledApp>, context: Context): String? {
        val match = findBestMatch(query, installedApps) ?: return null
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(match.packageName)
                ?: return null
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            match.label
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch ${match.packageName}", e)
            null
        }
    }
}
