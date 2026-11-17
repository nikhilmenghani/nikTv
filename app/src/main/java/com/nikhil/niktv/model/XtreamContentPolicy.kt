package com.nikhil.niktv.model

import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
enum class UnclassifiedContent { REVIEW, SHOW, HIDE }

@Serializable
enum class ContentOverride { ALLOW, HIDE }

/** Viewer-owned preferences, independent of connection credentials and page-level categories. */
@Serializable
data class XtreamContentPolicy(
    val enabled: Boolean = false,
    val languages: Set<String> = setOf("hi", "en"),
    val interests: Set<String> = setOf("entertainment", "news"),
    val sports: Set<String> = setOf("cricket"),
    val devotional: Set<String> = setOf("hindu"),
    val includeDubbed: Boolean = true,
    val includeReplays: Boolean = true,
    val reviewedKidsOnly: Boolean = false,
    val unclassified: UnclassifiedContent = UnclassifiedContent.REVIEW,
    val reviewNewCategories: Boolean = true,
    val knownCategories: Map<String, Set<String>> = emptyMap(),
    val categoryOverrides: Map<String, ContentOverride> = emptyMap(),
    val itemOverrides: Map<String, ContentOverride> = emptyMap()
) {
    companion object {
        fun parents() = XtreamContentPolicy(enabled = true, languages = setOf("hi"),
            interests = setOf("entertainment", "news", "devotional"), sports = emptySet())
        fun kids() = XtreamContentPolicy(enabled = true, interests = setOf("kids"), sports = emptySet(),
            reviewedKidsOnly = true, unclassified = UnclassifiedContent.HIDE)
        fun categoryKey(type: CatalogType, id: String) = "${type.name}:$id"
        fun overrideKey(connection: String, type: CatalogType, id: String) = "$connection:${categoryKey(type, id)}"
    }
}

internal data class XtreamContentFacts(
    val languages: Set<String> = emptySet(),
    val interest: String? = null,
    val sports: Set<String> = emptySet(),
    val devotional: String? = null,
    val dubbed: Boolean = false,
    val mixed: Boolean = false
)

/** Category/title rules are compiled locally. No network or TMDB calls in the filtering path. */
internal class XtreamContentFilter(val policy: XtreamContentPolicy, private val connection: String) {
    private val facts = HashMap<Pair<CatalogType, String>, XtreamContentFacts>()
    private fun facts(title: String, type: CatalogType) = facts.getOrPut(type to title) { classify(title, type) }
    private fun override(type: CatalogType, id: String) = policy.categoryOverrides[
        XtreamContentPolicy.overrideKey(connection, type, id)]
    private fun isNew(category: Category): Boolean {
        if (category.id.isBlank()) return false
        val baseline = policy.knownCategories[connection] ?: return false
        // An uncached media type establishes its own baseline when saved from settings.
        val prefix = "${category.type.name}:"
        return policy.reviewNewCategories && baseline.any { it.startsWith(prefix) } &&
            XtreamContentPolicy.categoryKey(category.type, category.id) !in baseline
    }

    fun allowsCategory(category: Category): Boolean {
        if (!policy.enabled) return true
        if (category.sourceCategoryIds.isNotEmpty() || category.id == "*") return true
        if (policy.reviewedKidsOnly && category.isAdult) return false
        when (override(category.type, category.id)) {
            ContentOverride.HIDE -> return false
            ContentOverride.ALLOW -> return true
            null -> Unit
        }
        if (isNew(category)) return false
        val f = facts(category.title, category.type)
        // Mixed folders must be inspected per item (e.g. Aastha in INDIA | SD).
        if (f.mixed) return true
        if (f.languages.isNotEmpty() && f.languages.none { it in policy.languages }) return false
        if (f.dubbed && !policy.includeDubbed) return false
        if (f.interest != null && !interestAllowed(f, category.type)) return false
        return true
    }

