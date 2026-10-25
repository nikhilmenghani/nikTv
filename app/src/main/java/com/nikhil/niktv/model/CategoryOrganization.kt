package com.nikhil.niktv.model

import com.nikhil.niktv.data.adultContentLabel
import java.security.MessageDigest
import java.util.Locale

enum class CategoryBrowseMode(val label: String) {
    COLLECTIONS("Collections"), CUSTOM("My collections"), LANGUAGE("Language"), REGION("Region"),
    TOPIC("Genre & theme"), SERVICE("Service & network"), QUALITY("Quality"), PROVIDER("Provider categories")
}

/** Facets describe provider category labels, never a claim about a stream's audio tracks. */
object CategoryOrganization {
    private val languages = listOf("English", "Hindi", "Punjabi", "Tamil", "Telugu", "Malayalam",
        "Kannada", "Marathi", "Gujarati", "Bengali", "Bangla", "Urdu", "Arabic", "Turkish",
        "Korean", "Odia", "Assamese", "Bhojpuri", "Afrikaans")
    private val regions = linkedMapOf("INDIA" to "India", "INDIAN" to "India", "BOLLYWOOD" to "India",
        "PAKISTAN" to "Pakistan", "PAKISTANI" to "Pakistan", "UK" to "UK", "USA" to "USA",
        "US" to "USA", "CANADA" to "Canada", "AUSTRALIA" to "Australia", "AUSTRALIAN" to "Australia",
        "NEW ZEALAND" to "New Zealand", "EUROPE" to "Europe", "MIDDLE EAST" to "Middle East",
        "AFRICA" to "Africa", "SRI LANKA" to "Sri Lanka", "NEPAL" to "Nepal", "UAE" to "UAE",
        "SAUDI ARABIA" to "Saudi Arabia", "TURKEY" to "Turkey", "RUSSIA" to "Russia",
        "FRANCE" to "France", "GERMANY" to "Germany", "SPAIN" to "Spain", "ITALY" to "Italy",
        "DENMARK" to "Denmark", "NORWAY" to "Norway", "FINLAND" to "Finland", "SWEDEN" to "Sweden",
        "ROMANIA" to "Romania", "BULGARIA" to "Bulgaria", "GREEK" to "Greece", "ALBANIA" to "Albania",
        "QATAR" to "Qatar", "KUWAIT" to "Kuwait", "OMAN" to "Oman", "IRAQ" to "Iraq", "SYRIA" to "Syria",
        "LEBANON" to "Lebanon", "IRAN" to "Iran", "ISRAEL" to "Israel", "INDONESIA" to "Indonesia",
        "PHILIPPINES" to "Philippines", "AFGHANISTAN" to "Afghanistan", "MOROCCO" to "Morocco",
        "ALGERIA" to "Algeria", "EGYPT" to "Egypt", "CARIBBEAN" to "Caribbean", "BRASIL" to "Brazil")
    private val topics = linkedMapOf("SPORTS|CRICKET|EPL|NFL|MLB|NBA|FIFA|WWE|UFC" to "Sports",
        "NEWS" to "News", "KIDS|CHILDREN|CBEEBIES" to "Kids", "COMEDY" to "Comedy",
        "HORROR" to "Horror", "DOCUMENTARY|DOCUMENTARIES|HISTORY" to "Documentaries",
        "MUSIC|SINGERS" to "Music", "DRAMA" to "Drama", "CRIME|THRILLER" to "Crime & thriller",
        "RELIGIOUS|ISLAMIC|CHRISTIAN" to "Faith", "MOVIES|MOVIE" to "Movies",
        "SERIES" to "Series", "REALITY" to "Reality shows", "ENTERTAINMENT" to "Entertainment")
    private val services = linkedMapOf("NETFLIX" to "Netflix", "AMAZON|PRIME" to "Prime Video",
        "DISNEY|HOTSTAR" to "Disney / Hotstar", "HBO" to "HBO", "SONY|SAB" to "Sony",
        "ZEE5|ZEE|ZEETV" to "Zee", "STAR" to "Star", "STARZ" to "Starz", "COLORS" to "Colors",
        "JIO" to "Jio", "APPLE TV" to "Apple TV+", "PARAMOUNT" to "Paramount+",
        "BBC" to "BBC", "HUM" to "Hum TV", "ARY" to "ARY", "GEO" to "Geo",
        "SUN" to "Sun", "ETV" to "ETV", "MX PLAYER" to "MX Player")

