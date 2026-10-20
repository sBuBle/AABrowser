/*
 * Copyright (C) 2025 AABrowser Contributors (https://github.com/kododake/AABrowser)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://gnu.org>.
 */

package com.kododake.aabrowser.web

object WebScripts {
    val DRM_L3_ENFORCER_JS = """
        (function() {
            try {
                const host = window.location.hostname;
                if (host && (
                    host === 'youtube.com' || host.endsWith('.youtube.com') ||
                    host === 'googlevideo.com' || host.endsWith('.googlevideo.com') ||
                    host === 'youtube-nocookie.com' || host.endsWith('.youtube-nocookie.com')
                )) {
                    return;
                }
            } catch (_) {}

            if (typeof navigator === 'undefined' || !navigator.requestMediaKeySystemAccess) {
                return;
            }

            const patchKey = typeof Symbol !== 'undefined' && Symbol.for ? Symbol.for('__aab_drm_l3_enforced__') : '__aab_drm_l3_enforced__';
            if (navigator[patchKey]) return;

            const originalRequest = navigator.requestMediaKeySystemAccess;
            const patchedRequest = function requestMediaKeySystemAccess(keySystem, configs) {
                if (keySystem === 'com.widevine.alpha' && configs) {
                    try {
                        const newConfigs = Array.from(configs).map(config => {
                            const newConfig = Object.assign({}, config);
                            if (config.videoCapabilities) {
                                newConfig.videoCapabilities = Array.from(config.videoCapabilities).map(cap => {
                                    const newCap = Object.assign({}, cap);
                                    if (newCap.robustness && typeof newCap.robustness === 'string' && newCap.robustness.startsWith('HW_SECURE')) {
                                        newCap.robustness = 'SW_SECURE_DECODE';
                                    }
                                    return newCap;
                                });
                            }
                            if (config.audioCapabilities) {
                                newConfig.audioCapabilities = Array.from(config.audioCapabilities).map(cap => {
                                    const newCap = Object.assign({}, cap);
                                    if (newCap.robustness && typeof newCap.robustness === 'string' && newCap.robustness.startsWith('HW_SECURE')) {
                                        newCap.robustness = 'SW_SECURE_DECODE';
                                    }
                                    return newCap;
                                });
                            }
                            return newConfig;
                        });
                        return originalRequest.call(navigator, keySystem, newConfigs).catch(function() {
                            return originalRequest.call(navigator, keySystem, configs);
                        });
                    } catch (_) {
                        return originalRequest.call(navigator, keySystem, configs);
                    }
                }
                return originalRequest.call(navigator, keySystem, configs);
            };

            try {
                Object.defineProperty(patchedRequest, 'name', { value: 'requestMediaKeySystemAccess' });
                patchedRequest.toString = function() {
                    return 'function requestMediaKeySystemAccess() { [native code] }';
                };
            } catch (_) {}

            try {
                Object.defineProperty(navigator, patchKey, { value: true, enumerable: false, configurable: false });
                Object.defineProperty(navigator, 'requestMediaKeySystemAccess', {
                    value: patchedRequest,
                    writable: true,
                    configurable: true
                });
            } catch (_) {
                navigator.requestMediaKeySystemAccess = patchedRequest;
            }
        })();
    """.trimIndent()