    fun allows(item: MediaItem, type: CatalogType, category: Category? = null): Boolean {
        if (!policy.enabled) return true
        if (policy.reviewedKidsOnly && (item.isAdult || category?.isAdult == true)) return false
        when (policy.itemOverrides[XtreamContentPolicy.overrideKey(connection, type, item.id)]) {
            ContentOverride.HIDE -> return false
            ContentOverride.ALLOW -> return true
            null -> Unit
        }
        if (category != null) {
            when (override(type, category.id)) {
                ContentOverride.HIDE -> return false
                ContentOverride.ALLOW -> return true
                null -> Unit
            }
            if (isNew(category)) return false
        }
        val cat = facts(category?.title.orEmpty(), type)
        // Channel names carry useful language/topic labels. A film called "English
        // Vinglish" or "News of the World" must not be classified by those ordinary words.
        val title = if (type == CatalogType.LIVE_TV) facts(item.title, type)
            else explicitTitleLanguages(item.title, type)
        val metadataLanguages = item.contentLanguage?.let { raw ->
            if (raw.lowercase(Locale.ROOT) in languageWords) setOf(raw.lowercase(Locale.ROOT)) else classify(raw, type).languages
        }.orEmpty()
        val language = when {
            title.dubbed && title.languages.isNotEmpty() -> title.languages
            cat.dubbed -> cat.languages
            else -> metadataLanguages.ifEmpty { title.languages }.ifEmpty { cat.languages }
        }
        if (language.isEmpty()) {
            if (policy.unclassified != UnclassifiedContent.SHOW) return false
        } else if (language.none { it in policy.languages }) return false
        if ((title.dubbed || cat.dubbed) && !policy.includeDubbed) return false
        val genres = normalized(item.contentGenres.joinToString(" "))
        val topic = title.interest ?: when {
            "KIDS" in genres -> "kids"
            cat.interest != null -> cat.interest
            genres.isNotBlank() -> "entertainment"
            else -> null
        }
        val combined = cat.copy(interest = topic,
            sports = title.sports.ifEmpty { cat.sports }, devotional = title.devotional ?: cat.devotional)
        if (topic == null) {
            if (policy.unclassified != UnclassifiedContent.SHOW) return false
        } else if (!interestAllowed(combined, type)) return false
        if (policy.reviewedKidsOnly || (topic == "kids" && policy.interests == setOf("kids"))) {
            // Provider KIDS/animation labels do not establish suitability. Use known sources
            // or an explicit reviewed exception; unknown films remain outside a kids profile.
            if (!reviewedKidsSource(item, category, type)) return false
        }
        return true
    }

    private fun interestAllowed(f: XtreamContentFacts, type: CatalogType): Boolean = when (f.interest) {
        "sports" -> (type == CatalogType.LIVE_TV || policy.includeReplays) &&
            if (f.sports.isEmpty()) "other" in policy.sports else f.sports.any { it in policy.sports }
        "devotional" -> "devotional" in policy.interests && f.devotional?.let { it in policy.devotional } == true
        else -> f.interest in policy.interests
    }

    private fun reviewedKidsSource(item: MediaItem, category: Category?, type: CatalogType): Boolean {
        val title = normalized(item.title)
        val source = normalized(category?.title.orEmpty())
        if (source == "CBEEBIES") return true
        return when (type) {
            CatalogType.LIVE_TV -> listOf("CBEEBIES", "NICK JR", "NICK JUNIOR", "DISNEY JR", "DISNEY JUNIOR",
                "DISCOVERY KIDS", "SONY YAY", "POGO").any { it in title }
            CatalogType.SERIES -> listOf("BLUEY", "PEPPA PIG", "PAW PATROL", "SUPERTATO", "TITIPO TITIPO",
                "DOUG UNPLUGS", "MY LITTLE PONY").any { title == it || title.startsWith("$it ") }
            else -> false
        }
    }

