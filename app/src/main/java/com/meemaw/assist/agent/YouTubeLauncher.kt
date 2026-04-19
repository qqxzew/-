package com.meemaw.assist.agent

import android.content.Context
import android.content.Intent
import android.net.Uri

object YouTubeLauncher {

    private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    private const val DEFAULT_MUSIC_QUERY = "music mix"
    private const val DEFAULT_VIDEO_QUERY = "popular videos"

    private val actionKeywords = listOf(
        "open",
        "play",
        "search",
        "find",
        "show",
        "watch",
        "listen",
        "start",
        "открой",
        "включи",
        "запусти",
        "найди",
        "поищи",
        "покажи",
        "послушай"
    )

    private val youtubeKeywords = listOf("youtube", "ютуб", "ютюбе", "ютубе")
    private val videoKeywords = listOf("video", "videos", "видео", "ролик", "клип")
    private val musicKeywords = listOf("music", "song", "songs", "playlist", "музыка", "музыку", "песня", "песню", "плейлист")
    private val randomKeywords = listOf("random", "рандом", "случайный")

    private val fillerKeywords = setOf(
        "in",
        "on",
        "for",
        "the",
        "a",
        "an",
        "to",
        "на",
        "в",
        "во",
        "ютуб",
        "ютубе",
        "ютюбе",
        "youtube",
        "video",
        "videos",
        "видео"
    )

    fun tryHandle(text: String, context: Context): String? {
        val command = parse(text) ?: return null

        val query = command.query.trim()
        val pm = context.packageManager

        val intent = if (query.isNotBlank()) {
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")
            ).apply {
                setPackage(YOUTUBE_PACKAGE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            pm.getLaunchIntentForPackage(YOUTUBE_PACKAGE)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        if (intent != null && intent.resolveActivity(pm) != null) {
            context.startActivity(intent)
            return if (query.isBlank()) {
                "Opening YouTube ✓"
            } else {
                "Opening YouTube for $query ✓"
            }
        }

        val uri = if (query.isBlank()) {
            Uri.parse("https://www.youtube.com/")
        } else {
            Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")
        }

        val webIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (webIntent.resolveActivity(pm) != null) {
            context.startActivity(webIntent)
            return if (query.isBlank()) {
                "Opening YouTube in your browser ✓"
            } else {
                "Opening YouTube search for $query ✓"
            }
        }

        return "I couldn't open YouTube right now."
    }

    private fun parse(text: String): YouTubeCommand? {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) {
            return null
        }

        val normalizedWords = words.map(::normalizeWord)
        val hasAction = normalizedWords.take(2).any { matchesKeyword(it, actionKeywords) }
        val hasYouTube = normalizedWords.any { matchesKeyword(it, youtubeKeywords) }
        val hasVideo = normalizedWords.any { matchesKeyword(it, videoKeywords) }
        val hasMusic = normalizedWords.any { matchesKeyword(it, musicKeywords) }
        val hasRandom = normalizedWords.any { matchesKeyword(it, randomKeywords) }

        if (!hasAction && !(hasMusic && !hasYouTube)) {
            return null
        }

        if (!hasYouTube && !hasMusic && !hasVideo) {
            return null
        }

        val remainingWords = words.filterIndexed { index, word ->
            val normalized = normalizedWords[index]
            !matchesKeyword(normalized, actionKeywords) &&
                !matchesKeyword(normalized, youtubeKeywords) &&
                normalizeWord(word) !in fillerKeywords
        }

        val rawQuery = remainingWords.joinToString(" ").trim()
        val query = when {
            rawQuery.isBlank() && hasMusic -> DEFAULT_MUSIC_QUERY
            rawQuery.isBlank() && hasVideo -> DEFAULT_VIDEO_QUERY
            hasRandom && hasMusic -> DEFAULT_MUSIC_QUERY
            isGenericMusicQuery(rawQuery) -> DEFAULT_MUSIC_QUERY
            else -> rawQuery
        }

        if (query.isBlank() && !hasYouTube) {
            return null
        }

        return YouTubeCommand(query = query)
    }

    private fun isGenericMusicQuery(query: String): Boolean {
        val normalized = normalizeWord(query)
        return matchesKeyword(normalized, musicKeywords)
    }

    private fun normalizeWord(value: String): String {
        return value
            .lowercase()
            .filter { it.isLetterOrDigit() }
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
                val maxLength = maxOf(candidate.length, normalizedKeyword.length)
                // Require exact match for short words to avoid false positives
                // (e.g. "son" → "song", "call" → "play").
                if (maxLength < 6) {
                    false
                } else {
                    val distance = damerauLevenshtein(candidate, normalizedKeyword)
                    val similarity = 1.0 - (distance.toDouble() / maxLength.toDouble())
                    similarity >= 0.75
                }
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

    private data class YouTubeCommand(
        val query: String
    )
}