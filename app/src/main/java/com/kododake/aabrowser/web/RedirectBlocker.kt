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
import android.webkit.WebView
import com.kododake.aabrowser.adblock.FilterEngine
import com.kododake.aabrowser.data.BrowserPreferences

/**
 * Blocks pop-up windows and redirects to other sites that the user did not ask for, whether or not
 * the destination is on a filter list. Only a tap on a real link may open a window or leave the
 * site; sites on the exception list (Settings, or "Allow" on the blocked message) may do either.
 */
object RedirectBlocker {

    @Volatile
    var isEnabled: Boolean = true
        private set

    val allowedSites = SiteExceptionList(
        BrowserPreferences::getRedirectAllowedSites,
        BrowserPreferences::setRedirectAllowedSites
    )

    fun initialize(context: Context) {
        isEnabled = BrowserPreferences.isRedirectBlockingEnabled(context)
        allowedSites.load(context)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        isEnabled = enabled
        BrowserPreferences.setRedirectBlockingEnabled(context, enabled)
    }

    /** Whether the last tap in [view] was on a link, the one way pages may open windows or leave the site. */
    fun isLinkTap(view: WebView?): Boolean {
        val type = view?.hitTestResult?.type ?: return false
        return type == WebView.HitTestResult.SRC_ANCHOR_TYPE || type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
    }

    /** A new window the page tries to open, see [isLinkTap]. */
    fun shouldBlockPopup(openerUrl: String?, isLinkTap: Boolean): Boolean =
        isEnabled && !isLinkTap && SiteExceptionList.siteOf(openerUrl) != null && !allowedSites.contains(openerUrl)

    /** A page sending the whole tab to [targetUrl] on another site without a tap on a link. */
    fun shouldBlockRedirect(pageUrl: String?, targetUrl: String): Boolean {
        if (!isEnabled) return false
        val from = SiteExceptionList.siteOf(pageUrl) ?: return false
        val to = SiteExceptionList.siteOf(targetUrl) ?: return false
        if (FilterEngine.siteOf(from) == FilterEngine.siteOf(to)) return false
        return !allowedSites.contains(pageUrl)
    }
}
