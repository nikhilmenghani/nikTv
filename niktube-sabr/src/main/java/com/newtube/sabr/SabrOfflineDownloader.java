package com.newtube.sabr;

import android.net.Uri;
import android.os.StatFs;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Downloads selected container bytes using the same validated framing as playback. */
public final class SabrOfflineDownloader {
    public interface Progress { void update(long bytes, long positionUs, long durationUs); }
    private SabrOfflineDownloader() {}

    public static void download(SabrStreamInfo info, SabrStreamInfo.Track track,
            SabrStreamInfo.Track audio, DataSource.Factory factory, File output,
            BooleanSupplier canceled, Progress progress) throws IOException {
        SabrProtocol.State state = new SabrProtocol.State(0, info.durationUs);
        Uri endpoint = info.endpoint;
        int idle = 0;
        int redirects = 0;
        long requestNumber = 0;
        byte[] buffer = new byte[64 * 1024];
        try (RandomAccessFile target = new RandomAccessFile(output, "rw")) {
            target.setLength(0);
            while (state.endUs < state.knownDurationUs) {
                if (canceled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IOException("Download canceled");
                if (new StatFs(output.getParent()).getAvailableBytes() < 32L * 1024 * 1024) throw new IOException("Not enough storage");
                long checkpoint = target.length();
                DataSource source = factory.createDataSource();
                try {
                    byte[] body = SabrProtocol.request(info, track, track.type == C.TRACK_TYPE_VIDEO ? audio : null,
                            state, state.endUs, 1_000_000, 1f);
                    source.open(new DataSpec.Builder().setUri(SabrMediaSource.numberedUri(endpoint, ++requestNumber))
                            .setHttpMethod(DataSpec.HTTP_METHOD_POST).setHttpBody(body)
                            .setHttpRequestHeaders(Map.of("Content-Type", "application/x-protobuf", "Accept", "application/vnd.yt-ump")).build());
                    InputStream raw = new InputStream() {
                        @Override public int read() throws IOException { byte[] one = new byte[1]; return read(one, 0, 1) == -1 ? -1 : one[0] & 255; }
                        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                            if (canceled.getAsBoolean()) throw new IOException("Download canceled");
                            return source.read(bytes, offset, length);
                        }
                    };
                    SabrProtocol.ResponseInput response = new SabrProtocol.ResponseInput(raw, info, track, state);
                    int count;
                    while ((count = response.read(buffer)) != -1) target.write(buffer, 0, count);
                    if (!response.cleanEof) throw new IOException("Incomplete media response");
                    if (response.result.endUs <= state.endUs && response.completedInitializations == 0) {
                        if (++idle > 8) throw new IOException("The server stopped delivering download data");
                    } else idle = 0;
                    state = response.result;
                    progress.update(target.length(), state.endUs, state.knownDurationUs);
                } catch (SabrRedirectException redirect) {
                    target.setLength(checkpoint); target.seek(checkpoint);
                    if (++redirects > 3) throw new IOException("Too many media redirects");
                    endpoint = redirect.target;
                } catch (IOException error) {
                    target.setLength(checkpoint); target.seek(checkpoint); throw error;
                } finally { source.close(); }
                if (state.backoffMs > 0 || idle > 0) {
                    try { Thread.sleep(Math.max(state.backoffMs, 250)); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException("Download canceled"); }
                }
            }
            if (!state.initialized || target.length() == 0) throw new IOException("No media was downloaded");
        }
    }
}
