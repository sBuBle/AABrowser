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
 * Pure matching logic for the ad blocker, kept free of Android types so it can be unit tested.
 *
 * Host rules match the host itself and every subdomain of it (`doubleclick.net` also blocks
 * `ad.doubleclick.net`). Path rules block specific path prefixes on otherwise trusted hosts,
 * e.g. YouTube's ad beacons, without touching the rest of the site.
 */
class AdBlockRules(
    private val blockedHosts: Set<String>,
    private val blockedPaths: Map<String, List<String>> = emptyMap()
) {

    val size: Int get() = blockedHosts.size + blockedPaths.values.sumOf { it.size }

    fun shouldBlock(host: String?, path: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        val normalizedHost = host.lowercase().trimEnd('.')

        var candidate = normalizedHost
        while (true) {
            if (candidate in blockedHosts) return true
            val prefixes = blockedPaths[candidate]
            if (prefixes != null && path != null && prefixes.any { path.startsWith(it) }) return true
            val dot = candidate.indexOf('.')
            if (dot < 0 || candidate.indexOf('.', dot + 1) < 0) return false
            candidate = candidate.substring(dot + 1)
        }
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")

        /**
         * Parses a hosts-style rule list. Supported line formats:
         * - `example.com`               blocks the host and its subdomains
         * - `0.0.0.0 example.com`       hosts-file syntax, same as above
         * - `example.com/some/path`     blocks the path prefix on the host and its subdomains
         * Blank lines and lines starting with `#` or `!` are ignored.
         */
        fun parse(lines: Sequence<String>): AdBlockRules {
            val hosts = HashSet<String>()
            val paths = HashMap<String, MutableList<String>>()
            lines.forEach { raw ->
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty() || line.startsWith("!")) return@forEach
                val entry = line.split(WHITESPACE).last()
                val slash = entry.indexOf('/')
                if (slash > 0) {
                    paths.getOrPut(entry.substring(0, slash).lowercase()) { mutableListOf() }.add(entry.substring(slash))
                } else if (slash < 0 && entry.contains('.')) {
                    hosts.add(entry.lowercase().trimEnd('.'))
                }
            }
            return AdBlockRules(hosts, paths)
        }
    }
}
