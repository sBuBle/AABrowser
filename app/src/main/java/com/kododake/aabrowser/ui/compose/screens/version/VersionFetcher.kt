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

package com.kododake.aabrowser.ui.compose.screens.version

import android.app.Activity
import androidx.core.net.toUri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object VersionFetcher {

    private const val LATEST_RELEASE_API = "https://api.github.com/repos/kododake/AABrowser/releases/latest"
    private const val MAX_RESPONSE_CHARS = 512 * 1024

    fun fetchLatestVersion(
        activity: Activity,
        onSuccess: (latestUrl: String, tagName: String) -> Unit,
        onError: () -> Unit
    ) {
        Thread {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                }

                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = conn.inputStream.bufferedReader().use { reader ->
                        val buffer = CharArray(MAX_RESPONSE_CHARS)
                        var length = 0
                        while (length < buffer.size) {
                            val read = reader.read(buffer, length, buffer.size - length)
                            if (read < 0) break
                            length += read
                        }
                        String(buffer, 0, length)
                    }
                    val json = JSONObject(response)
                    // The link is opened in the browser later, so only accept a GitHub page.
                    val latestUrl = json.getString("html_url").takeIf(::isGitHubPage)
                    val tag = json.getString("tag_name")

                    activity.runOnUiThread {
                        if (latestUrl != null) onSuccess(latestUrl, tag) else onError()
                    }
                } else {
                    activity.runOnUiThread { onError() }
                }
            } catch (_: Exception) {
                activity.runOnUiThread { onError() }
            } finally {
                conn?.disconnect()
            }
        }.start()
    }

    private fun isGitHubPage(url: String): Boolean {
        val uri = runCatching { url.toUri() }.getOrNull() ?: return false
        return uri.scheme == "https" && uri.host == "github.com"
    }
}