    /**
     * Cosmetic part of the ad blocker: hides ad containers using the filter lists' element hiding
     * rules (fetched from the native `AABAdBlock` bridge) plus a few built-in selectors, and skips
     * YouTube video ads, which are served from the same hosts as the videos themselves.
     *
     * Generic rules are only requested for the class and id names actually present in the page,
     * as uBlock Origin does, instead of injecting the thousands of selectors EasyList contains.
     */
    val AD_BLOCK_COSMETIC_JS = """
        (function() {
            const patchKey = typeof Symbol !== 'undefined' && Symbol.for ? Symbol.for('__aab_ad_block_cosmetic__') : '__aab_ad_block_cosmetic__';
            if (window[patchKey]) return;
            try {
                Object.defineProperty(window, patchKey, { value: true, enumerable: false, configurable: false });
            } catch (_) {
                return;
            }

            const css = [
                'ins.adsbygoogle',
                '.adsbygoogle',
                '[id^="google_ads_iframe"]',
                '[id^="div-gpt-ad"]',
                'div[data-google-query-id]',
                'iframe[src*="doubleclick.net"]',
                'iframe[src*="googlesyndication.com"]',
                'iframe[src*="amazon-adsystem.com"]',
                'amp-ad',
                'amp-embed[type="taboola"]',
                '.trc_rbox_container',
                '[id^="taboola-"]',
                '.OUTBRAIN',
                'ytd-ad-slot-renderer',
                'ytd-banner-promo-renderer',
                'ytd-display-ad-renderer',
                'ytd-in-feed-ad-layout-renderer',
                'ytd-promoted-sparkles-web-renderer',
                'ytd-player-legacy-desktop-watch-ads-renderer',
                '#masthead-ad',
                '#player-ads',
                '.ytp-ad-overlay-container',
                'ad-slot-renderer',
                'ytm-companion-ad-renderer',
                'ytm-promoted-sparkles-web-renderer',
                'ytm-promoted-video-renderer'
            ].join(',') + '{display:none !important;}';

            const bridge = window.AABAdBlock;
            let isTopFrame = true;
            try { isTopFrame = window.top === window; } catch (_) { isTopFrame = false; }

            function addStyle(text) {
                if (!text) return true;
                const root = document.head || document.documentElement;
                if (!root) return false;
                const style = document.createElement('style');
                style.setAttribute('data-aab', 'adblock');
                style.textContent = text;
                root.appendChild(style);
                return true;
            }

            let pageCss = '';
            try {
                if (bridge && bridge.pageCss) pageCss = bridge.pageCss(location.href, isTopFrame) || '';
            } catch (_) {}
            const initialCss = css + '\n' + pageCss;
            if (!addStyle(initialCss)) {
                document.addEventListener('DOMContentLoaded', function() { addStyle(initialCss); }, { once: true });
            }

            if (bridge && bridge.genericCss) {
                const seen = new Set();
                let pending = [];
                let flushScheduled = false;

                function note(token) {
                    if (token.length > 1 && !seen.has(token)) {
                        seen.add(token);
                        pending.push(token);
                    }
                }
                function collect(element) {
                    if (element.id) note('#' + element.id);
                    const classes = element.classList;
                    if (classes) for (let i = 0; i < classes.length; i++) note('.' + classes[i]);
                }
                function scan(root) {
                    if (!root || root.nodeType !== 1) return;
                    collect(root);
                    const elements = root.querySelectorAll('[id],[class]');
                    for (let i = 0; i < elements.length; i++) collect(elements[i]);
                }
                function flush() {
                    flushScheduled = false;
                    if (!pending.length) return;
                    const batch = pending.join(' ');
                    pending = [];
                    try { addStyle(bridge.genericCss(location.href, batch)); } catch (_) {}
                }
                function scheduleFlush() {
                    if (flushScheduled) return;
                    flushScheduled = true;
                    setTimeout(flush, 250);
                }
                function start() {
                    scan(document.documentElement);
                    flush();
                    new MutationObserver(function(mutations) {
                        for (let i = 0; i < mutations.length; i++) {
                            const added = mutations[i].addedNodes;
                            for (let j = 0; j < added.length; j++) scan(added[j]);
                        }
                        if (pending.length) scheduleFlush();
                    }).observe(document.documentElement, { childList: true, subtree: true });
                }
                if (document.readyState === 'loading') {
                    document.addEventListener('DOMContentLoaded', start, { once: true });
                } else {
                    start();
                }
            }

            let host = '';
            try { host = window.location.hostname || ''; } catch (_) {}
            const isYouTube = host === 'youtube.com' || host.endsWith('.youtube.com') ||
                host === 'youtube-nocookie.com' || host.endsWith('.youtube-nocookie.com');
            if (!isYouTube) return;

            setInterval(function() {
                if (document.hidden) return;
                const player = document.querySelector('.html5-video-player.ad-showing');
                if (!player) return;
                const skipButton = player.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button');
                if (skipButton) {
                    skipButton.click();
                    return;
                }
                const video = player.querySelector('video');
                if (video && isFinite(video.duration) && video.duration > 0 && video.currentTime < video.duration - 0.1) {
                    try { video.currentTime = video.duration; } catch (_) {}
                }
            }, 400);
        })();
    """.trimIndent()

