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

/**
 * An Adblock Plus style URL pattern, matched against a lowercase URL.
 *
 * Supports `||` (start of the host or of one of its subdomains), `|` (start or end of the URL),
 * `*` (any characters) and `^` (a separator character or the end of the URL).
 */
internal class UrlPattern(pattern: String) {
    val isHostAnchored: Boolean
    private val isStartAnchored: Boolean
    private val isEndAnchored: Boolean

    /** The pattern without its anchors. */
    val body: String

    init {
        var p = pattern.lowercase()
        isHostAnchored = p.startsWith("||")
        if (isHostAnchored) p = p.substring(2)
        isStartAnchored = !isHostAnchored && p.startsWith("|")
        if (isStartAnchored) p = p.substring(1)
        isEndAnchored = p.endsWith("|")
        if (isEndAnchored) p = p.dropLast(1)
        body = p
    }

    /** Whether the token at [start, end) of [body] is bounded so that it must match a whole URL token. */
    fun isWholeToken(start: Int, end: Int): Boolean {
        val leftBounded = if (start == 0) isHostAnchored || isStartAnchored else body[start - 1] != '*'
        val rightBounded = if (end == body.length) isEndAnchored else body[end] != '*'
        return leftBounded && rightBounded
    }

    fun matches(url: String, hostStart: Int, hostEnd: Int): Boolean {
        if (isHostAnchored) {
            var start = hostStart
            while (start < hostEnd) {
                if (matchesFrom(url, start, 0)) return true
                val dot = url.indexOf('.', start)
                if (dot < 0 || dot >= hostEnd) return false
                start = dot + 1
            }
            return false
        }
        if (isStartAnchored) return matchesFrom(url, 0, 0)

        val first = body.firstOrNull()
        if (first == null || first == '*' || first == '^') {
            for (start in 0..url.length) {
                if (matchesFrom(url, start, 0)) return true
            }
            return false
        }
        var start = url.indexOf(first)
        while (start >= 0) {
            if (matchesFrom(url, start, 0)) return true
            start = url.indexOf(first, start + 1)
        }
        return false
    }

    private fun matchesFrom(url: String, urlStart: Int, bodyStart: Int): Boolean {
        var u = urlStart
        var b = bodyStart
        while (b < body.length) {
            when (val c = body[b]) {
                '*' -> {
                    while (b < body.length && body[b] == '*') b++
                    if (b == body.length) return true
                    for (next in u..url.length) {
                        if (matchesFrom(url, next, b)) return true
                    }
                    return false
                }
                '^' -> {
                    if (u < url.length) {
                        if (!isSeparator(url[u])) return false
                        u++
                    }
                    b++
                }
                else -> {
                    if (u >= url.length || url[u] != c) return false
                    u++
                    b++
                }
            }
        }
        return !isEndAnchored || u == url.length
    }

    companion object {
        fun isSeparator(c: Char): Boolean = !isTokenChar(c) && c != '_' && c != '-' && c != '.'

        fun isTokenChar(c: Char): Boolean = c in 'a'..'z' || c in '0'..'9' || c == '%'
    }
}
