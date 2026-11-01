package com.nikhil.niktv.sports

import com.nikhil.niktv.data.RemoteCredentials
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

internal class CricbuzzDirectClient(
    private val transport: CricketHighlightsTransport,
    private val credentials: () -> CricbuzzCredentials = {
        CricbuzzCredentials(RemoteCredentials.get("NIKTV_CRICBUZZ_AUTH_KEY"),
            RemoteCredentials.get("NIKTV_CRICBUZZ_AUTH_MODE").ifBlank { "AK" },
            RemoteCredentials.get("NIKTV_CRICBUZZ_APP_VERSION").ifBlank { "6.16.04" })
    },
    private val now: () -> Long = System::currentTimeMillis,
) {
    var region: String = "CA"
        set(value) { require(value in setOf("CA", "IN")); field = value }
    var appVersion: String = defaultCricketAppVersion()
        set(value) { require(value in setOf("16.11.01", "6.16.04")); field = value }
    val configured get() = credentials().configured
    val cacheIdentity get() = credentials().cacheIdentity + ":" + region + ":" + appVersion
    private var regionCheckedAt: Long? = null
    private var regionIdentity = ""
    private var clockOffset = 0L
    private var collectionsCheckedAt: Long? = null
    var collections: List<CricketVideoCollection> = emptyList()
        private set
    private var collectionSections: List<CricketVideoSection> = emptyList()
    var sections: List<CricketVideoSection> = emptyList()
        private set
    private data class Location(val collectionId: Int?, val cursor: String?)
    private val locations = linkedMapOf<String, Location>()

    suspend fun featured(): List<CricketHighlight> {
        checkCanada()
        return CricketHomeProtobuf.parse(request("/a/home/v1/index")).distinctBy { it.id }.take(500)
    }

    private suspend fun checkCanada() {
        if (!configured) throw SportsException("Import your Cricbuzz API configuration to connect the Canadian catalogue.")
        if (regionIdentity != cacheIdentity) {
            locations.clear(); collections = emptyList(); collectionSections = emptyList(); sections = emptyList(); collectionsCheckedAt = null
        }
        if (regionIdentity != cacheIdentity || regionCheckedAt?.let { now() - it !in 0 until 900_000 } != false) {
            val geo = Json.parseToJsonElement(request("/a/infra/v1/geo-location", json = true).toString(Charsets.UTF_8)).jsonObject
            // geo reports the connection country; CB-Loc separately selects the requested catalogue.
            regionIdentity = cacheIdentity; regionCheckedAt = now()
        }
    }

    suspend fun browse(collectionId: Int? = null, cursor: String? = null, refreshCollections: Boolean = false): CricketVideoPage {
        checkCanada()
        if (refreshCollections || collectionsCheckedAt?.let { now() - it in 0 until 900_000 } != true) {
            collectionSections = CricketHomeProtobuf.sections(request("/a/videos/v1/collection/videoIndex"))
            collections = collectionSections.map { CricketVideoCollection(it.id!!, it.title) }
            collectionsCheckedAt = now()
        }
        val page = page(collectionId, cursor)
        val home = if (collectionId == null && cursor == null) featured() else emptyList()
        val rows = mergeCricketVideos(home, page.videos)
        page.videos.forEach { locations.putIfAbsent(it.id, Location(collectionId, cursor)) }
        collectionSections.forEach { section -> section.videos.forEach { locations.putIfAbsent(it.id, Location(section.id, null)) } }
        if (collectionId == null && cursor == null) {
            sections = listOf(CricketVideoSection(null, "Featured highlights", home)) + collectionSections.map {
                if (it.id == 1) it.copy(videos = page.videos) else it
            }
            if (sections.none { it.id == 1 }) sections = sections + CricketVideoSection(1, "Latest Videos", page.videos)
        }
        while (locations.size > 500) locations.remove(locations.keys.first())
        return page.copy(videos = rows)
    }

    /** Resolve older pages/series at their original cursor; never replace them with signed-out detail URLs. */
    suspend fun resolve(id: String): CricketHighlight {
        featured().firstOrNull { it.id == id }?.let { return it }
        val location = locations[id] ?: Location(null, null)
        return page(location.collectionId, location.cursor).videos.firstOrNull { it.id == id }
            ?: throw SportsException("This video is no longer available in this listing. Refresh the catalogue.")
    }

    private suspend fun page(collectionId: Int?, cursor: String?): CricketVideoPage {
        require(collectionId == null || collectionId > 0)
        require(cursor == null || cursor.matches(Regex("[0-9]{1,19}")))
        val path = if (collectionId == null) "/a/videos/v1/index" else "/a/videos/v1/collection/detail/$collectionId"
        val bytes = request(path + cursor?.let { "?pt=$it" }.orEmpty())
        return if (collectionId == null) CricketHomeProtobuf.latest(bytes) else CricketHomeProtobuf.collectionPage(bytes)
    }

    internal suspend fun scoreRequest(path: String): ByteArray {
        require(path in setOf("/a/matches/v1/live", "/a/infra/v1/settings") || path.matches(Regex("/a/mcenter/v1/[1-9][0-9]*/h?comm")))
        if (!configured) throw SportsException("Import your Cricbuzz configuration in Settings to enable live scores.")
        return request(path)
    }

    private suspend fun request(path: String, json: Boolean = false): ByteArray {
        val url = "https://api.cricbuzz.com$path"
        suspend fun send(): ByteArray {
            val config = credentials().let { CricbuzzCredentials(it.authKey, it.mode, appVersion) }
            val headers = mapOf("Accept" to if (json) "application/json" else "application/x-protobuf",
                "CB-Loc" to region, "CB-TZ" to SimpleDateFormat("Z", Locale.US).format(Date(now())),
                "CB-AppVer" to config.appVersion, "cb-src" to "playstore",
                "Authentication" to CricbuzzAuthentication.header(url, config, now(), clockOffset))
            return transport.get(url, headers)
        }
        try { return send() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (denied: CricketAccessDenied) {
            val offset = denied.serverTimeMillis?.let { (it - now()) / 1000 } ?: throw denied
            // Retry once only when the authenticated HTTPS server demonstrates meaningful clock skew.
            if (kotlin.math.abs(offset - clockOffset) < 30 || kotlin.math.abs(offset) > 86_400) throw denied
            clockOffset = offset
            return send()
        }
    }
}

internal fun defaultCricketAppVersion(): String = RemoteCredentials.get("NIKTV_CRICBUZZ_APP_VERSION")
    .takeIf { it in setOf("16.11.01", "6.16.04") } ?: "16.11.01"
