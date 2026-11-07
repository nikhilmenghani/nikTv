package com.nikhil.niktv.data

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/** Public catalogue and link pages only. Never executes scripts or solves browser challenges. */
internal class Hdhub4uSourceResolver(
    private val read: suspend (AlternateSourceHttpRequest, String) -> AlternateSourceHttpResponse,
) {
    private data class Listing(val path: String, val series: Boolean, val seasons: Set<Int>, val year: Int?, val imdb: String?)
    private data class Link(val url: HttpUrl, val referer: String, val label: String = "",
        val extraHeaders: Map<String, String> = emptyMap())

    suspend fun resolve(target: AlternateSourceTarget): List<AlternateStream> {
        if (target.series && (target.season == null || target.episode == null || target.episode < 1))
            throw AlternateSourceException("Choose a season and episode.", AlternateSourceFailure.IDENTITY)
        val base = domain()
        val listings = search(base, target)
        var issue: AlternateSourceException? = null
        for (listing in listings.take(3)) {
            currentCoroutineContext().ensureActive()
            val page = page(base.resolve(listing.path)!!, base.toString(), "HDHub4u title")
            val document = Jsoup.parse(page.body, page.url)
            val heading = document.selectFirst("h1 .material-text")?.text()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: document.selectFirst("h1")?.clone()?.apply { select("i").remove() }?.text()
                ?: throw changed()
            if (!matches(heading, target, SERIES.containsMatchIn(heading))) throw identity()
            val links = links(document, target, listing).sortedBy { priority(it.url) }
            val visited = mutableSetOf<String>()
            for (link in links.take(6)) {
                try {
                    val streams = resolveLink(link, visited, 0)
                    if (streams.isNotEmpty()) return streams.distinctBy { it.url }.take(12)
                } catch (cancelled: CancellationException) { throw cancelled }
                  catch (failure: AlternateSourceException) { issue = failure }
            }
        }
        issue?.let { throw it }
        return emptyList()
    }

    private suspend fun domain(): HttpUrl {
        val proposed = try {
            val response = page(DOMAINS.toHttpUrl(), "https://github.com/", "HDHub4u domain")
            JSONObject(response.body).optString("HDHUB4u")
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { FALLBACK }
        val url = requirePublicHttps(proposed.ifBlank { FALLBACK })
        if (!Regex("(?:[a-z0-9-]+\\.)?hdhub4u\\.[a-z]{2,24}").matches(url.host) ||
            url.port != 443 || url.encodedPath != "/" || url.query != null) throw identity()
        return url
    }

    private suspend fun search(base: HttpUrl, target: AlternateSourceTarget): List<Listing> {
        val found = linkedMapOf<String, Listing>()
        for (number in 1..4) {
            val url = SEARCH.toHttpUrl().newBuilder().addQueryParameter("q", target.title.trim())
                .addQueryParameter("query_by", "post_title,category").addQueryParameter("query_by_weights", "4,2")
                .addQueryParameter("limit", "40").addQueryParameter("page", "$number")
                .addQueryParameter("highlight_fields", "none").build()
            val response = page(url, base.toString(), "HDHub4u search")
            val root = try { JSONObject(response.body) } catch (_: Exception) { throw changed() }
            val hits = root.optJSONArray("hits") ?: throw changed()
            for (index in 0 until hits.length()) {
                val data = hits.optJSONObject(index)?.optJSONObject("document") ?: continue
                val title = Jsoup.parse(data.optString("post_title")).text()
                val series = SERIES.containsMatchIn(title) || data.optJSONArray("category")?.toString()
                    ?.contains("web-series", ignoreCase = true) == true
                if (!matches(title, target, series)) continue
                val raw = data.optString("permalink").ifBlank { data.optString("url") }
                val old = raw.toHttpUrlOrNull()
                val path = old?.encodedPath ?: raw.takeIf { it.startsWith('/') && !it.startsWith("//") } ?: continue
                // The index often retains a previous domain. Only its content path is reused.
                if (path == "/" || path.contains("/category/") || path.contains("/page/")) continue
                found[path] = Listing(path, series, seasons(title), releaseYear(title),
                    data.optString("imdb_id").takeIf { it.matches(Regex("tt\\d{5,12}")) })
            }
            if (found.isNotEmpty() || hits.length() < 40 || number * 40 >= root.optInt("found", hits.length())) break
        }
        val results = found.values.toList()
        // Equal names can identify remakes, even within one release year. Never
        // turn the search engine's ordering into an implicit identity decision.
        if (results.mapNotNull { it.imdb }.distinct().size > 1 ||
            !target.series && target.year == null && results.map { it.year }.distinct().size > 1 ||
            !target.series && target.year == null && results.size > 1 && results.all { it.year == null && it.imdb == null }) {
            throw AlternateSourceException("HDHub4u returned ambiguous titles. Choose the movie or series from its detail page.", AlternateSourceFailure.IDENTITY)
        }
        return results
    }

    private fun matches(raw: String, target: AlternateSourceTarget, series: Boolean): Boolean {
        if (series != target.series) return false
        val title = raw.replace(Regex("^(?:download|watch)\\s+", RegexOption.IGNORE_CASE), "")
            .substringBefore('(').replace(Regex("\\b(?:season\\s*\\d|S\\d{1,2}\\b|(?:480|720|1080|2160)p\\b).*$", RegexOption.IGNORE_CASE), "").trim()
        if (normalizedTitle(title) != normalizedTitle(target.title)) return false
        val year = releaseYear(raw)
        if (!series && target.year != null && year != target.year) return false
        if (series) {
            val scopes = seasons(raw)
            if (scopes.isNotEmpty() && target.season !in scopes) return false
            if (target.season == 1 && year != null && target.year != null && year != target.year) return false
            if (year != null && target.year != null && year < target.year) return false
        }
        return true
    }

    private fun releaseYear(title: String) = Regex("\\((19\\d{2}|20\\d{2})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()

    private fun seasons(text: String): Set<Int> = buildSet {
        SEASON.findAll(text).forEach { match ->
            val start = match.groupValues[1].toIntOrNull() ?: return@forEach
            val end = match.groupValues[2].toIntOrNull() ?: start
            if (start >= 0 && end in start..minOf(start + 30, 100)) addAll(start..end)
        }
    }

    private fun links(document: Document, target: AlternateSourceTarget, listing: Listing): List<Link> {
        val content = document.selectFirst(".page-content, .entry-content, article, main") ?: document.body()
        var season = listing.seasons.singleOrNull()
        var episode: Int? = null
        val result = linkedMapOf<String, Link>()
        for (element in content.allElements) {
            val text = if (element.tagName() == "a") element.text() else element.ownText()
            if (element.tagName() in setOf("h2", "h3", "h4", "h5", "h6", "p", "strong", "b", "span")) {
                val scope = seasons(text).singleOrNull()
                if (scope != null) { season = scope; episode = null }
                EPISODE.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { episode = it }
            }
            if (element.tagName() != "a") continue
            val url = element.absUrl("href").toHttpUrlOrNull() ?: continue
            val explicit = EPISODE.find(text)?.groupValues?.get(1)?.toIntOrNull()
            val wanted = !target.series || season == target.season && (explicit ?: episode) == target.episode
            if (!wanted || NON_RELEASE.containsMatchIn(text) || !supported(url)) continue
            result[url.toString()] = Link(requirePublicHttps(url.toString()), document.location(), text)
        }
        return result.values.toList()
    }

    private suspend fun resolveLink(link: Link, visited: MutableSet<String>, depth: Int): List<AlternateStream> {
        currentCoroutineContext().ensureActive()
        if (depth >= 6 || !visited.add(link.url.toString())) return emptyList()
        val host = link.url.host
        if (FILE.containsMatchIn(link.url.encodedPath)) return media(link)
        if (host.split('.').contains("hubstream")) {
            val id = link.url.fragment?.substringAfterLast('/')?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,160}")) }
                ?: return emptyList()
            val api = link.url.resolve("/api/v1/video")!!.newBuilder().addQueryParameter("id", id).build()
            val encoded = page(api, link.url.toString(), "HDHub4u player").body.trim()
            val source = hdhubPlayerMetadata(encoded)?.optString("source")?.replace("\\/", "/")
                ?.takeIf(String::isNotBlank) ?: return emptyList()
            return media(Link(requirePublicHttps(source), origin(link.url.toString()), link.label,
                mapOf("Origin" to origin(link.url.toString()).trimEnd('/'))))
        }
        if (host.split('.').any { it in setOf("pixeldrain", "pixeldra") }) {
            val id = link.url.pathSegments.lastOrNull()?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{4,100}")) }
                ?: return emptyList()
            return media(link.copy(url = link.url.resolve("/api/file/$id?download")!!))
        }
        val playerId = Regex("/(?:file|e|d|v)/([A-Za-z0-9]+)").find(link.url.encodedPath)?.groupValues?.get(1)
        val player = if (host.split('.').contains("hdstream4u") && playerId != null)
            link.url.resolve("/v/$playerId")!! else link.url
        val response = page(player, link.referer, "HDHub4u player")
        val body = response.body
        if (body.trimStart().startsWith("#EXTM3U")) return hls(response, link)
        val document = Jsoup.parse(body, response.url)
        val playerText = unpackHdhubPlayer(body).replace("\\/", "/")
        var playlistFailure: AlternateSourceException? = null
        for (playlist in hdhubPlayerPlaylists(playerText, response.url)) {
            try {
                val mediaLink = Link(playlist, origin(response.url), link.label)
                val streams = hls(page(playlist, mediaLink.referer, "HDHub4u playlist"), mediaLink)
                if (streams.isNotEmpty()) return streams
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (failure: AlternateSourceException) { playlistFailure = failure }
        }
        playlistFailure?.let { throw it }
        val rawMedia = Regex("\\b(?:file|source|src)[\"']?\\s*[=:]\\s*[\"'](https://[^\"']+\\.m3u8[^\"']*)", RegexOption.IGNORE_CASE)
            .find(playerText)?.groupValues?.get(1)
            ?: document.select("video[src], source[src]").firstOrNull()?.absUrl("src")
        if (!rawMedia.isNullOrBlank()) return media(Link(requirePublicHttps(rawMedia), origin(response.url), link.label))
        if (link.url.queryParameter("id") != null) {
            val target = decodeHdhubRedirect(body)
            if (target != null) return resolveLink(Link(requirePublicHttps(target), response.url, link.label), visited, depth + 1)
            val envelope = hdhubRedirectEnvelope(body)
            val blog = envelope?.optString("blog_url")?.takeIf(String::isNotBlank)
            val data = envelope?.optString("data")?.takeIf(String::isNotBlank)?.let {
                runCatching { String(Base64.getDecoder().decode(it), Charsets.UTF_8) }.getOrNull()
            }
            if (blog != null && data != null && data.length <= 16_384) {
                val callback = requirePublicHttps(blog).newBuilder().addQueryParameter("re", data).build()
                val reply = page(callback, response.url, "HDHub4u redirect")
                val destination = Jsoup.parse(reply.body).text().trim().toHttpUrlOrNull()
                if (destination != null) return resolveLink(Link(requirePublicHttps(destination.toString()), reply.url, link.label), visited, depth + 1)
            }
        }
        if (NON_RELEASE.containsMatchIn(document.selectFirst(".card-header")?.text().orEmpty())) return emptyList()
        val preferred = document.selectFirst("a#download[href], a.btn-success1[href]")
            ?.absUrl("href")?.toHttpUrlOrNull()
        if (preferred != null) return resolveLink(Link(requirePublicHttps(preferred.toString()), response.url, link.label), visited, depth + 1)
        val candidates = document.select("a[href]").mapNotNull { anchor ->
            val url = anchor.absUrl("href").toHttpUrlOrNull() ?: return@mapNotNull null
            val label = anchor.text()
            if (NON_RELEASE.containsMatchIn(label)) return@mapNotNull null
            val fileButton = host.split('.').any { it in setOf("hubcloud", "hubdrive", "hblinks") } &&
                anchor.hasClass("btn") && Regex("fsl|s3|10gb|download", RegexOption.IGNORE_CASE).containsMatchIn(label)
            if (supported(url) || fileButton) Link(requirePublicHttps(url.toString()), response.url, label) else null
        }.distinctBy { it.url }.sortedBy { priority(it.url) }
        for (candidate in candidates.take(4)) {
            val stream = if (!supported(candidate.url)) media(candidate) else resolveLink(candidate, visited, depth + 1)
            if (stream.isNotEmpty()) return stream
        }
        return emptyList()
    }

    private suspend fun media(link: Link): List<AlternateStream> {
        val headers = mapOf("User-Agent" to AlternateSourceResolver.USER_AGENT, "Referer" to link.referer) + link.extraHeaders
        if (link.url.encodedPath.endsWith(".m3u8", true))
            return hls(page(link.url, link.referer, "HDHub4u playlist", link.extraHeaders), link)
        val probe = read(AlternateSourceHttpRequest(link.url.toString(), headers + ("Range" to "bytes=0-1023"), 4096), "HDHub4u media")
        val type = probe.headers.entries.firstOrNull { it.key.equals("content-type", true) }?.value.orEmpty()
        if (probe.body.trimStart('\uFEFF', ' ', '\n', '\r', '\t').startsWith('<') || type.contains("text/html", true)) return emptyList()
        if (!type.startsWith("video/", true) && !probe.body.take(64).contains("ftyp")) return emptyList()
        val height = Regex("\\b(480|720|1080|2160)p\\b", RegexOption.IGNORE_CASE)
            .find(link.label)?.groupValues?.get(1)?.toIntOrNull()
        val label = "HDHub4u · ${link.url.host}"
        return listOf(AlternateStream("hdhub4u:${hash(probe.url)}", "${height?.let { "${it}p" } ?: "Auto"} · $label",
            requirePublicHttps(probe.url).toString(), headers,
            format = if (type.contains("matroska", true) || link.url.encodedPath.endsWith(".mkv", true)) "MKV" else "MP4", height = height))
    }

    private fun hls(response: AlternateSourceHttpResponse, link: Link): List<AlternateStream> =
        alternateHlsStreams(requirePublicHttps(response.url), response.body,
            mapOf("User-Agent" to AlternateSourceResolver.USER_AGENT, "Referer" to link.referer) + link.extraHeaders,
            "hdhub4u:${hash(response.url)}", "HDHub4u · ${link.url.host}").map { stream ->
            // HDStream's current HLS fails LibVLC 3's demuxer on Android, while
            // Media3 renders it. Use the existing per-stream compatibility route;
            // retain the user's player preference and other mirrors' behavior.
            stream.copy(vlcCompatible = !requirePublicHttps(link.referer).host.split('.').contains("hdstream4u"))
        }

    private suspend fun page(url: HttpUrl, referer: String, phase: String, extraHeaders: Map<String, String> = emptyMap()) =
        read(AlternateSourceHttpRequest(requirePublicHttps(url.toString()).toString(),
            mapOf("User-Agent" to AlternateSourceResolver.USER_AGENT, "Referer" to referer) + extraHeaders, 1024 * 1024), phase)

    private fun supported(url: HttpUrl) = url.scheme == "https" &&
        (FILE.containsMatchIn(url.encodedPath) || url.host.split('.').any { it in HOST_LABELS } ||
            url.host == "greenmotors.club" && url.queryParameter("id") != null)
    private fun priority(url: HttpUrl) = when {
        url.encodedPath.endsWith(".m3u8", true) -> 0
        url.host.split('.').contains("hdstream4u") -> 1
        url.host.split('.').contains("hubstream") -> 2
        url.host.split('.').contains("hubcloud") -> 3
        else -> 3
    }
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .take(8).joinToString("") { "%02x".format(it) }
    private fun origin(value: String) = requirePublicHttps(value).newBuilder().encodedPath("/").query(null).fragment(null).build().toString()
    private fun changed() = AlternateSourceException("HDHub4u returned an unsupported page. Retry or choose another source.", stage = "HDHub4u page")
    private fun identity() = AlternateSourceException("HDHub4u did not match the selected title or season.", AlternateSourceFailure.IDENTITY)

    companion object {
        internal const val DOMAINS = "https://raw.githubusercontent.com/phisher98/TVVVV/refs/heads/main/domains.json"
        internal const val FALLBACK = "https://new11.hdhub4u.cl"
        internal const val SEARCH = "https://search.pingora.fyi/collections/post/documents/search"
        private val HOST_LABELS = setOf("hdstream4u", "hubstream", "hubcloud", "hubdrive", "hblinks", "pixeldrain", "pixeldra")
        private val FILE = Regex("\\.(?:m3u8|mp4|mkv)$", RegexOption.IGNORE_CASE)
        private val SERIES = Regex("\\b(?:season\\s*\\d+|S\\d{1,2}\\b|full\\s+series|web[- ]series)\\b", RegexOption.IGNORE_CASE)
        private val SEASON = Regex("\\b(?:season\\s*|S)(\\d{1,2})(?:\\s*[-–]\\s*(\\d{1,2}))?\\b", RegexOption.IGNORE_CASE)
        private val EPISODE = Regex("\\b(?:episode|ep)\\s*0*(\\d{1,3})\\b", RegexOption.IGNORE_CASE)
        private val NON_RELEASE = Regex("\\bsample\\b|\\btrailer\\b|post[- .]?credits?|\\.(?:zip|rar|7z)\\b", RegexOption.IGNORE_CASE)
    }
}

/** The player's literal link table declares its HLS mirror preference, including .txt manifests. */
internal fun hdhubPlayerPlaylists(script: String, base: String): List<HttpUrl> {
    val literal = Regex("\\b(?:var|let|const)\\s+links\\s*=\\s*(\\{[^{}]{1,16384}\\})").find(script)?.groupValues?.get(1)
        ?: return emptyList()
    val links = runCatching { JSONObject(literal) }.getOrNull() ?: return emptyList()
    return listOf("hls4", "hls3", "hls2").mapNotNull { key ->
        links.optString(key).takeIf(String::isNotBlank)?.let { base.toHttpUrl().resolve(it) }
            ?.let { requirePublicHttps(it.toString()) }
    }.distinct()
}

/** Decode the page's literal public redirect envelope; do not execute its script. */
internal fun decodeHdhubRedirect(html: String): String? = runCatching {
    hdhubRedirectEnvelope(html)?.optString("o")?.takeIf(String::isNotBlank)
        ?.let { String(Base64.getDecoder().decode(it), Charsets.UTF_8) }
}.getOrNull()

private fun hdhubRedirectEnvelope(html: String): JSONObject? = runCatching {
    fun decode(value: String) = String(Base64.getDecoder().decode(value), Charsets.UTF_8)
    val parts = Regex("(?:s\\(['\"]o['\"],['\"]|ck\\(['\"]_wp_http_\\d+['\"],['\"])([A-Za-z0-9+/=]+)")
        .findAll(html).take(4).joinToString("") { it.groupValues[1] }
    if (parts.length !in 4..32_768) return null
    val rotated = decode(decode(parts)).map { c -> when (c) {
        in 'a'..'z' -> 'a' + (c - 'a' + 13) % 26
        in 'A'..'Z' -> 'A' + (c - 'A' + 13) % 26
        else -> c
    } }.joinToString("")
    JSONObject(decode(rotated))
}.getOrNull()

/** Decode the public player's metadata envelope, separately from HLS/media encryption. */
internal fun hdhubPlayerMetadata(encoded: String): JSONObject? {
    if (encoded.length !in 32..262_144 || encoded.length % 32 != 0 || !encoded.matches(Regex("[0-9a-fA-F]+"))) return null
    val bytes = ByteArray(encoded.length / 2) { encoded.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    for (iv in listOf("1234567890oiuytr", "0123456789abcdef")) {
        val metadata = try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec("kiemtienmua911ca".toByteArray(), "AES"), IvParameterSpec(iv.toByteArray()))
            JSONObject(String(cipher.doFinal(bytes), Charsets.UTF_8))
        } catch (_: Exception) { null }
        if (metadata?.optString("source")?.isNotBlank() == true) return metadata
    }
    return null
}

/** Bounded string substitution for the standard packed-player literal format, never eval(). */
internal fun unpackHdhubPlayer(html: String): String {
    val call = Regex("\\}\\s*\\(\\s*'((?:\\\\.|[^'\\\\])*)'\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*'((?:\\\\.|[^'\\\\])*)'\\.split\\('\\|'\\)")
        .find(html) ?: return html
    val radix = call.groupValues[2].toIntOrNull()?.takeIf { it in 2..62 } ?: return html
    val count = call.groupValues[3].toIntOrNull()?.takeIf { it in 1..10_000 } ?: return html
    val dictionary = call.groupValues[4].split('|').takeIf { it.size >= count } ?: return html
    val alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    val payload = call.groupValues[1].takeIf { it.length <= 512_000 }?.replace("\\'", "'")?.replace("\\\\", "\\") ?: return html
    return Regex("\\b[0-9a-zA-Z]+\\b").replace(payload) { match ->
        var index = 0L
        for (c in match.value) {
            val digit = alphabet.indexOf(c)
            if (digit !in 0 until radix || index > count) return@replace match.value
            index = index * radix + digit
        }
        dictionary.getOrNull(index.toInt())?.takeIf(String::isNotBlank) ?: match.value
    }
}
