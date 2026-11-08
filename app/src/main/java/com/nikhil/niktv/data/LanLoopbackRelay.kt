package com.nikhil.niktv.data

import android.util.Base64
import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Bridges an app-owned loopback movie without exposing a general-purpose URL proxy. */
internal class LanLoopbackRelay(private val rootUrl: String, private val headers: Map<String, String>, private val route: String) : AutoCloseable {
    private val key = SecretKeySpec(ByteArray(16).also(SecureRandom()::nextBytes), "AES")
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private fun seal(url: String): String {
        require(url.length <= 4096)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(url.toByteArray()), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
    private fun open(value: String): String {
        require(value.length <= 5600)
        val bytes = Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        require(bytes.size >= 29)
        return String(Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        }.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }
    fun serve(request: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val tail = request.uri.removePrefix(route)
        val remote = (if (tail == "video") rootUrl else open(tail.substringBefore('.'))).toHttpUrl()
        require(remote.scheme in setOf("http", "https"))
        val builder = Request.Builder().url(remote)
        headers.filterKeys { it.lowercase() !in setOf("host", "connection", "content-length", "range") }.forEach { (name, value) -> builder.header(name, value) }
        request.headers["range"]?.let { builder.header("Range", it) }
        val response = client.newCall(builder.build()).execute()
        try {
            if (response.code !in setOf(200, 206, 416)) error("Source is unavailable")
            val type = response.header("Content-Type") ?: "application/octet-stream"
            val hls = type.contains("mpegurl", true) || remote.encodedPath.endsWith(".m3u8")
            if (hls) {
                val buffer = java.io.ByteArrayOutputStream()
                val stream = response.body.byteStream()
                val chunk = ByteArray(8192)
                while (buffer.size() <= 1_048_576) {
                    val count = stream.read(chunk, 0, minOf(chunk.size, 1_048_577 - buffer.size()))
                    if (count < 0) break
                    buffer.write(chunk, 0, count)
                }
                val bytes = buffer.toByteArray()
                require(bytes.size <= 1_048_576)
                fun mapped(raw: String): String = route + seal(requireNotNull(response.request.url.resolve(raw)).toString()) +
                    if (raw.substringBefore('?').endsWith(".m3u8")) ".m3u8" else ".bin"
                val body = String(bytes, Charsets.UTF_8).lineSequence().joinToString("\n") { line ->
                    if (line.isBlank()) line else if (line.startsWith('#')) Regex("URI=\"([^\"]+)\"").replace(line) { "URI=\"${mapped(it.groupValues[1])}\"" }
                    else mapped(line.trim())
                }.toByteArray()
                response.close()
                return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/vnd.apple.mpegurl", ByteArrayInputStream(body), body.size.toLong()).apply { addHeader("Connection", "close") }
            }
            val input = object : FilterInputStream(response.body.byteStream()) { override fun close() { response.close() } }
            val length = response.body.contentLength()
            val status = when (response.code) { 206 -> NanoHTTPD.Response.Status.PARTIAL_CONTENT; 416 -> NanoHTTPD.Response.Status.RANGE_NOT_SATISFIABLE; else -> NanoHTTPD.Response.Status.OK }
            return (if (length >= 0) NanoHTTPD.newFixedLengthResponse(status, type, input, length)
                else NanoHTTPD.newChunkedResponse(status, type, input)).apply {
                listOf("Content-Range", "Accept-Ranges").forEach { header -> response.header(header)?.let { addHeader(header, it) } }
                addHeader("Cache-Control", "no-store")
                addHeader("Connection", "close")
            }
        } catch (failure: Exception) { response.close(); throw failure }
    }
    override fun close() { client.dispatcher.cancelAll(); client.connectionPool.evictAll() }
}
