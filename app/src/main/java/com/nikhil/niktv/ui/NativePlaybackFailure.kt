package com.nikhil.niktv.ui

import androidx.media3.common.PlaybackException

internal enum class NativeFailureKind { AUTHORIZATION, TRANSPORT, UNSUPPORTED, UNKNOWN }
internal class NativePlaybackFailure(val kind: NativeFailureKind, val httpStatus: Int?, details: String) : Exception(details)

internal fun nativePlaybackFailure(raw: String?): NativePlaybackFailure {
    val safe = raw.orEmpty().replace(Regex("(?i)https?://\\S+"), "[stream]")
        .replace(Regex("(?i)(authorization|cookie|token|password)\\s*[:=]\\s*[^\\r\\n]+"), "$1=[redacted]")
        .take(800).trim()
    val lower = safe.lowercase()
    val status = Regex("\\b(401|403|404|408|410|429|5\\d\\d)\\b").find(lower)?.value?.toIntOrNull()
    val kind = when {
        status == 401 || status == 403 || "unauthorized" in lower || "forbidden" in lower -> NativeFailureKind.AUTHORIZATION
        "unsupported" in lower || "not supported" in lower || "no decoder" in lower || "unrecognized file" in lower || "unknown format" in lower || "failed to recognize file format" in lower -> NativeFailureKind.UNSUPPORTED
        status != null || listOf("timed out", "timeout", "connection", "network", "resolve host", "server returned", "tls", "http error").any(lower::contains) -> NativeFailureKind.TRANSPORT
        else -> NativeFailureKind.UNKNOWN
    }
    return NativePlaybackFailure(kind, status, safe.ifBlank { "MPV stopped without reporting a native error. Retry or switch player." })
}

internal fun NativePlaybackFailure.playbackCode(): Int = when (kind) {
    NativeFailureKind.AUTHORIZATION -> PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
    NativeFailureKind.TRANSPORT -> if (httpStatus != null) PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS else PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
    NativeFailureKind.UNSUPPORTED -> PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
    NativeFailureKind.UNKNOWN -> PlaybackException.ERROR_CODE_UNSPECIFIED
}
