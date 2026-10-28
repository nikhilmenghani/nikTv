package com.nikhil.niktv.discovery

import com.nikhil.niktv.model.TmdbHomeSection

/** Injectable public metadata boundary. It has no playback or provider-network operations. */
internal interface DiscoveryDataSource {
    /** Public TMDB metadata for a provider title; never resolves availability or a stream. */
    suspend fun providerDetails(item: com.nikhil.niktv.model.MediaItem, type: com.nikhil.niktv.model.CatalogType,
        stale: (DiscoveryDetails) -> Unit = {}): DiscoveryDetails = DiscoveryDetails(providerDetailFallback(item, type))
    suspend fun feed(section: TmdbHomeSection, stale: (List<DiscoveryCard>) -> Unit = {}): List<DiscoveryCard>
    suspend fun details(card: DiscoveryCard, stale: (DiscoveryDetails) -> Unit = {}): DiscoveryDetails
    suspend fun related(card: DiscoveryCard): List<DiscoveryCard>
    suspend fun collection(id: Int): List<DiscoveryCard>
    suspend fun reviews(card: DiscoveryCard): List<DiscoveryReview>
    suspend fun episodes(card: DiscoveryCard, season: Int): List<DiscoveryEpisode>
    suspend fun actorInfo(person: DiscoveryPerson): DiscoveryActorInfo = DiscoveryActorInfo(person.name, person.portrait)
    suspend fun actorCredits(person: DiscoveryPerson, stale: (List<DiscoveryCard>) -> Unit = {}): List<DiscoveryCard> = emptyList()
}