    /**
     * Keeps progressive audio/video streams (internet radio, plain <video src>, native HLS) alive
     * on unreliable connections: when a stream dies with a network error or stops receiving data,
     * it is reloaded with exponential backoff and resumed at the last position, and anything that
     * failed while offline is retried as soon as the connection comes back. Adaptive (MSE/blob)
     * players manage their own buffering and are left alone.
     */
    val MEDIA_STREAM_RECOVERY_JS = """
        (function() {
            if (typeof HTMLMediaElement === 'undefined') return;
            const patchKey = typeof Symbol !== 'undefined' && Symbol.for ? Symbol.for('__aab_media_recovery__') : '__aab_media_recovery__';
            if (window[patchKey]) return;
            try {
                Object.defineProperty(window, patchKey, { value: true, enumerable: false, configurable: false });
            } catch (_) {
                return;
            }

            const MAX_ATTEMPTS = 6;
            const MAX_BACKOFF_MS = 16000;
            const STALL_TIMEOUT_MS = 12000;
            // Live progressive streams (internet radio) never resume a dropped connection on their own.
            const LIVE_STALL_TIMEOUT_MS = 6000;
            const HAVE_FUTURE_DATA = 3;
            const MEDIA_ERR_NETWORK = 2;
            const MEDIA_ERR_SRC_NOT_SUPPORTED = 4;

            const states = new WeakMap();
            const pending = new Set();

            function isRecoverable(media) {
                return /^https?:/i.test(media.currentSrc || media.src || '');
            }

            function clearStall(state) {
                if (state.stallTimer) {
                    clearTimeout(state.stallTimer);
                    state.stallTimer = 0;
                }
            }

            function recover(media) {
                const state = states.get(media);
                if (!state) return;
                state.retryTimer = 0;
                if (!state.wantsPlayback) {
                    pending.delete(media);
                    return;
                }
                if (!navigator.onLine) {
                    pending.add(media);
                    return;
                }
                pending.delete(media);
                const resumeAt = !state.live && isFinite(media.duration) ? state.position : 0;
                state.recovering = true;
                try {
                    if (resumeAt > 0) {
                        media.addEventListener('loadedmetadata', function() {
                            try { media.currentTime = resumeAt; } catch (_) {}
                        }, { once: true });
                    }
                    media.load();
                    const playback = media.play();
                    if (playback && typeof playback.catch === 'function') playback.catch(function() {});
                } catch (_) {}
            }

            function scheduleRecovery(media) {
                const state = states.get(media);
                if (!state || !state.wantsPlayback || state.retryTimer || !isRecoverable(media)) return;
                if (state.attempts >= MAX_ATTEMPTS) {
                    pending.add(media);
                    return;
                }
                const delay = Math.min(1000 * Math.pow(2, state.attempts), MAX_BACKOFF_MS);
                state.attempts++;
                state.retryTimer = setTimeout(function() { recover(media); }, delay);
            }

            function onStall(media, state) {
                if (!state.wantsPlayback || state.stallTimer || !isRecoverable(media)) return;
                const startPosition = media.currentTime;
                state.stallTimer = setTimeout(function() {
                    state.stallTimer = 0;
                    if (!media.paused && media.readyState < HAVE_FUTURE_DATA && media.currentTime === startPosition) {
                        scheduleRecovery(media);
                    }
                }, state.live ? LIVE_STALL_TIMEOUT_MS : STALL_TIMEOUT_MS);
            }

            function watch(media) {
                if (!(media instanceof HTMLMediaElement) || states.has(media)) return;
                const state = { attempts: 0, retryTimer: 0, stallTimer: 0, position: 0, wantsPlayback: !media.paused, recovering: false, live: false };
                states.set(media, state);

                // Recorded at load: when a live stream's connection drops, Chromium turns its infinite
                // duration into a finite one and fires 'pause' then 'ended', as if it had finished.
                media.addEventListener('loadedmetadata', function() { state.live = media.duration === Infinity; });

                media.addEventListener('play', function() { state.wantsPlayback = true; });
                media.addEventListener('playing', function() {
                    state.wantsPlayback = true;
                    state.recovering = false;
                    state.attempts = 0;
                    clearStall(state);
                    pending.delete(media);
                });
                media.addEventListener('pause', function() {
                    if (state.recovering || media.error || (state.live && media.ended)) return;
                    state.wantsPlayback = false;
                    clearStall(state);
                });
                media.addEventListener('ended', function() {
                    clearStall(state);
                    if (state.live && state.wantsPlayback && !media.loop) {
                        scheduleRecovery(media);
                        return;
                    }
                    state.wantsPlayback = false;
                });
                media.addEventListener('timeupdate', function() {
                    if (media.currentTime > 0) state.position = media.currentTime;
                });
                media.addEventListener('progress', function() { clearStall(state); });
                media.addEventListener('stalled', function() { onStall(media, state); });
                media.addEventListener('waiting', function() { onStall(media, state); });
                media.addEventListener('error', function() {
                    const error = media.error;
                    if (error && (error.code === MEDIA_ERR_NETWORK || error.code === MEDIA_ERR_SRC_NOT_SUPPORTED)) {
                        clearStall(state);
                        scheduleRecovery(media);
                    }
                });
            }

            // Media events do not bubble, but capturing listeners still see them for attached elements.
            ['loadstart', 'play'].forEach(function(type) {
                document.addEventListener(type, function(event) { watch(event.target); }, true);
            });

            // Detached elements such as `new Audio(url)` never reach the document, so catch them on play().
            try {
                const originalPlay = HTMLMediaElement.prototype.play;
                HTMLMediaElement.prototype.play = function play() {
                    try { watch(this); } catch (_) {}
                    return originalPlay.apply(this, arguments);
                };
            } catch (_) {}

            window.addEventListener('online', function() {
                Array.from(pending).forEach(function(media) {
                    const state = states.get(media);
                    if (!state) return;
                    if (state.retryTimer) {
                        clearTimeout(state.retryTimer);
                        state.retryTimer = 0;
                    }
                    state.attempts = 0;
                    recover(media);
                });
            });
        })();
    """.trimIndent()

