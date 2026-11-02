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

/**
 * The part of [WebScripts.AD_BLOCK_COSMETIC_JS] that runs the filter lists' scriptlets and
 * procedural cosmetic filters, which [AdBlocker.CosmeticBridge.pageExtras] hands it for the page.
 *
 * Scriptlets are small, well known page patches from uBlock Origin (and their AdGuard names):
 * they stop ad and anti-adblock scripts by trapping the properties those scripts use, defusing
 * their timers and event listeners, or pruning ad data from JSON. They run at document start,
 * before the page's own scripts. Procedural filters hide (or remove) elements that CSS alone cannot
 * select, such as a post whose text says "Sponsored"; they are re-checked as the page changes.
 *
 * Only the scriptlets implemented here run; trusted-only and unknown ones are ignored. The script
 * must not contain a dollar sign followed by a name or brace, since it is a Kotlin string template.
 */
internal val AD_BLOCK_EXTRAS_JS = """            // Scriptlets and procedural cosmetic filters from the filter lists (uBlock Origin syntax).
            let extras = null;
            try {
                if (bridge && bridge.pageExtras) extras = JSON.parse(bridge.pageExtras(location.href) || 'null');
            } catch (_) {}
            if (extras) {
                const win = window;
                const nativeSetTimeout = win.setTimeout;
                const nativeSetInterval = win.setInterval;

                function toRegex(pattern, flags) {
                    if (pattern === undefined || pattern === '') return /^/;
                    const match = /^\/(.+)\/([gimsu]*)$/.exec(pattern);
                    if (match) {
                        try { return new RegExp(match[1], match[2] || flags || ''); } catch (_) { return /^\b\B/; }
                    }
                    return new RegExp(pattern.replace(/[.*+?^{}()|[\]\\\x24]/g, '\\$&'), flags || '');
                }
                // A pattern starting with `!` matches what the rest does not.
                function matcher(pattern) {
                    let negate = false;
                    if (typeof pattern === 'string' && pattern.charAt(0) === '!') {
                        negate = true;
                        pattern = pattern.slice(1);
                    }
                    const regex = toRegex(pattern);
                    return function(text) { return regex.test(String(text)) !== negate; };
                }
                function onReady(fn) {
                    if (document.readyState === 'loading') {
                        document.addEventListener('DOMContentLoaded', fn, { once: true });
                    } else {
                        fn();
                    }
                }
                function observe(fn) {
                    let scheduled = false;
                    onReady(function() {
                        fn();
                        new MutationObserver(function() {
                            if (scheduled) return;
                            scheduled = true;
                            nativeSetTimeout.call(win, function() { scheduled = false; fn(); }, 100);
                        }).observe(document.documentElement, { childList: true, subtree: true });
                    });
                }
                const INVALID = {};
                function constantValue(raw) {
                    switch (raw) {
                        case 'undefined': return undefined;
                        case 'false': return false;
                        case 'true': return true;
                        case 'null': return null;
                        case "''": case '': case 'emptyStr': return '';
                        case '[]': case 'emptyArr': return [];
                        case '{}': case 'emptyObj': return {};
                        case 'noopFunc': return function() {};
                        case 'trueFunc': return function() { return true; };
                        case 'falseFunc': return function() { return false; };
                        case 'throwFunc': return function() { throw new Error(); };
                        case 'noopPromiseResolve': return function() { return Promise.resolve(); };
                        case 'noopPromiseReject': return function() { return Promise.reject(); };
                        case 'yes': case 'no': return raw;
                        case '-1': return -1;
                    }
                    if (/^\d+$/.test(raw) && Number(raw) <= 32767) return Number(raw);
                    return INVALID;
                }
                // Calls onOwner(owner, property) for the last property of chain, waiting for objects
                // along the chain that do not exist yet.
                function trapChain(owner, chain, onOwner) {
                    const dot = chain.indexOf('.');
                    if (dot < 0) { onOwner(owner, chain); return; }
                    const prop = chain.slice(0, dot);
                    const rest = chain.slice(dot + 1);
                    let value;
                    try { value = owner[prop]; } catch (_) { return; }
                    if (value !== null && (typeof value === 'object' || typeof value === 'function')) {
                        trapChain(value, rest, onOwner);
                        return;
                    }
                    const descriptor = Object.getOwnPropertyDescriptor(owner, prop);
                    if (descriptor && descriptor.configurable === false) return;
                    const previousGet = descriptor && descriptor.get;
                    const previousSet = descriptor && descriptor.set;
                    try {
                        Object.defineProperty(owner, prop, {
                            configurable: true,
                            enumerable: true,
                            get: function() { return previousGet ? previousGet.call(this) : value; },
                            set: function(next) {
                                if (previousSet) previousSet.call(this, next); else value = next;
                                if (next !== null && (typeof next === 'object' || typeof next === 'function')) {
                                    trapChain(next, rest, onOwner);
                                }
                            }
                        });
                    } catch (_) {}
                }
                function setConstant(chain, raw) {
                    if (!chain) return;
                    const value = constantValue(raw);
                    if (value === INVALID) return;
                    trapChain(win, chain, function(owner, prop) {
                        try {
                            if (owner[prop] === value) return;
                        } catch (_) {}
                        try {
                            Object.defineProperty(owner, prop, {
                                configurable: true,
                                get: function() { return value; },
                                set: function() {}
                            });
                        } catch (_) {}
                    });
                }
                function abortOnAccess(chain, onRead, onWrite, shouldAbort) {
                    if (!chain) return;
                    trapChain(win, chain, function(owner, prop) {
                        const own = Object.getOwnPropertyDescriptor(owner, prop);
                        if (own && own.configurable === false) return;
                        // Inherited properties (document.write, ...) keep working for everyone else.
                        let descriptor = own;
                        for (let proto = Object.getPrototypeOf(owner); !descriptor && proto; proto = Object.getPrototypeOf(proto)) {
                            descriptor = Object.getOwnPropertyDescriptor(proto, prop);
                        }
                        let value = descriptor ? (descriptor.get ? undefined : descriptor.value) : undefined;
                        const previousGet = descriptor && descriptor.get;
                        const previousSet = descriptor && descriptor.set;
                        try {
                            Object.defineProperty(owner, prop, {
                                configurable: true,
                                get: function() {
                                    if (onRead && shouldAbort()) throw new ReferenceError(prop);
                                    return previousGet ? previousGet.call(this) : value;
                                },
                                set: function(next) {
                                    if (onWrite && shouldAbort()) throw new ReferenceError(prop);
                                    if (previousSet) previousSet.call(this, next); else value = next;
                                }
                            });
                        } catch (_) {}
                    });
                }
                function always() { return true; }
                function abortCurrentScript(chain, needle, context) {
                    if (!chain) return;
                    const matches = matcher(needle || '');
                    const contextMatches = matcher(context || '');
                    const thisScript = document.currentScript;
                    abortOnAccess(chain, true, true, function() {
                        const script = document.currentScript;
                        if (!(script instanceof HTMLScriptElement) || script === thisScript) return false;
                        const text = script.src ? script.src + '\n' + script.textContent : script.textContent;
                        return matches(text) && contextMatches(text);
                    });
                }
                function abortOnStackTrace(chain, needle) {
                    const matches = matcher(needle || '');
                    abortOnAccess(chain, true, true, function() {
                        return matches(new Error().stack || '');
                    });
                }
                function wrapTimer(name, needle, delay, onMatch) {
                    const native = win[name];
                    if (!native) return;
                    const matches = matcher(needle || '');
                    let delayNegated = false;
                    let delayValue = delay;
                    if (typeof delay === 'string' && delay.charAt(0) === '!') {
                        delayNegated = true;
                        delayValue = delay.slice(1);
                    }
                    const wanted = delayValue === undefined || delayValue === '' ? null : Number(delayValue);
                    const patched = function(callback, ms) {
                        const delayMatches = wanted === null || ((Number(ms) || 0) === wanted) !== delayNegated;
                        if (delayMatches && matches(String(callback))) return onMatch(native, this, arguments);
                        return native.apply(this, arguments);
                    };
                    try { patched.toString = function() { return native.toString(); }; } catch (_) {}
                    win[name] = patched;
                }
                function preventTimer(name, needle, delay) {
                    wrapTimer(name, needle, delay, function(native, self) {
                        return native.call(self, function() {}, 1);
                    });
                }
                function boostTimer(name, needle, delay, boost) {
                    const factor = Math.min(Math.max(Number(boost) || 0.05, 0.001), 50);
                    wrapTimer(name, needle, delay === undefined || delay === '' ? '1000' : delay, function(native, self, args) {
                        const copy = Array.prototype.slice.call(args);
                        copy[1] = (Number(copy[1]) || 0) * factor;
                        return native.apply(self, copy);
                    });
                }
                function preventAddEventListener(type, needle) {
                    const typeMatches = matcher(type || '');
                    const handlerMatches = matcher(needle || '');
                    const proto = win.EventTarget && win.EventTarget.prototype;
                    if (!proto) return;
                    const native = proto.addEventListener;
                    proto.addEventListener = function(eventType, handler) {
                        let text = '';
                        try { text = String(typeof handler === 'function' ? handler : (handler && handler.handleEvent) || handler); } catch (_) {}
                        if (typeMatches(eventType) && handlerMatches(text)) return;
                        return native.apply(this, arguments);
                    };
                }
                function preventWindowOpen(pattern) {
                    const matches = matcher(pattern || '');
                    const native = win.open;
                    if (!native) return;
                    win.open = function(url) {
                        if (matches(url === undefined ? '' : String(url))) return null;
                        return native.apply(this, arguments);
                    };
                }
                function noWebRtc() {
                    const names = ['RTCPeerConnection', 'webkitRTCPeerConnection'];
                    for (let i = 0; i < names.length; i++) {
                        if (!win[names[i]]) continue;
                        const Fake = function() {
                            return {
                                close: function() {},
                                createDataChannel: function() { return {}; },
                                createOffer: function() { return Promise.resolve({}); },
                                setLocalDescription: function() { return Promise.resolve(); },
                                setRemoteDescription: function() { return Promise.resolve(); },
                                addEventListener: function() {},
                                removeEventListener: function() {}
                            };
                        };
                        try { win[names[i]] = Fake; } catch (_) {}
                    }
                }
                function removeAttributes(attributes, selector) {
                    if (!attributes) return;
                    const names = attributes.split(/\s*\|\s*/).filter(Boolean);
                    const target = selector || names.map(function(name) { return '[' + name + ']'; }).join(',');
                    observe(function() {
                        let elements;
                        try { elements = document.querySelectorAll(target); } catch (_) { return; }
                        for (let i = 0; i < elements.length; i++) {
                            for (let k = 0; k < names.length; k++) elements[i].removeAttribute(names[k]);
                        }
                    });
                }
                function removeClasses(classes, selector) {
                    if (!classes) return;
                    const names = classes.split(/\s*\|\s*/).filter(Boolean);
                    const target = selector || names.map(function(name) { return '.' + name; }).join(',');
                    observe(function() {
                        let elements;
                        try { elements = document.querySelectorAll(target); } catch (_) { return; }
                        for (let i = 0; i < elements.length; i++) {
                            for (let k = 0; k < names.length; k++) elements[i].classList.remove(names[k]);
                        }
                    });
                }
                function removeNodeText(nodeName, needle) {
                    if (!nodeName) return;
                    const nameMatches = matcher(nodeName);
                    const textMatches = matcher(needle || '');
                    function check(node) {
                        if (!nameMatches(node.nodeName.toLowerCase())) return;
                        if (textMatches(node.textContent)) {
                            if (node.nodeType === 1) node.textContent = ''; else node.remove();
                        }
                    }
                    new MutationObserver(function(mutations) {
                        for (let i = 0; i < mutations.length; i++) {
                            const added = mutations[i].addedNodes;
                            for (let j = 0; j < added.length; j++) check(added[j]);
                        }
                    }).observe(document, { childList: true, subtree: true });
                }
                // `url:pattern method:POST` style conditions; a bare pattern is matched against the URL.
                function requestMatcher(conditions) {
                    if (conditions === undefined) return function() { return false; };
                    const tests = [];
                    const parts = String(conditions).split(/\s+/).filter(Boolean);
                    for (let i = 0; i < parts.length; i++) {
                        const colon = parts[i].indexOf(':');
                        const isPair = colon > 0 && /^[a-zA-Z]+:/.test(parts[i]) && !/^https?:/.test(parts[i]);
                        tests.push({
                            key: isPair ? parts[i].slice(0, colon) : 'url',
                            matches: matcher(isPair ? parts[i].slice(colon + 1) : parts[i])
                        });
                    }
                    return function(details) {
                        for (let i = 0; i < tests.length; i++) {
                            const value = details[tests[i].key];
                            if (value === undefined || !tests[i].matches(String(value))) return false;
                        }
                        return true;
                    };
                }
                function preventFetch(conditions) {
                    const matches = requestMatcher(conditions);
                    const native = win.fetch;
                    if (!native) return;
                    win.fetch = function(input, init) {
                        const details = { url: '', method: 'GET' };
                        try {
                            details.url = typeof input === 'string' ? input : (input && input.url) || String(input);
                            if (input && input.method) details.method = input.method;
                            if (init) for (const key in init) details[key] = init[key];
                        } catch (_) {}
                        if (matches(details)) return Promise.resolve(new Response('', { status: 200, statusText: 'OK' }));
                        return native.apply(this, arguments);
                    };
                }
                function preventXhr(conditions) {
                    const matches = requestMatcher(conditions);
                    const proto = win.XMLHttpRequest && win.XMLHttpRequest.prototype;
                    if (!proto) return;
                    const nativeOpen = proto.open;
                    const nativeSend = proto.send;
                    const blocked = new WeakSet();
                    proto.open = function(method, url) {
                        if (matches({ method: String(method), url: String(url) })) blocked.add(this); else blocked.delete(this);
                        return nativeOpen.apply(this, arguments);
                    };
                    proto.send = function() {
                        if (!blocked.has(this)) return nativeSend.apply(this, arguments);
                        const xhr = this;
                        const values = { readyState: 4, status: 200, statusText: 'OK', responseText: '', response: '' };
                        for (const key in values) {
                            try { Object.defineProperty(xhr, key, { configurable: true, value: values[key] }); } catch (_) {}
                        }
                        nativeSetTimeout.call(win, function() {
                            const names = ['readystatechange', 'load', 'loadend'];
                            for (let i = 0; i < names.length; i++) {
                                try { xhr.dispatchEvent(new Event(names[i])); } catch (_) {}
                            }
                        }, 1);
                    };
                }
                function preventEval(needle) {
                    const matches = matcher(needle || '');
                    const native = win.eval;
                    win.eval = function(code) {
                        if (matches(String(code))) return undefined;
                        return native.apply(this, arguments);
                    };
                }
                // Removes properties (`a.b`, `a.[].b`, `a.*.b`) from parsed JSON.
                function pruneJson(rawPrune, rawNeeded) {
                    if (rawPrune === undefined) return;
                    const prunePaths = String(rawPrune).split(/\s+/).filter(Boolean);
                    const neededPaths = rawNeeded ? String(rawNeeded).split(/\s+/).filter(Boolean) : [];
                    function walk(object, path, onLast) {
                        if (!object || typeof object !== 'object') return false;
                        const dot = path.indexOf('.');
                        const key = dot < 0 ? path : path.slice(0, dot);
                        const rest = dot < 0 ? '' : path.slice(dot + 1);
                        if (key === '[]' || key === '*') {
                            let found = false;
                            for (const k in object) {
                                if (rest === '' ? onLast(object, k) : walk(object[k], rest, onLast)) found = true;
                            }
                            return found;
                        }
                        if (!(key in object)) return false;
                        return rest === '' ? onLast(object, key) : walk(object[key], rest, onLast);
                    }
                    const native = JSON.parse;
                    JSON.parse = function() {
                        const result = native.apply(this, arguments);
                        try {
                            if (prunePaths.length === 0) return result;
                            for (let i = 0; i < neededPaths.length; i++) {
                                if (!walk(result, neededPaths[i], function() { return true; })) return result;
                            }
                            for (let i = 0; i < prunePaths.length; i++) {
                                walk(result, prunePaths[i], function(owner, key) { delete owner[key]; return true; });
                            }
                        } catch (_) {}
                        return result;
                    };
                }
                function defuseAdBlockDetectors() {
                    const Detector = function() {};
                    Detector.prototype = {
                        check: function() { return this; },
                        clearEvent: function() { return this; },
                        emitEvent: function() { return this; },
                        on: function(detected, fn) { if (!detected && typeof fn === 'function') fn(); return this; },
                        onDetected: function() { return this; },
                        onNotDetected: function(fn) { if (typeof fn === 'function') fn(); return this; },
                        setOption: function() { return this; }
                    };
                    const names = ['FuckAdBlock', 'BlockAdBlock', 'SniffAdBlock'];
                    for (let i = 0; i < names.length; i++) {
                        try {
                            Object.defineProperty(win, names[i], { configurable: true, value: Detector });
                            const instance = names[i].charAt(0).toLowerCase() + names[i].slice(1);
                            Object.defineProperty(win, instance, { configurable: true, value: new Detector() });
                        } catch (_) {}
                    }
                }
                function defuseBlockAdBlock() {
                    preventEval('/blockadblock|babasbm/i');
                    wrapTimer('setTimeout', '/blockadblock|babasbm/i', undefined, function(native, self) {
                        return native.call(self, function() {}, 1);
                    });
                }
                function setCookie(name, value) {
                    if (!name || !/^(true|false|yes|no|y|n|ok|on|off|accept|accepted|reject|rejected|allow|deny|-?\d+|)$/i.test(value || '')) return;
                    try { document.cookie = encodeURIComponent(name) + '=' + (value || '') + '; path=/; max-age=31536000'; } catch (_) {}
                }
                function setLocalStorageItem(key, raw) {
                    if (!key) return;
                    try {
                        if (raw === '\u0024remove\u0024') { win.localStorage.removeItem(key); return; }
                        const value = constantValue(raw);
                        if (value === INVALID || typeof value === 'function') return;
                        win.localStorage.setItem(key, typeof value === 'object' && value !== null ? JSON.stringify(value) : String(value));
                    } catch (_) {}
                }
                function setAttribute(selector, name, value) {
                    if (!selector || !name || /^on/i.test(name)) return;
                    if (!/^(true|false|-?\d+|)$/.test(value || '')) return;
                    observe(function() {
                        let elements;
                        try { elements = document.querySelectorAll(selector); } catch (_) { return; }
                        for (let i = 0; i < elements.length; i++) elements[i].setAttribute(name, value || '');
                    });
                }
                function removeCookies(pattern) {
                    const matches = matcher(pattern || '');
                    function clear() {
                        const cookies = document.cookie.split(';');
                        for (let i = 0; i < cookies.length; i++) {
                            const name = cookies[i].split('=')[0].trim();
                            if (name && matches(name)) {
                                document.cookie = name + '=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
                                document.cookie = name + '=; expires=Thu, 01 Jan 1970 00:00:00 GMT';
                            }
                        }
                    }
                    clear();
                    win.addEventListener('beforeunload', clear);
                }

                const SCRIPTLETS = {
                    'set-constant': setConstant, 'set': setConstant,
                    'abort-on-property-read': function(chain) { abortOnAccess(chain, true, false, always); },
                    'aopr': function(chain) { abortOnAccess(chain, true, false, always); },
                    'abort-on-property-write': function(chain) { abortOnAccess(chain, false, true, always); },
                    'aopw': function(chain) { abortOnAccess(chain, false, true, always); },
                    'abort-current-script': abortCurrentScript, 'acs': abortCurrentScript,
                    'abort-current-inline-script': abortCurrentScript, 'acis': abortCurrentScript,
                    'abort-on-stack-trace': abortOnStackTrace, 'aost': abortOnStackTrace,
                    'addEventListener-defuser': preventAddEventListener, 'aeld': preventAddEventListener,
                    'prevent-addEventListener': preventAddEventListener,
                    'no-setTimeout-if': function(n, d) { preventTimer('setTimeout', n, d); },
                    'nostif': function(n, d) { preventTimer('setTimeout', n, d); },
                    'prevent-setTimeout': function(n, d) { preventTimer('setTimeout', n, d); },
                    'no-setInterval-if': function(n, d) { preventTimer('setInterval', n, d); },
                    'nosiif': function(n, d) { preventTimer('setInterval', n, d); },
                    'prevent-setInterval': function(n, d) { preventTimer('setInterval', n, d); },
                    'nano-setInterval-booster': function(n, d, b) { boostTimer('setInterval', n, d, b); },
                    'nano-sib': function(n, d, b) { boostTimer('setInterval', n, d, b); },
                    'adjust-setInterval': function(n, d, b) { boostTimer('setInterval', n, d, b); },
                    'nano-setTimeout-booster': function(n, d, b) { boostTimer('setTimeout', n, d, b); },
                    'nano-stb': function(n, d, b) { boostTimer('setTimeout', n, d, b); },
                    'adjust-setTimeout': function(n, d, b) { boostTimer('setTimeout', n, d, b); },
                    'no-window-open-if': preventWindowOpen, 'nowoif': preventWindowOpen,
                    'window.open-defuser': preventWindowOpen, 'prevent-window-open': preventWindowOpen,
                    'nowebrtc': noWebRtc,
                    'remove-attr': removeAttributes, 'ra': removeAttributes,
                    'remove-class': removeClasses, 'rc': removeClasses,
                    'remove-node-text': removeNodeText, 'rmnt': removeNodeText,
                    'no-fetch-if': preventFetch, 'prevent-fetch': preventFetch,
                    'no-xhr-if': preventXhr, 'prevent-xhr': preventXhr,
                    'noeval': function() { preventEval(''); },
                    'silent-noeval': function() { preventEval(''); },
                    'noeval-if': preventEval, 'prevent-eval-if': preventEval,
                    'json-prune': pruneJson,
                    'nobab': defuseBlockAdBlock, 'bab-defuser': defuseBlockAdBlock,
                    'nofab': defuseAdBlockDetectors, 'fuckadblock.js-3.2.0': defuseAdBlockDetectors,
                    'prevent-fab-3.2.0': defuseAdBlockDetectors,
                    'popads-dummy': function() {
                        try {
                            delete win.PopAds;
                            delete win.popns;
                            Object.defineProperties(win, { PopAds: { value: {} }, popns: { value: {} } });
                        } catch (_) {}
                    },
                    'popads.net': function() {
                        abortOnAccess('PopAds', true, true, always);
                        abortOnAccess('popns', true, true, always);
                    },
                    'prevent-popads-net': function() {
                        abortOnAccess('PopAds', true, true, always);
                        abortOnAccess('popns', true, true, always);
                    },
                    'set-cookie': setCookie,
                    'set-local-storage-item': setLocalStorageItem,
                    'set-attr': setAttribute,
                    'cookie-remover': removeCookies, 'remove-cookie': removeCookies,
                    'refresh-defuser': function() {
                        onReady(function() {
                            const meta = document.querySelector('meta[http-equiv="refresh" i]');
                            if (meta) meta.remove();
                        });
                    },
                    'window.name-defuser': function() {
                        try { if (win === win.top) win.name = ''; } catch (_) {}
                    }
                };
                const scriptlets = extras.scriptlets || [];
                for (let i = 0; i < scriptlets.length; i++) {
                    const fn = SCRIPTLETS[scriptlets[i][0]];
                    if (!fn) continue;
                    try { fn.apply(null, scriptlets[i].slice(1)); } catch (_) {}
                }

                // Procedural cosmetic filters: `:has-text()`, `:upward()`, `:remove()`, ...
                const OPERATORS = ['has-text', 'has', 'not', 'upward', 'remove', 'remove-attr', 'remove-class',
                    'xpath', 'matches-css', 'matches-css-before', 'matches-css-after', 'matches-attr',
                    'matches-path', 'min-text-length', 'watch-attr', 'matches-media', 'style'];
                const ACTIONS = { 'remove': 1, 'remove-attr': 1, 'remove-class': 1, 'style': 1 };
                function readArgument(text, start) {
                    let depth = 1;
                    let quote = '';
                    for (let i = start; i < text.length; i++) {
                        const c = text.charAt(i);
                        if (c === '\\') { i++; continue; }
                        if (quote) { if (c === quote) quote = ''; continue; }
                        if (c === '"' || c === "'") { quote = c; continue; }
                        if (c === '(') depth++;
                        else if (c === ')' && --depth === 0) return i;
                    }
                    return -1;
                }
                function isProcedural(text) {
                    for (let i = 0; i < OPERATORS.length; i++) {
                        if (OPERATORS[i] !== 'has' && OPERATORS[i] !== 'not' && text.indexOf(':' + OPERATORS[i] + '(') >= 0) return true;
                    }
                    return false;
                }
                // Returns { selector, tasks, action } or null when the selector cannot be used.
                function compile(text) {
                    const tasks = [];
                    let action = null;
                    let css = '';
                    let first = null;
                    let i = 0;
                    let bracket = 0;
                    let quote = '';
                    function flushCss() {
                        const trimmed = css.trim();
                        if (trimmed) {
                            if (first === null && tasks.length === 0) first = trimmed;
                            else tasks.push({ type: 'spath', value: trimmed });
                        }
                        css = '';
                    }
                    while (i < text.length) {
                        const c = text.charAt(i);
                        if (quote) { css += c; if (c === quote) quote = ''; i++; continue; }
                        if (c === '\\') { css += text.substr(i, 2); i += 2; continue; }
                        if (c === '"' || c === "'") { quote = c; css += c; i++; continue; }
                        if (c === '[') bracket++;
                        if (c === ']') bracket--;
                        if (c === ':' && bracket === 0) {
                            const match = /^:([a-z-]+)\(/.exec(text.slice(i));
                            if (match && OPERATORS.indexOf(match[1]) >= 0) {
                                const argStart = i + match[0].length;
                                const argEnd = readArgument(text, argStart);
                                if (argEnd < 0) return null;
                                const arg = text.slice(argStart, argEnd);
                                const name = match[1];
                                if ((name === 'has' || name === 'not') && !isProcedural(arg)) {
                                    css += text.slice(i, argEnd + 1);
                                } else {
                                    if (action) return null;
                                    flushCss();
                                    if (ACTIONS[name]) {
                                        action = { type: name, value: arg };
                                    } else if (name === 'has' || name === 'not') {
                                        const inner = compile(arg.trim());
                                        if (!inner) return null;
                                        tasks.push({ type: name, value: inner });
                                    } else {
                                        tasks.push({ type: name, value: arg });
                                    }
                                }
                                i = argEnd + 1;
                                continue;
                            }
                        }
                        css += c;
                        i++;
                    }
                    flushCss();
                    if (action && first === null && tasks.length === 0 && css === '') return null;
                    return { selector: first, tasks: tasks, action: action };
                }
                function textRegex(arg) {
                    const value = arg.trim().replace(/^(["'])(.*)\1$/, '$2');
                    return toRegex(value);
                }
                function query(root, selector) {
                    if (!selector) return [root];
                    try {
                        if (root === document) return Array.prototype.slice.call(document.querySelectorAll(selector));
                        const relative = /^[>+~]/.test(selector) ? ':scope ' + selector : selector;
                        return Array.prototype.slice.call(root.querySelectorAll(relative));
                    } catch (_) {
                        return [];
                    }
                }
                function run(compiled, root) {
                    let elements = compiled.selector ? query(root, compiled.selector) : (root === document ? [document.documentElement] : [root]);
                    for (let t = 0; t < compiled.tasks.length && elements.length; t++) {
                        const task = compiled.tasks[t];
                        const next = [];
                        const value = task.value;
                        switch (task.type) {
                            case 'has-text': {
                                const regex = textRegex(value);
                                for (let k = 0; k < elements.length; k++) if (regex.test(elements[k].textContent)) next.push(elements[k]);
                                break;
                            }
                            case 'has':
                            case 'not':
                                for (let k = 0; k < elements.length; k++) {
                                    const found = run(value, elements[k]).length > 0;
                                    if (found === (task.type === 'has')) next.push(elements[k]);
                                }
                                break;
                            case 'upward': {
                                const steps = /^\s*\d+\s*$/.test(value) ? Number(value) : 0;
                                for (let k = 0; k < elements.length; k++) {
                                    let element = elements[k];
                                    if (steps > 0) {
                                        for (let s = 0; s < steps && element; s++) element = element.parentElement;
                                    } else {
                                        try { element = element.parentElement && element.parentElement.closest(value); } catch (_) { element = null; }
                                    }
                                    if (element && next.indexOf(element) < 0) next.push(element);
                                }
                                break;
                            }
                            case 'xpath':
                                for (let k = 0; k < elements.length; k++) {
                                    try {
                                        const result = document.evaluate(value, elements[k], null, 7, null);
                                        for (let s = 0; s < result.snapshotLength; s++) {
                                            const node = result.snapshotItem(s);
                                            if (node.nodeType === 1) next.push(node);
                                        }
                                    } catch (_) {}
                                }
                                break;
                            case 'matches-css':
                            case 'matches-css-before':
                            case 'matches-css-after': {
                                const colon = value.indexOf(':');
                                if (colon < 0) break;
                                const property = value.slice(0, colon).trim();
                                const regex = textRegex(value.slice(colon + 1));
                                const pseudo = task.type === 'matches-css' ? null : '::' + task.type.slice(12);
                                for (let k = 0; k < elements.length; k++) {
                                    if (regex.test(getComputedStyle(elements[k], pseudo).getPropertyValue(property))) next.push(elements[k]);
                                }
                                break;
                            }
                            case 'matches-attr': {
                                const parts = value.split('=');
                                const nameRegex = textRegex(parts[0]);
                                const valueRegex = parts.length > 1 ? textRegex(parts.slice(1).join('=')) : /^/;
                                for (let k = 0; k < elements.length; k++) {
                                    const attributes = elements[k].attributes;
                                    for (let a = 0; a < attributes.length; a++) {
                                        if (nameRegex.test(attributes[a].name) && valueRegex.test(attributes[a].value)) { next.push(elements[k]); break; }
                                    }
                                }
                                break;
                            }
                            case 'matches-path':
                                if (textRegex(value).test(location.pathname + location.search)) next.push.apply(next, elements);
                                break;
                            case 'matches-media':
                                try { if (win.matchMedia(value).matches) next.push.apply(next, elements); } catch (_) {}
                                break;
                            case 'min-text-length': {
                                const min = Number(value) || 0;
                                for (let k = 0; k < elements.length; k++) if (elements[k].textContent.length >= min) next.push(elements[k]);
                                break;
                            }
                            case 'watch-attr':
                                next.push.apply(next, elements);
                                break;
                            case 'spath':
                                for (let k = 0; k < elements.length; k++) next.push.apply(next, query(elements[k], value));
                                break;
                        }
                        elements = next;
                    }
                    return elements;
                }
                const procedural = [];
                const selectors = extras.procedural || [];
                for (let i = 0; i < selectors.length; i++) {
                    const compiled = compile(selectors[i]);
                    if (compiled) procedural.push(compiled);
                }
                if (procedural.length) {
                    observe(function() {
                        for (let p = 0; p < procedural.length; p++) {
                            const compiled = procedural[p];
                            let elements;
                            try { elements = run(compiled, document); } catch (_) { continue; }
                            const action = compiled.action;
                            for (let k = 0; k < elements.length; k++) {
                                const element = elements[k];
                                if (element === document.documentElement || element === document.body) continue;
                                if (!action) {
                                    element.style.setProperty('display', 'none', 'important');
                                } else if (action.type === 'remove') {
                                    element.remove();
                                } else if (action.type === 'remove-attr') {
                                    element.removeAttribute(action.value.trim());
                                } else if (action.type === 'remove-class') {
                                    element.classList.remove(action.value.trim());
                                } else if (action.type === 'style' && !element.hasAttribute('data-aab-style') &&
                                    !/url\(|expression/i.test(action.value)) {
                                    element.setAttribute('data-aab-style', '');
                                    element.setAttribute('style', (element.getAttribute('style') || '') + ';' + action.value);
                                }
                            }
                        }
                    });
                }
            }
"""