    companion object {
        private val separators = Regex("[^\\p{L}\\p{N}]+")
        private fun normalized(value: String) = value.uppercase(Locale.ROOT).replace(separators, " ").trim()
        private val languageWords = linkedMapOf(
            "hi" to listOf("HINDI", "BOLLYWOOD"), "en" to listOf("ENGLISH", "HOLLYWOOD"),
            "ar" to listOf("ARABIC", "ARAB"), "gu" to listOf("GUJARATI"), "pa" to listOf("PUNJABI"),
            "ta" to listOf("TAMIL"), "te" to listOf("TELUGU"), "ml" to listOf("MALAYALAM"),
            "mr" to listOf("MARATHI"), "bn" to listOf("BANGLA", "BENGALI"), "kn" to listOf("KANNADA"),
            "ur" to listOf("URDU", "PAKISTANI"), "tr" to listOf("TURKISH"), "ko" to listOf("KOREAN"),
            "ja" to listOf("JAPANESE"), "fr" to listOf("FRENCH"), "de" to listOf("GERMAN"),
            "es" to listOf("SPANISH"), "pt" to listOf("PORTUGUESE"), "ru" to listOf("RUSSIAN"),
            "it" to listOf("ITALIAN"), "ne" to listOf("NEPALI"), "or" to listOf("ODIA", "ORIYA"),
            "as" to listOf("ASSAMESE", "ASOMIYA"), "bho" to listOf("BHOJPURI")
        )
        private val subtitleLanguage = Regex("\\b(?:${languageWords.values.flatten().joinToString("|")}) (?:SUBTITLES|SUBTITLE|SUBBED)\\b")
        private val languageLabel = Regex("\\[([^]]+)]|\\(([^)]+)\\)")
        private val languagePrefix = Regex("^(?:${languageWords.values.flatten().joinToString("|")})\\s*[:|]", RegexOption.IGNORE_CASE)
        private fun explicitTitleLanguages(title: String, type: CatalogType): XtreamContentFacts {
            val labels = languageLabel.findAll(title).map { it.groupValues.drop(1).firstOrNull(String::isNotEmpty).orEmpty() }.toList()
            val prefix = languagePrefix.find(title)?.value
            val f = classify((labels + listOfNotNull(prefix)).joinToString(" "), type)
            return f.copy(interest = null, sports = emptySet(), devotional = null)
        }
        private val hindiNetworks = listOf("SONY SAB", "SAB", "SONY SET", "SONY PAL", "STAR PLUS", "STARPLUS", "COLORS",
            "STAR BHARAT", "ZEE TV", "ZEETV", "ZEE ANMOL", "ZEE BOLLYWOOD", "ZEE ACTION", "COLORS RISHTEY",
            "DANGAL", "STAR UTSAV", "SHEMAROO UMANG", "SUN NEO HINDI", "HINDI TV SERIES")
        private val hinduChannels = listOf("AASTHA", "ASTHA", "SANSKAR", "JINVANI", "DARSHAN", "BHAJAN", "BHAKTI")
        internal fun classify(value: String, type: CatalogType): XtreamContentFacts {
            var text = normalized(value)
            val mixed = "MULTI LANGUAGE" in text || "MULTILANGUAGE" in text ||
                text in setOf("INDIAN GENERAL", "INDIA SD", "INDIA ACTIVE", "INDIA KIDS", "KIDS", "KIDS MOVIES", "KIDS CAM") ||
                listOf("HOTSTAR", "NETFLIX", "AMAZON", "ZEE5", "SONY LIV", "JIO", "MX PLAYER", "DISNEY", "VOOT").any { it in text }
            // The language attached to SUBTITLES is not the audio language.
            text = text.replace(subtitleLanguage, "")
            val words = text.split(' ').toSet()
            var languages = languageWords.filterValues { aliases -> aliases.any { it in words } }.keys
            val dubbed = "DUBBED" in words
            if (dubbed) {
                val target = languageWords.entries.firstOrNull { (_, names) -> names.any { "$it DUBBED" in text } }?.key
                if (target != null) languages = setOf(target)
            }
            if ("MULTI LANGUAGE" in text || "MULTILANGUAGE" in text) languages = emptySet()
            if (languages.isEmpty() && type == CatalogType.SERIES && hindiNetworks.any { it == text || it in text }) languages = setOf("hi")
            if (languages.isEmpty() && type == CatalogType.LIVE_TV && (hindiNetworks.any { it in text } || hinduChannels.any { it in words })) languages = setOf("hi")
            if (languages.isEmpty() && ("CBEEBIES" in words || listOf("WILLOW", "FOX CRIC", "SKY SPORTS", "ASTRO CRICKET").any { it in text })) languages = setOf("en")
            if (languages.isEmpty() && type == CatalogType.MOVIES && text.startsWith("INDIAN ") && text != "INDIAN GENERAL") languages = setOf("hi")
            val devotional = when {
                hinduChannels.any { it in words } || "HINDU" in words -> "hindu"
                "ISLAMIC" in words || "QURAN" in words || "IBAADAT" in words -> "islamic"
                "CHRISTIAN" in words -> "christian"
                else -> null
            }
            val sports = buildSet {
                if (listOf("CRICKET", "CRIC", "WILLOW").any { it in words }) add("cricket")
                if (listOf("FOOTBALL", "SOCCER", "EPL", "MLS", "FIFA", "UEFA").any { it in words }) add("football")
                if ("TENNIS" in words) add("tennis")
                if ("F1" in words || "FORMULA" in words || "MOTOGP" in words) add("motorsport")
                if (listOf("WWE", "UFC", "AEW", "WRESTLING").any { it in words }) add("combat")
                if ("GOLF" in words) add("golf")
                if ("RUGBY" in words) add("rugby")
                if ("NBA" in words || "BASKETBALL" in words) add("basketball")
                if ("MLB" in words || "BASEBALL" in words) add("baseball")
                if ("NFL" in words) add("american-football")
            }
            val interest = when {
                devotional != null -> "devotional"
                sports.isNotEmpty() || "SPORTS" in words || "PPV" in words || "ESPN" in text -> "sports"
                "KIDS" in words || "CBEEBIES" in words || "NICK" in words || "NICKELODEON" in words || "POGO" in words -> "kids"
                "NEWS" in words -> "news"
                listOf("MOVIES", "MOVIE", "SERIES", "ENTERTAINMENT", "COMEDY", "DRAMA", "MUSIC", "DOCUMENTARY", "DOCUMENTARIES").any { it in words } ||
                    hindiNetworks.any { it in text } || type in listOf(CatalogType.MOVIES, CatalogType.SERIES) && languages.isNotEmpty() -> "entertainment"
                else -> null
            }
            return XtreamContentFacts(languages, interest, sports, devotional, dubbed, mixed)
        }
    }
}
