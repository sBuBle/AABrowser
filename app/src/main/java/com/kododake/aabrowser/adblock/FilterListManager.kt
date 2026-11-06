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

package com.kododake.aabrowser.adblock

import android.content.Context
import android.net.ConnectivityManager
import android.os.LocaleList
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads and caches the popular community filter lists used by the ad blocker.
 *
 * Lists are refreshed every [UPDATE_INTERVAL_MS] (EasyList itself expires after 4 days). On a
 * metered connection, such as a phone tethered in a car, existing lists are only refreshed once
 * they are [METERED_UPDATE_INTERVAL_MS] old.
 */
object FilterListManager {

    /**
     * A downloadable list. [languages] limits a regional list to devices set to one of those
     * languages; null means the list is always used.
     */
    class FilterList(val id: String, val title: String, val urls: List<String>, val languages: Set<String>? = null)

    val LISTS = listOf(
        FilterList(
            id = "easylist",
            title = "EasyList",
            urls = listOf(
                "https://easylist.to/easylist/easylist.txt",
                "https://easylist-downloads.adblockplus.org/easylist.txt"
            )
        ),
        FilterList(
            id = "easyprivacy",
            title = "EasyPrivacy",
            urls = listOf(
                "https://easylist.to/easylist/easyprivacy.txt",
                "https://easylist-downloads.adblockplus.org/easyprivacy.txt"
            )
        ),
        FilterList(
            id = "peter-lowe",
            title = "Peter Lowe's list",
            urls = listOf(
                "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=0&mimetype=plaintext"
            )
        ),
        // DNS level list: covers ad, telemetry and analytics servers (Xiaomi, Oppo, Huawei, Apple,
        // TikTok, Yahoo, ...) that the browser lists above leave out.
        FilterList(
            id = "hagezi-light",
            title = "HaGeZi Light",
            urls = listOf(
                "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/light.txt",
                "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/light.txt",
                "https://gitlab.com/hagezi/mirror/-/raw/main/dns-blocklists/adblock/light.txt"
            )
        ),
        // uBlock Origin's own lists: ads EasyList misses, scriptlets for inline and anti-adblock
        // ads, fixes for EasyList rules that break sites, and exceptions that keep sites working.
        FilterList(
            id = "ublock-filters",
            title = "uBlock filters",
            urls = uBlockAsset("filters/filters.txt")
        ),
        FilterList(
            id = "ublock-quick-fixes",
            title = "uBlock filters – Quick fixes",
            urls = uBlockAsset("filters/quick-fixes.txt")
        ),
        FilterList(
            id = "ublock-unbreak",
            title = "uBlock filters – Unbreak",
            urls = uBlockAsset("filters/unbreak.txt")
        ),
        // Ads served to mobile browsers and in-app web views, which desktop lists leave out.
        FilterList(
            id = "adguard-mobile",
            title = "AdGuard Mobile Ads",
            urls = adGuardFilter(11)
        ),
        // Regional lists, used when the device is set to one of the region's languages.
        FilterList(
            id = "adguard-russian",
            title = "AdGuard Russian",
            urls = adGuardFilter(1),
            languages = setOf("ru", "uk", "be", "kk", "ky", "tg", "uz", "hy", "az", "ka")
        )
    )

    private fun uBlockAsset(path: String) = listOf(
        "https://ublockorigin.github.io/uAssets/$path",
        "https://cdn.jsdelivr.net/gh/uBlockOrigin/uAssets@latest/$path",
        "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/$path"
    )

    /** AdGuard filter [id] in its uBlock Origin flavour, which uses the syntax the engine supports. */
    private fun adGuardFilter(id: Int) = listOf(
        "https://filters.adtidy.org/extension/ublock/filters/$id.txt",
        "https://raw.githubusercontent.com/AdguardTeam/FiltersRegistry/master/platforms/extension/ublock/filters/$id.txt"
    )

    /** The lists used on this device: every general list, plus regional lists for its languages. */
    fun activeLists(): List<FilterList> {
        val languages = deviceLanguages()
        return LISTS.filter { list -> list.languages == null || list.languages.any { it in languages } }
    }

    private fun deviceLanguages(): Set<String> {
        val locales = LocaleList.getDefault()
        return (0 until locales.size()).mapTo(HashSet()) { locales[it].language }
    }

    private const val TAG = "FilterListManager"
    private const val DIRECTORY = "adblock"
    private const val UPDATE_INTERVAL_MS = 4L * 24 * 60 * 60 * 1000
    private const val METERED_UPDATE_INTERVAL_MS = 14L * 24 * 60 * 60 * 1000
    private const val MAX_LIST_BYTES = 20L * 1024 * 1024
    private const val MIN_RULE_LINES = 100

    fun listFiles(context: Context): List<File> =
        activeLists().map { fileFor(context, it) }.filter { it.isFile && it.length() > 0 }

    /** Time of the oldest successful download, or 0 if any list has never been downloaded. */
    fun lastUpdated(context: Context): Long {
        val files = activeLists().map { fileFor(context, it) }
        if (files.any { !it.isFile }) return 0L
        return files.minOf { it.lastModified() }
    }

    fun needsUpdate(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val oldest = activeLists().minOf { fileFor(context, it).takeIf(File::isFile)?.lastModified() ?: 0L }
        val age = now - oldest
        if (age < UPDATE_INTERVAL_MS) return false
        val metered = context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true
        return oldest == 0L || !metered || age >= METERED_UPDATE_INTERVAL_MS
    }

    /**
     * Downloads every list, keeping the previous copy of any list that fails. Must be called off
     * the main thread. Returns true when at least one list was updated.
     */
    fun downloadAll(context: Context): Boolean {
        val directory = File(context.filesDir, DIRECTORY).apply { mkdirs() }
        var updated = false
        activeLists().forEach { list ->
            val target = File(directory, "${list.id}.txt")
            val temp = File(directory, "${list.id}.tmp")
            val ok = list.urls.any { url -> download(url, temp) }
            if (ok && temp.renameTo(target)) {
                target.setLastModified(System.currentTimeMillis())
                updated = true
            } else {
                temp.delete()
            }
        }
        return updated
    }

    private fun download(url: String, target: File): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Accept", "text/plain")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return false
            var bytes = 0L
            var ruleLines = 0
            connection.inputStream.bufferedReader().useLines { lines ->
                target.bufferedWriter().use { writer ->
                    for (line in lines) {
                        bytes += line.length + 1
                        if (bytes > MAX_LIST_BYTES) return false
                        if (ruleLines == 0 && line.trimStart().startsWith("<")) return false
                        if (line.isNotBlank() && !line.startsWith("!")) ruleLines++
                        writer.write(line)
                        writer.write('\n'.code)
                    }
                }
            }
            // Guard against captive portals and error pages served with status 200.
            ruleLines >= MIN_RULE_LINES
        } catch (e: Exception) {
            Log.w(TAG, "Failed to download $url: ${e.message}")
            false
        } finally {
            connection?.disconnect()
        }
    }

    private fun fileFor(context: Context, list: FilterList): File =
        File(File(context.filesDir, DIRECTORY), "${list.id}.txt")
}
