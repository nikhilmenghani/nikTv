package com.nikhil.niktv.discovery

import com.nikhil.niktv.model.TmdbHomeSection

/** Injectable metadata boundary. It deliberately has no playback or provider methods. */
internal interface DiscoveryDataSource {
    suspend fun feed(section: TmdbHomeSection, stale: (List<DiscoveryCard>) -> Unit = {}): List<DiscoveryCard>
    suspend fun details(card: DiscoveryCard, stale: (DiscoveryDetails) -> Unit = {}): DiscoveryDetails
    suspend fun related(card: DiscoveryCard): List<DiscoveryCard>
    suspend fun collection(id: Int): List<DiscoveryCard>
    suspend fun reviews(card: DiscoveryCard): List<DiscoveryReview>
    suspend fun episodes(card: DiscoveryCard, season: Int): List<DiscoveryEpisode>
    suspend fun actorCredits(person: DiscoveryPerson, stale: (List<DiscoveryCard>) -> Unit = {}): List<DiscoveryCard> = emptyList()
}
