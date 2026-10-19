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

import kotlin.math.abs
import kotlin.math.max

/**
 * Safety net for the page leaping backwards right after the finger lifts off a scroll.
 *
 * [TouchGlitchFilter] removes the corrupt touch samples we know about; this catches whatever is
 * left. After a scroll gesture ends, the page may keep moving the same way (a fling) but should
 * never travel most of a screen back the other way on its own. If it does within [windowMs], the
 * position the page had reached is returned so the caller can put it back. Touching the page again
 * disarms the guard, so "back to top" buttons and deliberate scrolls are never undone.
 */
class ScrollJumpGuard(
    private val windowMs: Long = 1000L
) {
    private var armed = false
    private var downScrollY = 0
    private var direction = 0
    private var furthestScrollY = 0
    private var armedAt = 0L
    private var jumpThresholdPx = 0

    fun onTouchDown(scrollY: Int) {
        armed = false
        downScrollY = scrollY
    }

    fun onGestureEnd(scrollY: Int, time: Long, viewportHeight: Int, minScrollPx: Int) {
        val scrolled = scrollY - downScrollY
        if (abs(scrolled) < minScrollPx) {
            armed = false
            return
        }
        armed = true
        direction = if (scrolled > 0) 1 else -1
        furthestScrollY = scrollY
        armedAt = time
        jumpThresholdPx = max(viewportHeight * 3 / 4, minScrollPx * 4)
    }

    /** Returns the scroll position to restore when this change is a jump, otherwise null. */
    fun onScrollChanged(scrollY: Int, time: Long): Int? {
        if (!armed) return null
        if (time - armedAt > windowMs) {
            armed = false
            return null
        }
        val progress = (scrollY - furthestScrollY) * direction
        if (progress >= 0) {
            furthestScrollY = scrollY
            return null
        }
        if (-progress <= jumpThresholdPx) return null
        armed = false
        return furthestScrollY
    }
}
