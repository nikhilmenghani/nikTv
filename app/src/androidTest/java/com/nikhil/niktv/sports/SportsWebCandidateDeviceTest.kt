package com.nikhil.niktv.sports

import android.content.Intent
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.ui.PlaybackFixtureHostActivity
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Executes the shipped extractor against fake DOM/resources: no network or media playback. */
class SportsWebCandidateDeviceTest {
    @Test fun resourceChunksCannotReplaceAManifestButExplicitWholeTsRemainsSupported() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        val script = context.assets.open("sports/extract-media.js").bufferedReader().use { it.readText() }
        val fixture = """
            (function() {
                function extract(resources, explicit) {
                    var fake = {
                        document: {baseURI:'https://fixture.example.test/', querySelectorAll:function(selector) {
                            return selector === 'video,audio,video source,audio source' && explicit ? [{currentSrc:explicit}] : [];
                        }},
                        location:{href:'https://fixture.example.test/'},
                        performance:{getEntriesByType:function(){return resources.map(function(url){return {name:url};});}}
                    };
                    return JSON.parse((function(window) {return $script;})(fake));
                }
                var chunks = ['https://cdn.example.test/segment01.ts?mime=video', 'https://cdn.example.test/chunk02.m4s?type=video'];
                return JSON.stringify([
                    extract(chunks, ''),
                    extract(chunks.concat(['https://cdn.example.test/master.m3u8']), ''),
                    extract(chunks, 'https://cdn.example.test/whole-live-stream.ts')
                ]);
            })()
        """.trimIndent()
        val done = CountDownLatch(1)
        val result = AtomicReference<String>()
        val activity = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PlaybackFixtureHostActivity
        var web: WebView? = null
        try {
            instrumentation.runOnMainSync {
                web = WebView(activity).apply {
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            view.evaluateJavascript(fixture) { value -> result.set(value); done.countDown() }
                        }
                    }
                    loadDataWithBaseURL("https://fixture.example.test/", "<html></html>", "text/html", "UTF-8", null)
                }
            }
            assertTrue("Extractor script completes", done.await(10, TimeUnit.SECONDS))
            val cases = Json.parseToJsonElement(Json.parseToJsonElement(result.get()).jsonPrimitive.content).jsonArray
                .map { sportsWebCandidates(it.toString()) }
            assertTrue(cases[0].media.isEmpty())
            assertEquals(listOf("https://cdn.example.test/master.m3u8"), cases[1].media)
            assertEquals(listOf("https://cdn.example.test/whole-live-stream.ts"), cases[2].media)
        } finally {
            instrumentation.runOnMainSync { web?.destroy(); activity.finish() }
        }
    }
}
