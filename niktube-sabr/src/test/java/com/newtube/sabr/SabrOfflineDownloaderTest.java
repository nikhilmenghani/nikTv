package com.newtube.sabr;

import android.app.Application;
import androidx.media3.common.C;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import java.io.File;
import java.io.IOException;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class SabrOfflineDownloaderTest {
    @Test public void selectedAudioAndVideoDownloadToTheEndWithoutPlayback() throws Exception {
        SabrFixtures fixture = new SabrFixtures(name -> getClass().getResourceAsStream("/" + name), false);
        SabrStreamInfo.Track audio = fixture.info.tracks.stream().filter(t -> t.type == C.TRACK_TYPE_AUDIO).findFirst().get();
        File directory = RuntimeEnvironment.getApplication().getCacheDir();
        org.robolectric.shadows.ShadowStatFs.registerStats(directory.getAbsolutePath(), 1_000_000, 900_000, 900_000);
        for (SabrStreamInfo.Track track : fixture.info.tracks) {
            File target = new File(directory, "offline-" + track.format.id + ".mp4");
            final long[] end = {0};
            try {
                SabrOfflineDownloader.download(fixture.info, track, audio, fixture, target, () -> false, (bytes, position, duration) -> end[0] = position);
                assertTrue(target.length() > 1000);
                assertTrue(end[0] >= fixture.info.durationUs - 100_000);
            } finally { target.delete(); }
        }
        assertEquals(fixture.openCount.get(), fixture.closedCount.get());
    }
    @Test public void canceledDownloadDoesNotOpenTheNetwork() throws Exception {
        SabrFixtures fixture = new SabrFixtures(name -> getClass().getResourceAsStream("/" + name), false);
        File target = new File(RuntimeEnvironment.getApplication().getCacheDir(), "cancel.mp4");
        try {
            assertThrows(IOException.class, () -> SabrOfflineDownloader.download(fixture.info, fixture.info.tracks.get(0), null, fixture, target, () -> true, (bytes, position, duration) -> {}));
            assertEquals(0, fixture.openCount.get());
        } finally { target.delete(); }
    }
}
