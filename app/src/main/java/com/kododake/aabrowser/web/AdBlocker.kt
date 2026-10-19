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
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.kododake.aabrowser.data.BrowserPreferences
import java.io.ByteArrayInputStream

/**
 * Network-level ad blocker backed by the bundled `adblock_hosts.txt` rule list.
 *
 * [shouldBlock] is called from WebView's network thread for every sub-resource request, so the
 * enabled flag is cached in memory and the rule set is loaded once and then only read.
 */
object AdBlocker {
    private const val RULES_ASSET = "adblock_hosts.txt"
    private const val TAG = "AdBlocker"

    @Volatile
    private var rules: AdBlockRules? = null

    @Volatile
    var isEnabled: Boolean = true
        private set

    @Volatile
    private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        val appContext = context.applicationContext
        synchronized(this) {
            if (initialized) return
            isEnabled = BrowserPreferences.isAdBlockerEnabled(appContext)
            initialized = true
        }
        Thread({ ensureRulesLoaded(appContext) }, "AdBlockRulesLoader").apply { isDaemon = true }.start()
    }

    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
    }

    fun shouldBlock(context: Context, request: WebResourceRequest): Boolean {
        if (!isEnabled || request.isForMainFrame) return false
        val url = request.url ?: return false
        val scheme = url.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        return ensureRulesLoaded(context).shouldBlock(url.host, url.encodedPath)
    }

    fun blockedResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    private fun ensureRulesLoaded(context: Context): AdBlockRules {
        rules?.let { return it }
        synchronized(this) {
            rules?.let { return it }
            val loaded = runCatching {
                context.applicationContext.assets.open(RULES_ASSET).bufferedReader().useLines { lines ->
                    AdBlockRules.parse(lines)
                }
            }.onFailure { e ->
                Log.w(TAG, "Failed to load ad block rules: ${e.message}")
            }.getOrDefault(AdBlockRules(emptySet()))
            rules = loaded
            return loaded
        }
    }
}
