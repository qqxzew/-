package com.meemaw.assist.agent

import android.content.Context
import android.content.Intent
import android.net.Uri

object WebsiteLauncher {

    private val browserNavigationActions = setOf(
        "visit",
        "browse",
        "go",
        "go to",
        "зайди",
        "зайди на",
        "перейди",
        "перейди на"
    )

    private val actionKeywords = listOf(
        "open",
        "visit",
        "go",
        "go to",
        "browse",
        "show",
        "зайди",
        "зайди на",
        "открой",
        "открыть",
        "перейди",
        "перейди на",
        "покажи"
    )

    private val websiteKeywords = listOf(
        "site",
        "website",
        "web",
        "page",
        "domain",
        "browser",
        "online",
        "сайт",
        "сайте",
        "страница",
        "страницу",
        "домен",
        "веб",
        "браузер",
        "браузере"
    )

    private val fillerKeywords = setOf(
        "the",
        "a",
        "an",
        "to",
        "in",
        "on",
        "for",
        "official",
        "web",
        "website",
        "site",
        "page",
        "domain",
        "browser",
        "online",
        "сайт",
        "сайте",
        "страница",
        "страницу",
        "домен",
        "в",
        "во",
        "на",
        "браузер",
        "браузере",
        "официальный",
        "официальном"
    )

    private val knownDomains = mapOf(
        "google" to "https://www.google.com",
        "youtube" to "https://www.youtube.com",
        "github" to "https://github.com",
        "linkedin" to "https://www.linkedin.com",
        "facebook" to "https://www.facebook.com",
        "instagram" to "https://www.instagram.com",
        "reddit" to "https://www.reddit.com",
        "wikipedia" to "https://www.wikipedia.org",
        "netflix" to "https://www.netflix.com",
        "spotify" to "https://open.spotify.com",
        "amazon" to "https://www.amazon.com",
        "x" to "https://x.com",
        "twitter" to "https://x.com",
        "gmail" to "https://mail.google.com",
        "googlemaps" to "https://maps.google.com",
        "maps" to "https://maps.google.com",
        "yandex" to "https://yandex.ru",
        "wildberries" to "https://www.wildberries.ru",
        "ozon" to "https://www.ozon.ru",
        "rutube" to "https://rutube.ru"
    )

    private val explicitDomainRegex = Regex(
        pattern = """(?:(?:https?://)?(?:www\.)?)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+)""",
        option = RegexOption.IGNORE_CASE
    )

    fun tryHandle(text: String, context: Context): String? {
        val request = parse(text) ?: return null
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(request.url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
            "Opening ${request.label} in your browser ✓"
        } else {
            "I couldn't open that website right now."
        }
    }

    private fun parse(text: String): WebsiteRequest? {
        val trimmed = text.trim()
        if (trimmed.isBlank()) {
            return null
        }

        val words = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) {
            return null
        }

        val normalizedWords = words.map(::normalizeWord)
        val firstWord = normalizedWords.firstOrNull().orEmpty()
        val actionPhrase = normalizedWords.take(2).joinToString(" ").trim()
        val hasAction = actionKeywords.any { normalizeWord(it) == firstWord || normalizeWord(it) == actionPhrase }
        val hasBrowserAction = browserNavigationActions.any {
            val normalized = normalizeWord(it)
            normalized == firstWord || normalized == actionPhrase
        }
        val hasWebsiteHint = normalizedWords.any { word -> websiteKeywords.any { normalizeWord(it) == word } }
        val directDomain = explicitDomainRegex.find(trimmed)?.groupValues?.getOrNull(1)

        if (!hasAction && directDomain == null) {
            return null
        }

        if (directDomain != null) {
            val url = ensureHttps(directDomain)
            return WebsiteRequest(url = url, label = domainLabel(directDomain))
        }

        if (!hasWebsiteHint) {
            return null
        }

        val queryWords = words.filterIndexed { index, word ->
            val normalized = normalizedWords[index]
            actionKeywords.none { normalizeWord(it) == normalized } &&
                websiteKeywords.none { normalizeWord(it) == normalized } &&
                normalizeWord(word) !in fillerKeywords
        }

        val rawQuery = queryWords.joinToString(" ").trim()
        if (rawQuery.isBlank()) {
            return null
        }

        val normalizedQuery = normalizeWord(rawQuery)
        val compactQuery = normalizedQuery.replace(" ", "")
        val knownUrl = knownDomains[compactQuery]
        if (knownUrl != null) {
            return WebsiteRequest(url = knownUrl, label = rawQuery)
        }

        if (!hasWebsiteHint && !hasBrowserAction) {
            return null
        }

        if (normalizedQuery.split(' ').size == 1) {
            return WebsiteRequest(
                url = ensureHttps("www.$compactQuery.com"),
                label = rawQuery
            )
        }

        val searchUrl = "https://www.google.com/search?q=${Uri.encode("$rawQuery official site")}" 
        return WebsiteRequest(url = searchUrl, label = rawQuery)
    }

    private fun normalizeWord(value: String): String {
        return value
            .lowercase()
            .replace("ё", "е")
            .replace(Regex("[^\\p{L}\\p{Nd}.-]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    private fun ensureHttps(value: String): String {
        val normalized = value.trim()
        return if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
            normalized
        } else {
            "https://$normalized"
        }
    }

    private fun domainLabel(domain: String): String {
        return domain
            .removePrefix("www.")
            .substringBefore('.')
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    private data class WebsiteRequest(
        val url: String,
        val label: String
    )
}