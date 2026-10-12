package com.newtube.sabr;

import android.net.Uri;
import java.io.IOException;
import java.util.Locale;

final class SabrRedirectException extends IOException {
    final Uri target;
    SabrRedirectException(String url) throws SabrException {
        super("SABR CDN redirect");
        Uri parsed = Uri.parse(url);
        String host = parsed.getHost();
        if (!"https".equals(parsed.getScheme()) || host == null
                || !host.toLowerCase(Locale.ROOT).endsWith(".googlevideo.com")
                || parsed.getUserInfo() != null || parsed.getFragment() != null
                || (parsed.getPort() != -1 && parsed.getPort() != 443)
                || !"/videoplayback".equals(parsed.getPath())) {
            throw new SabrException("invalid_cdn_redirect");
        }
        target = parsed;
    }
}