    /**
     * Car-friendly buffering defaults for page media players (hls.js, dash.js, Shaka, video.js/VHS):
     * - moderate forward buffers and trimmed back buffers, sized to Chromium's MSE memory quotas;
     * - more patient retries, so a tunnel or dead zone does not end playback with a fatal error;
     * - no network-information ABR, because WebView reports a fixed ~10 Mbps downlink.
     * Only stock defaults are changed, values the page set itself always win, and YouTube (which
     * runs its own player) is left alone. Native <video>/<audio> buffering is not touched: Chromium
     * exposes no knob for it, and MEDIA_STREAM_RECOVERY_JS covers progressive streams.
     */
    val MEDIA_BUFFER_TUNING_JS = """
        (function() {
            'use strict';
            var w = window;

            // YouTube runs its own player: leave every YouTube frame (pages and embeds) completely untouched.
            try {
                var host = String(w.location.hostname || '').toLowerCase();
                var OWN_PLAYER_HOSTS = ['youtube.com', 'youtube-nocookie.com', 'youtubekids.com', 'youtube.googleapis.com', 'googlevideo.com', 'youtu.be'];
                for (var h = 0; h < OWN_PLAYER_HOSTS.length; h++) {
                    var d = OWN_PLAYER_HOSTS[h];
                    if (host === d || (host.length > d.length && host.slice(host.length - d.length - 1) === '.' + d)) return;
                }
            } catch (_) {
                return;
            }

            var defineProp = Object.defineProperty;
            var getOwnDesc = Object.getOwnPropertyDescriptor;
            var getProto = Object.getPrototypeOf;
            var objectKeys = Object.keys;
            var hasOwn = Object.prototype.hasOwnProperty;

            // Once per frame.
            var patchKey = typeof Symbol !== 'undefined' && Symbol.for ? Symbol.for('__aab_media_buffer_tuning__') : '__aab_media_buffer_tuning__';
            try {
                if (getOwnDesc(w, patchKey)) return;
                defineProp(w, patchKey, { value: true, enumerable: false, configurable: false, writable: false });
            } catch (_) {
                return;
            }
            if (typeof WeakSet !== 'function' || typeof WeakMap !== 'function') return;

            // Chromium gives <= 1 GB devices a 30 MiB video / 2 MiB audio MSE quota: never grow forward buffers there.
            var allowLargerForwardBuffer = true;
            try {
                var deviceMemory = navigator.deviceMemory;
                if (typeof deviceMemory === 'number' && deviceMemory > 0 && deviceMemory <= 1) allowLargerForwardBuffer = false;
            } catch (_) {}

            var MISSING = {};

            function isObj(v) {
                return v !== null && (typeof v === 'object' || typeof v === 'function');
            }

            function isPlainObject(v) {
                if (v === null || typeof v !== 'object') return false;
                var proto = getProto(v);
                return proto === Object.prototype || proto === null;
            }

            function readPath(obj, path) {
                var parts = path.split('.');
                for (var i = 0; i < parts.length; i++) {
                    if (!obj || typeof obj !== 'object' || !hasOwn.call(obj, parts[i])) return MISSING;
                    obj = obj[parts[i]];
                }
                return obj;
            }

            function writePath(obj, path, value) {
                var parts = path.split('.');
                for (var i = 0; i < parts.length - 1; i++) {
                    if (!isPlainObject(obj[parts[i]])) obj[parts[i]] = {};
                    obj = obj[parts[i]];
                }
                obj[parts[parts.length - 1]] = value;
            }

            function sameData(a, b) {
                if (a === b) return true;
                if (!isPlainObject(a) || !isPlainObject(b)) return false;
                var keys = objectKeys(a);
                if (keys.length !== objectKeys(b).length) return false;
                for (var i = 0; i < keys.length; i++) {
                    if (!hasOwn.call(b, keys[i]) || !sameData(a[keys[i]], b[keys[i]])) return false;
                }
                return true;
            }

            function keepLength(wrapper, original) {
                try { defineProp(wrapper, 'length', { value: original.length }); } catch (_) {}
                return wrapper;
            }

            // Calls onValue with the current and every later object assigned to window[name]. The page reads back
            // exactly what it assigned; the accessor stays configurable so later var/let/function declarations work.
            function watchGlobal(name, onValue) {
                function notify(v) {
                    if (!isObj(v)) return;
                    try { onValue(v); } catch (_) {}
                }
                var desc;
                try { desc = getOwnDesc(w, name); } catch (_) { return; }
                var value;
                if (desc) {
                    if (!hasOwn.call(desc, 'value')) {
                        try { notify(w[name]); } catch (_) {}
                        return;
                    }
                    value = desc.value;
                    notify(value);
                    if (!desc.configurable || !desc.writable) return;
                }
                var enumerable = desc ? !!desc.enumerable : false;
                function get() {
                    return value;
                }
                function set(v) {
                    value = v;
                    if (!enumerable) {
                        enumerable = true;
                        try { defineProp(w, name, { get: get, set: set, enumerable: true, configurable: true }); } catch (_) {}
                    }
                    notify(v);
                }
                try { defineProp(w, name, { get: get, set: set, enumerable: enumerable, configurable: true }); } catch (_) {}
            }

            // ---------------------------------------------------------------- hls.js (UMD global window.Hls)
            // Only Hls.DefaultConfig is changed, and only keys that still hold the stock value. Every new Hls()
            // deep-copies DefaultConfig and spreads the page's own config over it, so explicit page values win.

            // hls.js retries status 0 only while navigator.onLine === false. In a dead zone the phone usually still
            // reports online, so also retry status 0, bounded by maxNumRetry. 4xx stays non-retriable.
            function retryNetworkFailures(retryConfig, retryCount, isTimeout, response, retry) {
                return !!retry || !!(retryConfig && response && response.code === 0 && retryCount < retryConfig.maxNumRetry);
            }
            function hlsRetry(maxNumRetry, retryDelayMs, maxRetryDelayMs) {
                return { maxNumRetry: maxNumRetry, retryDelayMs: retryDelayMs, maxRetryDelayMs: maxRetryDelayMs };
            }
            function hlsPatientRetry(maxNumRetry) {
                var retry = hlsRetry(maxNumRetry, 1000, 8000);
                retry.shouldRetry = retryNetworkFailures;
                return retry;
            }
            function hlsLoader(ttfb, loadTime, timeoutRetry, errorRetry) {
                return { maxTimeToFirstByteMs: ttfb, maxLoadTimeMs: loadTime, timeoutRetry: timeoutRetry, errorRetry: errorRetry };
            }
            var HLS_SCALARS = [
                // key, stock value, car value
                ['maxMaxBufferLength', 600, 180],
                ['backBufferLength', Infinity, 30],
                ['abrBandWidthFactor', 0.95, 0.8]
            ];
            var HLS_POLICIES = [
                // key, stock LoaderConfig (identical in hls.js 1.4 - 1.7), car LoaderConfig
                ['fragLoadPolicy', hlsLoader(10000, 120000, hlsRetry(4, 0, 0), hlsRetry(6, 1000, 8000)),
                    function() { return hlsLoader(10000, 120000, hlsRetry(6, 0, 0), hlsPatientRetry(10)); }],
                ['playlistLoadPolicy', hlsLoader(10000, 20000, hlsRetry(2, 0, 0), hlsRetry(2, 1000, 8000)),
                    function() { return hlsLoader(10000, 20000, hlsRetry(4, 0, 0), hlsPatientRetry(8)); }],
                ['manifestLoadPolicy', hlsLoader(Infinity, 20000, hlsRetry(2, 0, 0), hlsRetry(1, 1000, 8000)),
                    function() { return hlsLoader(Infinity, 20000, hlsRetry(2, 0, 0), hlsPatientRetry(3)); }]
            ];
            var hlsClasses = new WeakSet();

            function tuneHls(Hls) {
                if (typeof Hls !== 'function' || hlsClasses.has(Hls)) return;
                hlsClasses.add(Hls);
                var cfg = Hls.DefaultConfig;
                if (!cfg || typeof cfg !== 'object' || typeof cfg.maxBufferLength !== 'number' ||
                    typeof cfg.maxMaxBufferLength !== 'number' || typeof cfg.abrEwmaDefaultEstimate !== 'number' ||
                    !('fragLoadingTimeOut' in cfg)) return;
                for (var i = 0; i < HLS_SCALARS.length; i++) {
                    var s = HLS_SCALARS[i];
                    if (hasOwn.call(cfg, s[0]) && cfg[s[0]] === s[1]) cfg[s[0]] = s[2];
                }
                for (var j = 0; j < HLS_POLICIES.length; j++) {
                    var p = HLS_POLICIES[j];
                    if (hasOwn.call(cfg, p[0]) && sameData(cfg[p[0]], { default: p[1] })) cfg[p[0]] = { default: p[2]() };
                }
            }

            watchGlobal('Hls', tuneHls);

            // ---------------------------------------------------------------- dash.js v4 / v5 (window.dashjs)
            // Applied inside MediaPlayer().create(), before the page holds the player, and only to settings that
            // still hold the stock value. The page's own updateSettings() calls come later and win.
            var DASH_TUNING = [
                // settings path, stock value(s), car value, grows the forward buffer
                ['streaming.buffer.bufferTimeDefault', [18], 30, true],
                ['streaming.buffer.stableBufferTime', [12], 30, true],
                ['streaming.buffer.bufferTimeAtTopQuality', [30], 60, true],
                ['streaming.buffer.fastSwitchEnabled', [null, true], false, false],
                ['streaming.retryAttempts.MediaSegment', [3], 8, false],
                ['streaming.retryIntervals.MediaSegment', [1000], 2000, false],
                ['streaming.retryAttempts.InitializationSegment', [3], 6, false],
                ['streaming.retryIntervals.InitializationSegment', [1000], 2000, false],
                ['streaming.retryAttempts.MPD', [3], 6, false],
                ['streaming.retryIntervals.MPD', [500], 1500, false],
                ['streaming.abr.throughput.bandwidthSafetyFactor', [0.9], 0.8, false],
                ['streaming.abr.bandwidthSafetyFactor', [0.9], 0.8, false]
            ];
            var dashSettingsObjects = new WeakSet();
            var dashPlayers = new WeakSet();
            var dashNamespaces = new WeakSet();
            var dashProxies = new WeakSet();
            var dashProxyFor = new WeakMap();

            function tuneDashSettings(player) {
                var settings = player.getSettings();
                // dash.js replaces the settings object on reset(); a shared context reuses it and must not be re-tuned.
                if (!settings || typeof settings !== 'object' || dashSettingsObjects.has(settings)) return;
                dashSettingsObjects.add(settings);
                var patch = null;
                for (var i = 0; i < DASH_TUNING.length; i++) {
                    var t = DASH_TUNING[i];
                    if (t[3] && !allowLargerForwardBuffer) continue;
                    var current = readPath(settings, t[0]);
                    if (current !== MISSING && t[1].indexOf(current) !== -1) writePath(patch = patch || {}, t[0], t[2]);
                }
                if (patch) player.updateSettings(patch);
            }

            function instrumentDashPlayer(player) {
                if (!isObj(player) || dashPlayers.has(player) ||
                    typeof player.getSettings !== 'function' || typeof player.updateSettings !== 'function') return;
                dashPlayers.add(player);
                try { tuneDashSettings(player); } catch (_) {}
                // reset() and resetSettings() restore dash.js defaults: put the car defaults back underneath.
                ['reset', 'resetSettings'].forEach(function(name) {
                    var original = player[name];
                    if (typeof original !== 'function') return;
                    player[name] = keepLength(function() {
                        var result = original.apply(this, arguments);
                        try { tuneDashSettings(player); } catch (_) {}
                        return result;
                    }, original);
                });
            }

            function instrumentDashFactoryResult(result) {
                try {
                    if (isObj(result) && typeof result.create === 'function') {
                        var create = result.create;
                        result.create = keepLength(function() {
                            var player = create.apply(this, arguments);
                            try { instrumentDashPlayer(player); } catch (_) {}
                            return player;
                        }, create);
                    }
                } catch (_) {}
                return result;
            }

            function wrapDashFactory(factory) {
                if (typeof factory !== 'function' || typeof Proxy !== 'function' || typeof Reflect !== 'object') return factory;
                if (dashProxies.has(factory)) return factory;
                var proxy = dashProxyFor.get(factory);
                if (proxy) return proxy;
                proxy = new Proxy(factory, {
                    apply: function(target, thisArg, args) {
                        return instrumentDashFactoryResult(Reflect.apply(target, thisArg, args));
                    },
                    construct: function(target, args, newTarget) {
                        return instrumentDashFactoryResult(Reflect.construct(target, args, newTarget));
                    }
                });
                dashProxies.add(proxy);
                dashProxyFor.set(factory, proxy);
                return proxy;
            }

            // dash.js assigns window.dashjs = {} first and adds MediaPlayer to it afterwards, so watch that property.
            function hookDashNamespace(ns) {
                if (dashNamespaces.has(ns)) return;
                dashNamespaces.add(ns);
                var desc = getOwnDesc(ns, 'MediaPlayer');
                if (desc && (!desc.configurable || !hasOwn.call(desc, 'value'))) return;
                var wrapped = wrapDashFactory(desc ? desc.value : undefined);
                var enumerable = desc ? !!desc.enumerable : false;
                function get() {
                    return wrapped;
                }
                function set(v) {
                    wrapped = wrapDashFactory(v);
                    if (!enumerable) {
                        enumerable = true;
                        try { defineProp(ns, 'MediaPlayer', { get: get, set: set, enumerable: true, configurable: true }); } catch (_) {}
                    }
                }
                defineProp(ns, 'MediaPlayer', { get: get, set: set, enumerable: enumerable, configurable: true });
            }

            watchGlobal('dashjs', hookDashNamespace);

            // ---------------------------------------------------------------- Shaka Player (window.shaka)
            // Applied once per player at its first load()/preload(), skipping every path the page configured
            // itself and every value that is no longer the stock default. Later page configure() calls win.
            var SHAKA_TUNING = [
                // config path, stock value, car value, all-or-nothing group, grows the forward buffer
                ['streaming.bufferingGoal', 10, 30, '', true],
                ['streaming.retryParameters.maxAttempts', 2, 12, 'retry', false],
                ['streaming.retryParameters.baseDelay', 1000, 1500, 'retry', false],
                ['streaming.retryParameters.backoffFactor', 2, 1.25, 'retry', false],
                // WebView has no network quality estimator: navigator.connection.downlink is a constant ~10 Mbps.
                ['abr.useNetworkInformation', true, false, '', false]
            ];
            var shakaPagePaths = new WeakMap();
            var shakaTuned = new WeakSet();
            var shakaPrototypes = new WeakSet();

            function collectPaths(out, value, prefix, depth) {
                if (isPlainObject(value) && depth < 8) {
                    var keys = objectKeys(value);
                    if (!keys.length && prefix) out.push(prefix);
                    for (var i = 0; i < keys.length; i++) collectPaths(out, value[keys[i]], prefix ? prefix + '.' + keys[i] : keys[i], depth + 1);
                } else if (prefix) {
                    out.push(prefix);
                }
            }

            function overlaps(a, b) {
                return a === b || a.indexOf(b + '.') === 0 || b.indexOf(a + '.') === 0;
            }

            function shakaPatch(player) {
                var cfg = player.getConfiguration();
                var pagePaths = shakaPagePaths.get(player) || [];
                function applicable(t) {
                    if (t[4] && !allowLargerForwardBuffer) return false;
                    for (var i = 0; i < pagePaths.length; i++) if (overlaps(pagePaths[i], t[0])) return false;
                    return readPath(cfg, t[0]) === t[1];
                }
                var patch = null;
                for (var i = 0; i < SHAKA_TUNING.length; i++) {
                    var t = SHAKA_TUNING[i];
                    var ok = applicable(t);
                    for (var j = 0; ok && t[3] && j < SHAKA_TUNING.length; j++) {
                        if (SHAKA_TUNING[j][3] === t[3] && !applicable(SHAKA_TUNING[j])) ok = false;
                    }
                    if (ok) writePath(patch = patch || {}, t[0], t[2]);
                }
                return patch;
            }

            function patchShakaPlayer(Player) {
                var proto = typeof Player === 'function' ? Player.prototype : null;
                if (!proto || shakaPrototypes.has(proto)) return;
                var configure0 = proto.configure;
                var load0 = proto.load;
                var preload0 = proto.preload;
                if (typeof configure0 !== 'function' || typeof load0 !== 'function' || typeof proto.getConfiguration !== 'function') return;
                shakaPrototypes.add(proto);

                function tune(player) {
                    if (!isObj(player) || shakaTuned.has(player)) return;
                    shakaTuned.add(player);
                    var patch = shakaPatch(player);
                    if (patch) configure0.call(player, patch);
                }

                proto.configure = keepLength(function configure(config) {
                    try {
                        var paths = shakaPagePaths.get(this);
                        if (!paths) shakaPagePaths.set(this, paths = []);
                        if (typeof config === 'string') paths.push(config);
                        else collectPaths(paths, config, '', 0);
                    } catch (_) {}
                    return configure0.apply(this, arguments);
                }, configure0);
                proto.load = keepLength(function load() {
                    try { tune(this); } catch (_) {}
                    return load0.apply(this, arguments);
                }, load0);
                if (typeof preload0 === 'function') {
                    proto.preload = keepLength(function preload() {
                        try { tune(this); } catch (_) {}
                        return preload0.apply(this, arguments);
                    }, preload0);
                }
            }

            watchGlobal('shaka', function(ns) {
                patchShakaPlayer(ns.Player);
            });

            // ---------------------------------------------------------------- video.js 8 / VHS 3
            // VHS 3 defaults useNetworkInformationApi to true and then takes its bandwidth estimate from
            // navigator.connection.downlink, which WebView pins at ~10 Mbps. Fall back to VHS's own throughput
            // measurement unless the page chose the option (videojs.options.vhs, html5.vhs or the source object).
            // Found through the player element, so bundled copies without a window.videojs global work too.
            var vhsHandlers = new WeakSet();

            function videojsDefaultOptions(player) {
                // videojs.options === Player.prototype.options_ in video.js 7 and 8.
                for (var p = getProto(player), i = 0; p && i < 8; p = getProto(p), i++) {
                    if (hasOwn.call(p, 'options_')) return p.options_;
                }
                return null;
            }

            function pageChoseVhsOption(handler, player, key) {
                var source = handler.source_;
                if (source && typeof source === 'object' && source[key] !== undefined) return true;
                var tech = handler.tech_;
                var techVhs = tech && tech.options_ && tech.options_.vhs;
                if (techVhs && techVhs[key] !== undefined) return true;
                var defaults = videojsDefaultOptions(player);
                if (!defaults) return true;
                return !!(defaults.vhs && defaults.vhs[key] !== undefined);
            }

            function tuneVhsHandler(handler, player) {
                if (!isObj(handler) || vhsHandlers.has(handler)) return;
                vhsHandlers.add(handler);
                var options = handler.options_;
                if (!options || typeof options !== 'object' || options.useNetworkInformationApi !== true) return;
                if (pageChoseVhsOption(handler, player, 'useNetworkInformationApi')) return;
                options.useNetworkInformationApi = false;
            }

            function inspectMedia(media) {
                if (!media || (media.localName !== 'video' && media.localName !== 'audio')) return;
                for (var node = media.parentNode, i = 0; node && i < 3; node = node.parentNode, i++) {
                    var player = node.player;
                    var tech = player && player.tech_;
                    if (tech && tech.vhs) {
                        tuneVhsHandler(tech.vhs, player);
                        return;
                    }
                }
            }

            function onMediaEvent(event) {
                try { inspectMedia(event.target); } catch (_) {}
            }

            try {
                // Media events do not bubble, but capturing listeners still see them for attached elements.
                document.addEventListener('loadstart', onMediaEvent, true);
                document.addEventListener('play', onMediaEvent, true);
                if (document.readyState !== 'loading') {
                    Array.prototype.forEach.call(document.querySelectorAll('video,audio'), function(media) {
                        try { inspectMedia(media); } catch (_) {}
                    });
                }
            } catch (_) {}
        })();
    """.trimIndent()
}
