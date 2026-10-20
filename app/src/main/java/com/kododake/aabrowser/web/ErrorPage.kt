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
import androidx.core.net.toUri
import com.kododake.aabrowser.data.BrowserPreferences

/** The app's own page for sites that cannot be reached, served from the APK's assets. */
object ErrorPage {
    private const val BASE_URL = "file:///android_asset/error.html"

    fun urlFor(failedUrl: String, code: Int, message: String): String =
        "$BASE_URL?failedUrl=${Uri.encode(failedUrl)}&code=$code&message=${Uri.encode(message)}"

    fun isErrorPage(url: String?): Boolean = url?.startsWith(BASE_URL) == true

    /** The web address the error page stands in for, or null if [url] is not the error page. */
    fun failedUrl(url: String?): String? {
        if (!isErrorPage(url)) return null
        val failed = runCatching { url!!.toUri().getQueryParameter("failedUrl") }.getOrNull()
        return failed?.takeIf { BrowserPreferences.isHttpOrHttps(it) }
    }

    /** Error code the page was opened with: negative for connection errors, else an HTTP status. */
    fun errorCode(url: String?): Int? {
        if (!isErrorPage(url)) return null
        return runCatching { url!!.toUri().getQueryParameter("code")?.toIntOrNull() }.getOrNull()
    }

    /** What to show the user for [url]: the address that failed rather than the asset path. */
    fun displayUrl(url: String): String = failedUrl(url) ?: url
}
