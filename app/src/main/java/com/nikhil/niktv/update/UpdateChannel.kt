package com.nikhil.niktv.update

/** Distribution identity follows the installed package, never its signing key or debugger flag. */
internal enum class UpdateChannel(val applicationId: String, val development: Boolean, val appName: String) {
    STABLE("com.nikhil.niktv", false, "NikTV"),
    DEVELOPMENT("com.nikhil.niktv.debug", true, "NikTV Dev");

    fun releaseTag(version: String) = if (development) "dev-v$version" else "v$version"

    fun accepts(version: String, url: String): Boolean {
        if (!Regex("""\d+\.\d+\.\d+""").matches(version)) return false
        return runCatching {
            val uri = java.net.URI(url)
            val tag = releaseTag(version)
            uri.scheme == "https" && uri.host == "github.com" && uri.port == -1 &&
                uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                UpdatePackage.entries.filterNot { it == UpdatePackage.AUTO }.any {
                    uri.rawPath == "/nikhilmenghani/nikTv/releases/download/$tag/NikTV-$tag-${it.assetSuffix}.apk"
                }
        }.getOrDefault(false)
    }

    companion object {
        fun forPackage(applicationId: String): UpdateChannel =
            entries.singleOrNull { it.applicationId == applicationId }
                ?: error("No update channel configured for $applicationId")
    }
}
