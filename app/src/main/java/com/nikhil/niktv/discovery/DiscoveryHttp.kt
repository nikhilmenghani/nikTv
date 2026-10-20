package com.nikhil.niktv.discovery

import java.io.IOException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.*

/** Keeps parsing off the UI thread on OkHttp's callback dispatcher and owns response closure. */
internal suspend fun discoveryHttp(factory: Call.Factory, request: Request, json: Json,
    maxBytes: Long = 4L * 1024 * 1024): JsonObject = suspendCancellableCoroutine { continuation ->
    val call = factory.newCall(request)
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWith(Result.failure(e))
        }
        override fun onResponse(call: Call, response: Response) {
            if (!continuation.isActive) { response.close(); return }
            try { response.use {
                check(it.isSuccessful) { "TMDB returned HTTP ${it.code}" }
                val source = it.body.source()
                source.request(maxBytes + 1)
                check(source.buffer.size <= maxBytes) { "TMDB metadata response is too large" }
                val result = json.parseToJsonElement(source.readUtf8()).jsonObject
                if (continuation.isActive) continuation.resumeWith(Result.success(result))
            } } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(error))
            }
        }
    })
}
