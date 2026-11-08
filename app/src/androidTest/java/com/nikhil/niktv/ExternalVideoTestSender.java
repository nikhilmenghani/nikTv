package com.nikhil.niktv;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import java.io.File;
import java.io.FileOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Test-APK UID models an independent file manager; Android framework only. */
public class ExternalVideoTestSender extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final String target = getIntent().getStringExtra("target");
        final boolean mp4 = getIntent().getBooleanExtra("mp4", false);
        if (target == null || !target.endsWith(".regression")) { finish(); return; }
        final Uri uri = Uri.parse("content://" + getPackageName() + ".externalfixture/video/" + (mp4 ? "mp4/" : "") + getIntent().getStringExtra("nonce"));
        new Thread(() -> {
            try (ZipInputStream zip = new ZipInputStream(getAssets().open("playback-fixtures.zip"));
                 FileOutputStream output = new FileOutputStream(new File(getCacheDir(), "external-video-fixture.ts"))) {
                ZipEntry entry;
                int segments = 0;
                while ((entry = zip.getNextEntry()) != null) {
                    if (!entry.getName().matches("seg[0-9]{3}\\.ts")) continue;
                    if (!entry.getName().equals(String.format(java.util.Locale.ROOT, "seg%03d.ts", segments++)))
                        throw new IllegalStateException("Unordered fixture segments");
                    byte[] bytes = new byte[8192];
                    int count;
                    while ((count = zip.read(bytes)) != -1) output.write(bytes, 0, count);
                }
                if (segments != 60) throw new IllegalStateException("Missing silent video fixture");
            } catch (Exception failure) { throw new RuntimeException(failure); }
            if (mp4) {
                android.media.MediaExtractor extractor = new android.media.MediaExtractor();
                android.media.MediaMuxer muxer = null;
                try {
                    extractor.setDataSource(new File(getCacheDir(), "external-video-fixture.ts").getAbsolutePath());
                    int video = -1;
                    for (int index = 0; index < extractor.getTrackCount(); index++) {
                        if (extractor.getTrackFormat(index).getString(android.media.MediaFormat.KEY_MIME).startsWith("video/")) { video = index; break; }
                    }
                    if (video < 0) throw new IllegalStateException("Missing fixture video track");
                    extractor.selectTrack(video);
                    muxer = new android.media.MediaMuxer(new File(getCacheDir(), "external-video-fixture.mp4").getAbsolutePath(), android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                    int outputTrack = muxer.addTrack(extractor.getTrackFormat(video));
                    muxer.start();
                    java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocateDirect(2 * 1024 * 1024);
                    android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
                    long firstTime = extractor.getSampleTime();
                    while (true) {
                        buffer.clear();
                        int size = extractor.readSampleData(buffer, 0);
                        if (size < 0) break;
                        info.set(0, size, Math.max(0, extractor.getSampleTime() - firstTime), extractor.getSampleFlags());
                        muxer.writeSampleData(outputTrack, buffer, info);
                        extractor.advance();
                    }
                    muxer.stop();
                } catch (Exception failure) { throw new RuntimeException(failure); }
                finally { extractor.release(); if (muxer != null) muxer.release(); }
            }
            runOnUiThread(() -> {
                Intent open = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, mp4 ? "video/mp4" : "video/mp2t").setPackage(target)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                open.setClipData(ClipData.newRawUri("Silent video fixture", uri));
                startActivity(open);
                finish();
            });
        }, "external-video-test-sender").start();
    }
}
