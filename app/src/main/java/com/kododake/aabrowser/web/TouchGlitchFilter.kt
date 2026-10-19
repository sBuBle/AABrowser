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

import kotlin.math.hypot
import kotlin.math.max

/**
 * Filters out corrupt samples in single-finger touch gestures.
 *
 * Car head units relay touches to the phone at a low, uneven rate and some report stray points:
 * a sample that teleports far away, or a lift-off reported back near where the finger started.
 * WebView turns such a point into a huge fling in the opposite direction, so scrolling down with
 * a finger suddenly throws the page back to the top. Real fingers cannot move faster than the
 * platform's maximum fling velocity, do not leap back over most of the distance they just
 * travelled in one sample, and do not travel backwards while lifting off, which is what this
 * filter checks. Only isolated samples are dropped: if the finger really is somewhere else the
 * following samples agree with it and are delivered. Kept free of Android types so it can be
 * unit tested.
 *
 * @param maxSpeedPxPerMs fastest plausible finger movement; faster jumps are treated as glitches.
 * @param minGlitchDistancePx movements shorter than this are always trusted.
 */
class TouchGlitchFilter(
    private val maxSpeedPxPerMs: Float,
    private val minGlitchDistancePx: Float
) {
    private var tracking = false
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastTime = 0L
    private var droppedInARow = 0

    fun onDown(x: Float, y: Float, time: Long) {
        tracking = true
        downX = x
        downY = y
        droppedInARow = 0
        accept(x, y, time)
    }

    /** Stops filtering until the next gesture, e.g. for pinch zoom or a cancelled gesture. */
    fun reset() {
        tracking = false
    }

    /** Returns false when this move sample is a glitch and should not reach the page. */
    fun acceptMove(x: Float, y: Float, time: Long): Boolean {
        if (!tracking) return true
        if (droppedInARow < MAX_DROPPED_IN_A_ROW && (isTooFast(x, y, time) || isSnapBack(x, y))) {
            droppedInARow++
            return false
        }
        droppedInARow = 0
        accept(x, y, time)
        return true
    }

    /**
     * Returns the position the gesture should end at: `null` to keep the reported lift-off point,
     * or the last trusted position when the reported one is a glitch.
     */
    fun resolveUp(x: Float, y: Float, time: Long): Pair<Float, Float>? {
        if (!tracking) return null
        tracking = false
        val dx = x - lastX
        val dy = y - lastY
        if (hypot(dx, dy) < minGlitchDistancePx) return null
        val movesBackwards = dx * (lastX - downX) + dy * (lastY - downY) < 0f
        return if (movesBackwards || isTooFast(x, y, time)) lastX to lastY else null
    }

    private fun isTooFast(x: Float, y: Float, time: Long): Boolean {
        val distance = hypot(x - lastX, y - lastY)
        if (distance < minGlitchDistancePx) return false
        val elapsed = max(time - lastTime, 1L)
        return distance / elapsed > maxSpeedPxPerMs
    }

    private fun isSnapBack(x: Float, y: Float): Boolean {
        val dx = x - lastX
        val dy = y - lastY
        val distance = hypot(dx, dy)
        if (distance < minGlitchDistancePx) return false
        val travelledX = lastX - downX
        val travelledY = lastY - downY
        val movesBackwards = dx * travelledX + dy * travelledY < 0f
        return movesBackwards && distance > hypot(travelledX, travelledY) / 2f
    }

    private fun accept(x: Float, y: Float, time: Long) {
        lastX = x
        lastY = y
        lastTime = time
    }

    private companion object {
        const val MAX_DROPPED_IN_A_ROW = 2
    }
}
