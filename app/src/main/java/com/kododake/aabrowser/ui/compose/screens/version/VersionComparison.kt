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

/**
 * Whether [installed] is at least [latest], comparing the numbers in each ("v3.4", "3.4-debug").
 * Plain string equality would call a newer build (a fork release, a debug build) out of date and
 * offer to "update" it to an older version.
 */
fun isUpToDate(installed: String, latest: String?): Boolean {
    val latestParts = versionNumbers(latest ?: return false) ?: return false
    val installedParts = versionNumbers(installed) ?: return false
    for (i in 0 until maxOf(latestParts.size, installedParts.size)) {
        val a = installedParts.getOrElse(i) { 0 }
        val b = latestParts.getOrElse(i) { 0 }
        if (a != b) return a > b
    }
    return true
}

/** The dotted numbers at the start of a version name, without a "v" prefix or "-suffix". */
private fun versionNumbers(version: String): List<Int>? {
    val core = version.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
    val parts = core.split('.').map { it.toIntOrNull() ?: return null }
    return parts.takeIf { it.isNotEmpty() }
}