    private val patterns = java.util.concurrent.ConcurrentHashMap<String, Regex>()
    private fun has(text: String, words: String): Boolean = patterns.getOrPut(words) {
        Regex("(?<![A-Z0-9])(?:" + words.split('|').joinToString("|") { Regex.escape(it) } + ")(?![A-Z0-9])")
    }.containsMatchIn(text)

    fun labels(category: Category, mode: CategoryBrowseMode): Set<String> {
        val text = category.title.uppercase(Locale.ROOT)
        return when (mode) {
            CategoryBrowseMode.LANGUAGE -> buildSet {
                languages.filter { has(text, it.uppercase(Locale.ROOT)) }.forEach { add(it) }
                if (has(text, "MULTI LANGUAGE|MULTI-LANGUAGE|MULTILANGUAGE|MULTILINGUAL")) add("Multiple languages")
                if (has(text, "DUBBED")) add("Dubbed")
            }
            CategoryBrowseMode.REGION -> regions.filterKeys { has(text, it) }.values.toSet()
            CategoryBrowseMode.TOPIC -> buildSet {
                topics.filterKeys { has(text, it) }.values.forEach { add(it) }
                if (Regex("24\\s*[/Xx]\\s*7").containsMatchIn(text)) add("24/7 channels")
                if (has(text, "LIVE ONLY|LIVE EVENT")) add("Live events")
                if (has(text, "COLLECTION")) add("Film collections")
            }
            CategoryBrowseMode.SERVICE -> services.filterKeys { has(text, it) }.values.toSet()
            CategoryBrowseMode.QUALITY -> listOf("4K", "8K", "UHD", "FHD", "HD", "SD", "CAM")
                .filter { has(text, it) }.toSet()
            else -> emptySet()
        }
    }

    fun organize(categories: List<Category>, mode: CategoryBrowseMode): List<Category> {
        if (mode == CategoryBrowseMode.PROVIDER || categories.isEmpty()) return categories
        val safe = categories.filterNot { it.isAdult || adultContentLabel(it.title) }
        if (safe.isEmpty()) return emptyList()
        val type = safe.first().type
        fun collection(label: String, members: List<Category>): Category {
            val ids = members.map { it.id }.distinct().sorted()
            // Membership is part of cache identity: hiding or adding a category cannot leak stale members.
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(ids.joinToString("\u0000").toByteArray()).take(8).joinToString("") { "%02x".format(it) }
            return Category("@collection:${mode.name}:$label:$digest", label, type, sourceCategoryIds = ids)
        }
        if (mode == CategoryBrowseMode.CUSTOM) return emptyList()
        if (mode == CategoryBrowseMode.COLLECTIONS) {
            return listOf(collection(when (type) {
                CatalogType.LIVE_TV -> "All channels"
                CatalogType.MOVIES -> "All movies"
                CatalogType.SERIES -> "All series"
                else -> "All titles"
            }, safe)) + organize(categories, CategoryBrowseMode.LANGUAGE) +
                organize(categories, CategoryBrowseMode.REGION) +
                organize(categories, CategoryBrowseMode.TOPIC)
        }
        return safe.flatMap { category -> labels(category, mode).map { it to category } }
            .groupBy({ it.first }, { it.second }).toSortedMap()
            .map { (label, members) -> collection(label, members) }
    }

    /** A specific provider name remains findable even when its tile is grouped under a broad facet. */
    fun search(organized: List<Category>, provider: List<Category>, query: String): List<Category> {
        if (query.isBlank()) return organized
        val matches = titleMatcher(query)
        return (organized + provider.filterNot { it.isAdult || adultContentLabel(it.title) })
            .distinctBy { it.id }.filter { matches(it.title) }
    }

    fun select(items: List<MediaItem>, category: Category): List<MediaItem> {
        val members = category.sourceCategoryIds.toHashSet()
        return items.filter { it.portalCategoryId in members && !it.isAdult && !adultContentLabel(it.title) }
            .distinctBy { it.id }
    }

    private val whitespace = Regex("\\s+")
    fun titleMatcher(query: String): (String) -> Boolean {
        val words = query.trim().split(whitespace)
        return { title -> words.all { title.contains(it, ignoreCase = true) } }
    }
    fun matchesTitle(title: String, query: String): Boolean = titleMatcher(query)(title)
}
