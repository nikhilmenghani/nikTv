(function () {
    var media = [], frames = [], known = {}, visited = [], budget = 700;
    function absolute(value, base) {
        if (typeof value !== 'string' || value.length > 16384) return '';
        try {
            var url = new URL(value.replace(/&amp;/g, '&'), base);
            return /^(https?:)$/.test(url.protocol) && !url.username && !url.password ? url.href : '';
        } catch (_) { return ''; }
    }
    function isMedia(url) {
        return /\.(m3u8|mpd|mp4|webm|mkv|ts)([?#]|$)/i.test(url) || /[?&](format|type|mime)=(?:[^&]*(m3u8|mpegurl|dash|video))/i.test(url);
    }
    function add(value, base, explicit) {
        var url = absolute(value, base);
        if (!url || known[url] || /(?:doubleclick\.net|googlesyndication\.com|\/vast[/?]|\/preroll[/?])/i.test(url)) return;
        if (!explicit && !isMedia(url)) return;
        known[url] = true;
        if (media.length < 32) media.push(url);
    }
    function walk(value, base, depth) {
        if (--budget < 0 || depth > 5 || value == null) return;
        if (typeof value === 'string') { add(value, base, false); return; }
        if (typeof value !== 'object' || visited.indexOf(value) >= 0) return;
        visited.push(value);
        if (Array.isArray(value)) {
            value.slice(0, 30).forEach(function (v) { walk(v, base, depth + 1); });
        } else {
            ['file','src','source','sources','url','hls','dash','mpd','m3u8','video','playback_url','contentUrl','embedUrl'].forEach(function (k) {
                try { if (value[k] != null) walk(value[k], base, depth + 1); } catch (_) {}
            });
            if (depth < 2) Object.keys(value).slice(0, 30).forEach(function (k) {
                try { walk(value[k], base, depth + 1); } catch (_) {}
            });
        }
    }
    function inspect(win, depth) {
        if (depth > 2) return;
        var doc, base;
        try { doc = win.document; base = doc.baseURI || win.location.href; } catch (_) { return; }
        try {
            Array.prototype.slice.call(doc.querySelectorAll('video,audio,video source,audio source'), 0, 30).forEach(function (node) { add(node.currentSrc || node.src || node.getAttribute('src'), base, true); });
        } catch (_) {}
        try {
            if (typeof win.jwplayer === 'function') {
                var jw = win.jwplayer();
                if (jw && typeof jw.getPlaylist === 'function') walk(jw.getPlaylist(), base, 0);
                if (jw && typeof jw.getConfig === 'function') walk(jw.getConfig(), base, 0);
            }
        } catch (_) {}
        try {
            if (win.videojs && typeof win.videojs.getPlayers === 'function') {
                var players = win.videojs.getPlayers();
                Object.keys(players).slice(0, 10).forEach(function (key) {
                    var p = players[key];
                    if (p && typeof p.currentSrc === 'function') add(p.currentSrc(), base, true);
                    if (p && typeof p.currentSources === 'function') walk(p.currentSources(), base, 0);
                });
            }
        } catch (_) {}
        ['player','videoPlayer','myPlayer','plyr','plyrPlayer','shakaPlayer','hls','_hls','hlsInstance','clapprPlayer','clappr','dp','dplayer','bitmovinplayer','bitmovinPlayer'].forEach(function (key) {
            try {
                var p = win[key];
                if (!p) return;
                walk(p.source || p.options || p.options_ || p.config, base, 0);
                add(p.url || p.src, base, false);
                ['currentSrc','getAssetUri','getPlaybackURL','getSourceUri'].forEach(function (method) { try { if (typeof p[method] === 'function') add(p[method](), base, true); } catch (_) {} });
                if (typeof p.getSource === 'function') walk(p.getSource(), base, 0);
            } catch (_) {}
        });
        try {
            Array.prototype.slice.call(doc.querySelectorAll('meta[property^="og:video"],meta[name="twitter:player:stream"],meta[itemprop="contentUrl"],meta[itemprop="embedUrl"]'), 0, 30).forEach(function (node) { add(node.content, base, false); });
            Array.prototype.slice.call(doc.querySelectorAll('[data-src],[data-source],[data-file],[data-url],[data-hls],[data-m3u8],[data-stream],[data-video],[data-mpd]'), 0, 60).forEach(function (node) {
                ['src','source','file','url','hls','m3u8','stream','video','mpd'].forEach(function (key) { add(node.getAttribute('data-' + key), base, false); });
            });
            Array.prototype.slice.call(doc.querySelectorAll('script'), 0, 40).forEach(function (node) {
                var text = node.textContent || '';
                if (text.length > 262144) return;
                if (/^application\/(ld\+)?json$/i.test(node.type || '')) { try { walk(JSON.parse(text), base, 0); } catch (_) {} }
                var urls = text.match(/https?(?::|\\u003[aA])(?:\\?\/){2}[^\s"'<>`]{5,16384}/g) || [];
                urls.slice(0, 30).forEach(function (url) { add(url.replace(/\\u0026/g, '&').replace(/\\u003[aA]/g, ':').replace(/\\\//g, '/'), base, false); });
                var encoded = text.match(/["']([A-Za-z0-9+/]{30,8192}={0,2})["']/g) || [];
                encoded.slice(0, 10).forEach(function (value) { try { add(win.atob(value.slice(1, -1)), base, false); } catch (_) {} });
            });
        } catch (_) {}
        try { win.performance.getEntriesByType('resource').slice(-150).forEach(function (entry) { add(entry.name, base, false); }); } catch (_) {}
        try {
            Array.prototype.slice.call(doc.querySelectorAll('iframe[src],embed[src],frame[src]'), 0, 10).forEach(function (node) {
                var url = absolute(node.src || node.getAttribute('src'), base);
                if (url && url !== base && frames.indexOf(url) < 0 && frames.length < 8) frames.push(url);
                try { if (node.contentWindow) inspect(node.contentWindow, depth + 1); } catch (_) {}
            });
        } catch (_) {}
    }
    inspect(window, 0);
    media.sort(function (a, b) {
        function rank(url) { return /\.(m3u8|mpd)([?#]|$)/i.test(url) ? 0 : /\.(ts)([?#]|$)/i.test(url) ? 2 : 1; }
        return rank(a) - rank(b);
    });
    return JSON.stringify({media: media, frames: frames});
})()
