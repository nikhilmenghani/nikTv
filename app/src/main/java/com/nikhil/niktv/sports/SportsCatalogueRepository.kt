package com.nikhil.niktv.sports

import kotlinx.coroutines.flow.StateFlow

/** Common browse/player contract; each portal owns its protocol, endpoints and caches. */
internal interface SportsCatalogueRepository {
    val transport: SportsTransport
    val servers: StateFlow<SportsServersState>
    suspend fun events(refresh: Boolean = false): List<SportsEvent>
    suspend fun categories(refresh: Boolean = false): List<SportsCategory>
    suspend fun eventFilters(refresh: Boolean = false): List<SportsEventFilter>
    suspend fun channels(category: SportsCategory = SportsCategory("sports", "Sports"), refresh: Boolean = false): List<SportsChannel>
    suspend fun detail(slug: String, refresh: Boolean = false): SportsChannelDetail
    suspend fun checkServers()
    suspend fun selectServer(base: String?)
    suspend fun highlights(refresh: Boolean = false): List<SportsChannel> = emptyList()
}
