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

import android.net.Uri
import android.webkit.WebView
import com.kododake.aabrowser.R
import com.kododake.aabrowser.data.BrowserPreferences

object CleartextNavigationHandler {
    fun handleCleartextIfNeeded(
        view: WebView,
        uri: Uri?,
        callbacks: BrowserCallbacks,
        onPageStart: Boolean = false
    ): Boolean {
        if (uri == null) return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http") return false

        val host = uri.host?.lowercase()
        if (host != null && host == view.getTag(R.id.webview_allow_once_host_tag)) return false
        if (BrowserPreferences.isHostAllowedCleartext(view.context, host)) {
            return false
        }
        if (onPageStart) view.stopLoading()
        val allowOnce = {
            allowOnce(view, uri)
            view.post { view.loadUrl(uri.toString()) }
            Unit
        }
        val allowHost = {
            view.context?.let { ctx ->
                if (host != null) BrowserPreferences.addAllowedCleartextHost(ctx, host)
            }
            view.post { view.loadUrl(uri.toString()) }
            Unit
        }
        val cancel = {
            if (onPageStart) view.stopLoading()
            Unit
        }
        callbacks.onCleartextNavigationRequested(uri, allowOnce, allowHost, cancel)
        return true
    }

    /**
     * Lets this tab use plain HTTP on [uri]'s site until it moves on to another site. Keyed by host,
     * not the exact address: WebView normalizes what it loads (`http://a.com` becomes
     * `http://a.com/`) and pages link around within the site, neither of which should ask again.
     */
    fun allowOnce(view: WebView, uri: Uri) {
        view.setTag(R.id.webview_allow_once_host_tag, uri.host?.lowercase())
    }

    /** Forgets an "Allow once" when the tab starts loading a page on a different site. */
    fun onMainFramePageStarted(view: WebView, uri: Uri) {
        val allowedHost = view.getTag(R.id.webview_allow_once_host_tag) as? String ?: return
        if (uri.host?.lowercase() != allowedHost) view.setTag(R.id.webview_allow_once_host_tag, null)
    }
}
