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
import android.net.Uri

/**
 * Sites the user made an exception for (ads allowed, pop-ups allowed, ...), kept in preferences.
 * A site covers its subdomains; read on WebView's network threads, so lookups never block.
 */
class SiteExceptionList(
    private val read: (Context) -> Set<String>,
    private val write: (Context, Set<String>) -> Unit
) {
    @Volatile
    private var sites: Set<String> = emptySet()

    fun load(context: Context) {
        sites = read(context.applicationContext)
    }

    /** Whether the site of [pageUrl] (or a parent domain of it) is on the list. */
    fun contains(pageUrl: String?): Boolean {
        val current = sites
        if (current.isEmpty()) return false
        val host = siteOf(pageUrl) ?: return false
        return current.any { site -> host == site || host.endsWith(".$site") }
    }

    fun set(context: Context, site: String, included: Boolean) {
        // Read from preferences: settings can be opened before any WebView loaded the list.
        val current = read(context.applicationContext)
        val updated = if (included) current + site else current - site
        sites = updated
        write(context.applicationContext, updated)
    }

    companion object {
        /** The key used for site exceptions: the page's host without `www.`, or null for non-web pages. */
        fun siteOf(pageUrl: String?): String? {
            if (pageUrl.isNullOrEmpty()) return null
            val uri = Uri.parse(pageUrl)
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return null
            return uri.host?.lowercase()?.removePrefix("www.")?.takeIf { it.isNotEmpty() }
        }
    }
}
