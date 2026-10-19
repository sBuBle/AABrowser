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
     * Cosmetic part of the ad blocker: hides ad containers left empty by blocked requests and
     * skips YouTube video ads, which are served from the same hosts as the videos themselves.
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

            function injectStyle() {
                const root = document.head || document.documentElement;
                if (!root) return false;
                const style = document.createElement('style');
                style.setAttribute('data-aab', 'adblock');
                style.textContent = css;
                root.appendChild(style);
                return true;
            }
            if (!injectStyle()) {
                document.addEventListener('DOMContentLoaded', injectStyle, { once: true });
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
                const resumeAt = isFinite(media.duration) ? state.position : 0;
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
                }, STALL_TIMEOUT_MS);
            }

            function watch(media) {
                if (!(media instanceof HTMLMediaElement) || states.has(media)) return;
                const state = { attempts: 0, retryTimer: 0, stallTimer: 0, position: 0, wantsPlayback: !media.paused, recovering: false };
                states.set(media, state);

                media.addEventListener('play', function() { state.wantsPlayback = true; });
                media.addEventListener('playing', function() {
                    state.wantsPlayback = true;
                    state.recovering = false;
                    state.attempts = 0;
                    clearStall(state);
                    pending.delete(media);
                });
                media.addEventListener('pause', function() {
                    if (state.recovering || media.error) return;
                    state.wantsPlayback = false;
                    clearStall(state);
                });
                media.addEventListener('ended', function() {
                    state.wantsPlayback = false;
                    clearStall(state);
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
}
