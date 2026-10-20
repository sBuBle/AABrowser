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

import android.content.Context

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.kododake.aabrowser.adblock.FilterEngine
import com.kododake.aabrowser.adblock.FilterListManager
import com.kododake.aabrowser.data.BrowserPreferences
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executors

/**
 * WebView-facing side of the ad blocker.
 *
 * Starts with the bundled `adblock_builtin.txt` rules, then loads EasyList, EasyPrivacy, Peter
 * Lowe's list and HaGeZi Light (downloaded and refreshed by [FilterListManager]) on a background
 * thread and swaps the new [FilterEngine] in once it is built. Request checks run on WebView's
 * network threads, so they only read the current engine and never wait for it.
 */
object AdBlocker {
    private const val BUILTIN_RULES_ASSET = "adblock_builtin.txt"
    private const val TAG = "AdBlocker"
    private const val MAX_COSMETIC_TOKENS_CHARS = 200_000

    @Volatile
    private var engine: FilterEngine = FilterEngine.EMPTY

    @Volatile
    var isEnabled: Boolean = true
        private set

    /** Sites the user turned the ad blocker off for. */
    private val allowedSites = SiteExceptionList(
        BrowserPreferences::getAdBlockAllowedSites,
        BrowserPreferences::setAdBlockAllowedSites
    )

    @Volatile
    private var initialized = false

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AdBlockFilters").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    val ruleCount: Int get() = engine.ruleCount

    fun initialize(context: Context) {
        if (initialized) return
        val appContext = context.applicationContext
        synchronized(this) {
            if (initialized) return
            isEnabled = BrowserPreferences.isAdBlockerEnabled(appContext)
            allowedSites.load(appContext)
            RedirectBlocker.initialize(appContext)
            initialized = true
        }
        worker.execute {
            // The small built-in list is ready in milliseconds; the full lists take a moment longer.
            rebuildEngine(appContext, includeDownloadedLists = false)
            rebuildEngine(appContext, includeDownloadedLists = true)
            if (FilterListManager.needsUpdate(appContext) && FilterListManager.downloadAll(appContext)) {
                rebuildEngine(appContext, includeDownloadedLists = true)
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
    }

    /** The key used for per-site exceptions, see [SiteExceptionList.siteOf]. */
    fun siteOf(pageUrl: String?): String? = SiteExceptionList.siteOf(pageUrl)

    /** Whether the user turned the ad blocker off for the site of [pageUrl] (or a parent domain of it). */
    fun isAllowedSite(pageUrl: String?): Boolean = allowedSites.contains(pageUrl)

    /** Whether ads are blocked on [pageUrl]: the ad blocker is on and the site is not an exception. */
    fun isActiveFor(pageUrl: String?): Boolean = isEnabled && !isAllowedSite(pageUrl)

    fun setSiteAllowed(context: Context, site: String, allowed: Boolean) =
        allowedSites.set(context, site, allowed)

    /** Downloads fresh filter lists now; [onComplete] runs on the main thread. */
    fun updateFilterLists(context: Context, onComplete: (updated: Boolean) -> Unit) {
        val appContext = context.applicationContext
        worker.execute {
            val updated = FilterListManager.downloadAll(appContext)
            if (updated) rebuildEngine(appContext, includeDownloadedLists = true)
            mainHandler.post { onComplete(updated) }
        }
    }

    fun shouldBlock(request: WebResourceRequest, pageUrl: String?): Boolean {
        if (!isEnabled || request.isForMainFrame) return false
        val url = request.url ?: return false
        val scheme = url.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        if (isAllowedSite(pageUrl)) return false
        return engine.shouldBlockRequest(url.toString(), pageUrl)
    }

    /**
     * Whether a top-level navigation should be stopped: a pop-up window opening an ad, or a page
     * redirecting itself to an ad without the user asking for it.
     */
    fun shouldBlockNavigation(url: String, openerUrl: String?, isPopup: Boolean): Boolean {
        if (!isActiveFor(openerUrl)) return false
        return engine.shouldBlockNavigation(url, openerUrl, isPopup)
    }

    /**
     * A response that makes WebView fail the request with a network error, like a real ad blocker.
     * An empty `200 OK` (or a null body, which WebView turns into a `404`) still counts as a
     * successful load, so `fetch()` resolves and scripts fire `onload`, which lets ad scripts and
     * ad block tests see the request as "not blocked". WebView fails the request when the stream
     * throws before any headers are sent.
     *
     * Blocked ad frames get an empty page instead, since a failed frame shows an error page in the
     * ad slot. Frames ask for HTML; `fetch()`, scripts and images do not.
     */
    fun blockedResponse(request: WebResourceRequest): WebResourceResponse {
        val accept = request.requestHeaders.entries.firstOrNull { it.key.equals("Accept", ignoreCase = true) }?.value
        if (accept?.startsWith("text/html") == true) {
            return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(ByteArray(0)))
        }
        return WebResourceResponse("text/plain", "utf-8", FailingInputStream)
    }

    private object FailingInputStream : InputStream() {
        private val blocked = IOException("Blocked by AABrowser ad blocker")

        override fun available(): Int = throw blocked

        override fun read(): Int = throw blocked

        override fun read(b: ByteArray, off: Int, len: Int): Int = throw blocked
    }

    /** Exposed to pages as `AABAdBlock` for the element hiding part of [WebScripts.AD_BLOCK_COSMETIC_JS]. */
    class CosmeticBridge {
        /** Whether ads are blocked on [pageUrl]: the blocker is on and the site is not allowed. */
        @JavascriptInterface
        fun isActive(pageUrl: String?): Boolean = isActiveFor(pageUrl)

        @JavascriptInterface
        fun pageCss(pageUrl: String?, isTopFrame: Boolean): String {
            if (!isEnabled || pageUrl.isNullOrEmpty() || isAllowedSite(pageUrl)) return ""
            return runCatching { engine.pageCss(pageUrl, includeGenericComplex = isTopFrame) }.getOrDefault("")
        }

        @JavascriptInterface
        fun genericCss(pageUrl: String?, tokens: String?): String {
            if (!isEnabled || pageUrl.isNullOrEmpty() || tokens.isNullOrEmpty() || isAllowedSite(pageUrl)) return ""
            if (tokens.length > MAX_COSMETIC_TOKENS_CHARS) return ""
            return runCatching { engine.genericCss(pageUrl, tokens.split(' ')) }.getOrDefault("")
        }
    }

    private fun rebuildEngine(context: Context, includeDownloadedLists: Boolean) {
        val builder = FilterEngine.Builder()
        runCatching {
            context.assets.open(BUILTIN_RULES_ASSET).bufferedReader().useLines { builder.addList(it) }
        }.onFailure { e -> Log.w(TAG, "Failed to load built-in rules: ${e.message}") }
        val lists = if (includeDownloadedLists) FilterListManager.listFiles(context) else emptyList()
        if (includeDownloadedLists && lists.isEmpty()) return
        lists.forEach { file ->
            runCatching {
                file.bufferedReader().useLines { builder.addList(it) }
            }.onFailure { e -> Log.w(TAG, "Failed to load ${file.name}: ${e.message}") }
        }
        engine = builder.build()
        Log.i(TAG, "Ad block engine ready with ${engine.ruleCount} rules")
    }
}
