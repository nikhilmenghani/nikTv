package com.nikhil.niktv.data

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class, manifest = Config.NONE)
class StreamingPortalGuideTest {
    private val now = 1_000_000L
    private val row = """{"id":"programme-id","name":"Current film","start_timestamp":"999","stop_timestamp":"1001","duration":"2","descr":"Skipped description","mark_archive":0}"""

    @Test fun actualWioWrapperKeepsTimedRowsAndIgnoresEmptyChannels() {
        val guide = readStreamingPortalGuide("""{"js":{"data":{"185":[$row],"71505":[]}},"text":"provider debug"}""".reader(), now)
        assertEquals(setOf("185"), guide.keys)
        assertEquals("Current film", guide.getValue("185").single().title)
        assertEquals(999_000L, guide.getValue("185").single().startTimeMillis)
    }

    @Test fun nestedChannelIdentityMayFollowTheProgrammeArray() {
        val guide = readStreamingPortalGuide("""{"js":[{"epg":[$row],"ch_id":"42"}]}""".reader(), now)
        assertEquals(setOf("42"), guide.keys)
        assertEquals(1, guide.getValue("42").size)
    }

    @Test fun callbackWrappedGuideStillWorksWithoutBufferingTheWholeResponse() {
        assertEquals(1, readStreamingPortalGuide("callback({\"js\":{\"42\":[$row]}}); debug text".reader(), now).getValue("42").size)
    }

    @Test fun channelMapsAliasesAndDuplicatesAreHandled() {
        val alias = """{"title":"Next","start_ts":1000,"end_ts":1002}"""
        val guide = readStreamingPortalGuide("""{"js":{"42":[$row,$row],"43":[$alias]}}""".reader(), now)
        assertEquals(1, guide.getValue("42").size)
        assertEquals(1_000_000L, guide.getValue("43").single().startTimeMillis)
    }

    @Test fun millisecondTimestampsAreNotMultipliedAgain() {
        val wall = 1_790_000_000_000L
        val guide = readStreamingPortalGuide("""{"js":{"1":[{"name":"Current","start_timestamp":${wall-1000},"end_timestamp":${wall+1000}}]}}""".reader(), wall)
        assertEquals(wall-1000, guide.getValue("1").single().startTimeMillis)
    }

    @Test fun staleAndUntimedRowsNeverBecomeNowPlayingFromSharedGuide() {
        val guide = readStreamingPortalGuide("""{"js":{"1":[{"name":"Old","start_timestamp":900,"stop_timestamp":1000},{"name":"Untimed"},{"name":"No channel info","start_timestamp":999,"stop_timestamp":1001}]}}""".reader(), now)
        assertTrue(guide.isEmpty())
    }

    @Test fun programmeRowsAreBoundedDuringStreaming() {
        val rows = (1..21_000).joinToString(",") { row.replace("Current film", "Film $it") }
        val guide = readStreamingPortalGuide("""{"js":{"1":[$rows]}}""".reader(), now)
        assertEquals(20_000, guide.getValue("1").size)
    }
}
