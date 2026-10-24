package com.nikhil.niktv.model

internal val TmdbHomeSection.regionalBase: TmdbHomeSection
    get() = if (name.startsWith("BOLLYWOOD_")) TmdbHomeSection.entries.firstOrNull { it.name == name.removePrefix("BOLLYWOOD_") } ?: this else this
internal val TmdbHomeSection.regionalLanguage: String
    get() = if (name.startsWith("BOLLYWOOD") || name.startsWith("NEW_BOLLYWOOD")) "hi" else "en"
internal data class TmdbCategoryPair(val title: String, val hollywood: TmdbHomeSection, val bollywood: TmdbHomeSection)
internal fun tmdbCategoryPairs(surface: DashboardSurface): List<TmdbCategoryPair> = TmdbHomeSection.entries
    .filter { it.regionalLanguage == "en" && (surface == DashboardSurface.HOME || (surface != DashboardSurface.LIVE_TV && it.series == (surface == DashboardSurface.SERIES))) }
    .mapNotNull { section ->
        val other = when (section) {
            TmdbHomeSection.HOLLYWOOD -> TmdbHomeSection.BOLLYWOOD
            TmdbHomeSection.NEW_HOLLYWOOD_MOVIES -> TmdbHomeSection.NEW_BOLLYWOOD_MOVIES
            TmdbHomeSection.HOLLYWOOD_SERIES -> TmdbHomeSection.BOLLYWOOD_SERIES
            TmdbHomeSection.NEW_HOLLYWOOD_SERIES -> TmdbHomeSection.NEW_BOLLYWOOD_SERIES
            else -> TmdbHomeSection.entries.firstOrNull { it.name == "BOLLYWOOD_${section.name}" }
        } ?: return@mapNotNull null
        TmdbCategoryPair(when (section) {
            TmdbHomeSection.HOLLYWOOD, TmdbHomeSection.HOLLYWOOD_SERIES -> "Popular"
            TmdbHomeSection.NEW_HOLLYWOOD_MOVIES, TmdbHomeSection.NEW_HOLLYWOOD_SERIES -> "New releases"
            else -> section.title.removePrefix("Hollywood ").removeSuffix(" Movies").removeSuffix(" Series")
        }, section, other)
    }

internal fun tmdbRegionalRequest(selected: TmdbHomeSection, today: String): Pair<String, Map<String, String>> {
    val section = selected.regionalBase
    val regionalLanguage = selected.regionalLanguage
    val type = if (section.series) "tv" else "movie"
    val base = mapOf("include_adult" to "false", "with_original_language" to regionalLanguage)
    if (section in setOf(TmdbHomeSection.TRENDING_MOVIES, TmdbHomeSection.TRENDING_SERIES)) return "/trending/$type/day" to base
    if (section in setOf(TmdbHomeSection.TOP_RATED_MOVIES, TmdbHomeSection.TOP_RATED_SERIES)) return "/discover/$type" to (base + mapOf("sort_by" to "vote_average.desc", "vote_count.gte" to "100", (if (section.series) "first_air_date.lte" else "primary_release_date.lte") to today))
    val language = regionalLanguage
    val genre = when (section) {
        TmdbHomeSection.ACTION -> "28"; TmdbHomeSection.ACTION_SERIES -> "10759"
        TmdbHomeSection.COMEDY, TmdbHomeSection.COMEDY_SERIES -> "35"
        TmdbHomeSection.HORROR -> "27"; TmdbHomeSection.THRILLER -> "53"; TmdbHomeSection.MYSTERY_SERIES -> "9648"
        TmdbHomeSection.FAMILY, TmdbHomeSection.FAMILY_SERIES -> "10751"
        TmdbHomeSection.DOCUMENTARY, TmdbHomeSection.DOCUMENTARY_SERIES -> "99"
        else -> null
    }
    val recent = section.name.startsWith("NEW_")
    val query = base + mapOf("sort_by" to if (recent) { if (section.series) "first_air_date.desc" else "primary_release_date.desc" } else "popularity.desc",
        (if (section.series) "first_air_date.lte" else "primary_release_date.lte") to today) +
        listOfNotNull(language?.let { "with_original_language" to it }, genre?.let { "with_genres" to it }).toMap()
    return "/discover/$type" to query
}
