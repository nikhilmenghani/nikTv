package com.nikhil.niktv.update

import org.junit.Assert.*
import org.junit.Test

class UpdateChannelTest {
    @Test fun packageDeterminesDistribution() {
        assertEquals(UpdateChannel.STABLE, UpdateChannel.forPackage("com.nikhil.niktv"))
        assertEquals(UpdateChannel.DEVELOPMENT, UpdateChannel.forPackage("com.nikhil.niktv.debug"))
    }

    @Test(expected = IllegalStateException::class)
    fun unknownPackageFailsClosed() { UpdateChannel.forPackage("com.nikhil.other") }

    @Test fun allApkVariantsStayInTheirChannel() {
        for (channel in UpdateChannel.entries) {
            for (asset in UpdatePackage.entries.filterNot { it == UpdatePackage.AUTO }) {
                val tag = channel.releaseTag("1.2.3")
                val url = "https://github.com/nikhilmenghani/nikTv/releases/download/$tag/NikTV-$tag-${asset.assetSuffix}.apk"
                assertTrue(channel.accepts("1.2.3", url))
                assertFalse(UpdateChannel.entries.single { it != channel }.accepts("1.2.3", url))
                assertFalse(channel.accepts("1.2.4", url))
                assertFalse(channel.accepts("1.2.3", "$url?redirect=stable"))
                assertFalse(channel.accepts("1.2.3", url.replace("github.com", "example.com")))
            }
        }
    }
}
