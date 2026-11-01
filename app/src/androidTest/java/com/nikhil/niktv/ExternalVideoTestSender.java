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
        if (target == null || !target.endsWith(".regression")) { finish(); return; }
        final Uri uri = Uri.parse("content://" + getPackageName() + ".externalfixture/video/" + getIntent().getStringExtra("nonce"));
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
            runOnUiThread(() -> {
                Intent open = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp2t").setPackage(target)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                open.setClipData(ClipData.newRawUri("Silent video fixture", uri));
                startActivity(open);
                finish();
            });
        }, "external-video-test-sender").start();
    }
}
