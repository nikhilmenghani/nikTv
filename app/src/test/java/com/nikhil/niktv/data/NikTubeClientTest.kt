package com.nikhil.niktv.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class NikTubeClientTest {
    @Test fun paginationKeepsAllItemsAndReadsContinuation() {
        val items = org.json.JSONArray()
        repeat(30) { index -> items.put(JSONObject().put("videoRenderer", JSONObject().put("videoId", "video%06d".format(index)).put("title", JSONObject().put("simpleText", "Video $index")))) }
        items.put(JSONObject("""{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"page-2"}}}}"""))
        val page = NikTubeClient.parsePage(JSONObject().put("contents", items))
        assertEquals(30, page.videos.size)
        assertEquals("page-2", page.continuation)
    }
    @Test fun searchDeduplicatesVideosAndIgnoresNonVideoResults() {
        val video = """{"videoRenderer":{"videoId":"jNQXAC9IVRw","title":{"runs":[{"text":"Me at "},{"text":"the zoo"}]},"ownerText":{"runs":[{"text":"jawed"}]},"lengthText":{"simpleText":"0:19"}}}"""
        val result = NikTubeClient.parseVideos(JSONObject("""{"contents":[$video,{"channelRenderer":{"channelId":"channel"}},$video]}"""))
        assertEquals(listOf(NikTubeVideo("jNQXAC9IVRw", "Me at the zoo", "jawed", "0:19")), result)
    }

    @Test fun adaptivePlaybackRequiresBothVideoAndAudio() {
        val result = NikTubeClient.parseStream(JSONObject("""{"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[
            {"mimeType":"video/mp4","height":2160,"url":"https://example.com/4k"},
            {"mimeType":"video/mp4","height":1080,"url":"https://example.com/video"},
            {"mimeType":"audio/mp4","bitrate":128000,"url":"https://example.com/audio"}]}}"""))
        assertEquals(NikTubeStream("https://example.com/video", "https://example.com/audio"), result)
    }

    @Test fun encryptedFormatsAreNotMistakenForPlayableUrls() {
        assertThrows(IllegalStateException::class.java) {
            NikTubeClient.parseStream(JSONObject("""{"playabilityStatus":{"status":"OK"},"streamingData":{"formats":[{"signatureCipher":"s=secret","mimeType":"video/mp4"}]}}"""))
        }
    }

    @Test fun verificationFailuresPreserveTheServiceReason() {
        val error = assertThrows(IllegalStateException::class.java) {
            NikTubeClient.parseStream(JSONObject("""{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in to confirm you’re not a bot"}}"""))
        }
        assertEquals("Sign in to confirm you’re not a bot", error.message)
    }
}
