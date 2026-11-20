package com.nikhil.niktv.update

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateApkCleanupTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun findsRegisteredDownloadsWithoutDirectFileVisibility() {
        val directory = temp.newFolder("NikTV").canonicalFile
        val unseen = File(directory, "NikTV-v0.6.16-firetv.apk")
        val record = UpdateInstallerDownload(12, unseen, 138_116_852, completed = true)
        assertEquals(listOf(record), olderUpdateInstallers(directory, UpdateChannel.STABLE, "0.6.20",
            listOf(record), NO_DOWNLOAD_ID))
    }

    @Test fun countsRegisteredFileOnceAndIncludesLegacyNames() {
        val directory = temp.newFolder("NikTV").canonicalFile
        val registered = File(directory, "NikTV-v0.6.16-firetv.apk").apply { writeBytes(ByteArray(31)) }
        val legacy = File(directory, "NikTV-0.1.26.apk").apply { writeBytes(ByteArray(25)) }
        File(directory, "NikTV-0.1.32 (1).apk").apply { writeBytes(ByteArray(15)) }
        val records = listOf(UpdateInstallerDownload(14, registered, 31, completed = true))
        val result = olderUpdateInstallers(directory, UpdateChannel.STABLE, "0.6.20", records, NO_DOWNLOAD_ID)
        assertEquals(3, result.size)
        assertEquals(71L, result.sumOf { it.bytes })
        assertEquals(14L, result.single { it.file == registered }.id)
        assertEquals(NO_DOWNLOAD_ID, result.single { it.file == legacy }.id)
    }

    @Test fun retainsCurrentAndNewerInstallersActiveRecordsAndOtherChannels() {
        val directory = temp.newFolder("NikTV").canonicalFile
        val names = listOf(
            "NikTV-v0.6.18-firetv.apk", "NikTV-v0.6.19-firetv.apk",
            "NikTV-v0.6.20-firetv.apk", "NikTV-v0.6.22-firetv (1).apk",
            "NikTV-dev-v0.1.75-firetv.apk", "NikTV-v9999999999999.6.3-firetv.apk",
            "NikTV-v0.6.10-labeled.apk", "recording.mp4",
        )
        val files = names.map { File(directory, it).apply { writeBytes(ByteArray(1)) } }
        val outside = temp.newFolder("different").resolve("NikTV-v0.6.14-firetv.apk")
        val records = listOf(
            UpdateInstallerDownload(12, files[0], 1, completed = false),
            UpdateInstallerDownload(13, files[1], 1, completed = true),
            UpdateInstallerDownload(14, outside, 1, completed = true),
        )
        assertEquals(emptyList<UpdateInstallerDownload>(),
            olderUpdateInstallers(directory, UpdateChannel.STABLE, "0.6.20", records, 13))
        assertEquals(listOf(files[4]),
            olderUpdateInstallers(directory, UpdateChannel.DEVELOPMENT, "0.1.80-dev", records, 13).map { it.file })
    }
}
