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
 * a sample that teleports far away, a sample that repeats an earlier timestamp, or a lift-off
 * reported back near where the finger started.
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
    private var maxDistanceFromDown = 0f

    // Recent trusted samples, used to work out the fling velocity at lift-off.
    private val recentX = FloatArray(RECENT_SAMPLES)
    private val recentY = FloatArray(RECENT_SAMPLES)
    private val recentTime = LongArray(RECENT_SAMPLES)
    private var recentCount = 0
    private var recentNext = 0

    fun onDown(x: Float, y: Float, time: Long) {
        tracking = true
        downX = x
        downY = y
        droppedInARow = 0
        maxDistanceFromDown = 0f
        recentCount = 0
        recentNext = 0
        accept(x, y, time)
    }

    /** Stops filtering until the next gesture, e.g. for pinch zoom or a cancelled gesture. */
    fun reset() {
        tracking = false
    }

    /** Returns false when this move sample is a glitch and should not reach the page. */
    fun acceptMove(x: Float, y: Float, time: Long): Boolean {
        if (!tracking) return true
        val isGlitch = isOutOfOrder(x, y, time) || isTooFast(x, y, time) || isSnapBack(x, y)
        if (isGlitch && droppedInARow < MAX_DROPPED_IN_A_ROW) {
            droppedInARow++
            return false
        }
        droppedInARow = 0
        accept(x, y, time)
        return true
    }

    /** Whether the current gesture has moved further than [slopPx] from where it started. */
    fun hasScrolled(slopPx: Float): Boolean = tracking && maxDistanceFromDown > slopPx

    /**
     * Velocity in px/ms the gesture should fling with, measured over the trusted samples of the
     * last [VELOCITY_WINDOW_MS] rather than the last few events, so bursts of samples delivered at
     * once do not inflate it. Each axis is zeroed when it points against the overall movement of
     * the gesture and clamped to [maxSpeedPxPerMs], so a fling can never throw the page back.
     */
    fun flingVelocity(maxSpeedPxPerMs: Float): Pair<Float, Float> {
        if (!tracking || recentCount < 2) return 0f to 0f
        val newest = (recentNext - 1 + RECENT_SAMPLES) % RECENT_SAMPLES
        var oldest = newest
        for (i in 1 until recentCount) {
            val index = (newest - i + RECENT_SAMPLES) % RECENT_SAMPLES
            if (recentTime[newest] - recentTime[index] > VELOCITY_WINDOW_MS) {
                // Sparse samples (slow head units): fall back to the previous one if recent enough.
                if (oldest == newest && recentTime[newest] - recentTime[index] <= SPARSE_VELOCITY_WINDOW_MS) oldest = index
                break
            }
            oldest = index
        }
        val elapsed = recentTime[newest] - recentTime[oldest]
        if (elapsed <= 0L) return 0f to 0f
        val vx = (recentX[newest] - recentX[oldest]) / elapsed
        val vy = (recentY[newest] - recentY[oldest]) / elapsed
        return constrain(vx, lastX - downX, maxSpeedPxPerMs) to constrain(vy, lastY - downY, maxSpeedPxPerMs)
    }

    private fun constrain(velocity: Float, travelled: Float, max: Float): Float {
        if (velocity * travelled <= 0f) return 0f
        return velocity.coerceIn(-max, max)
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

    /** A new position stamped no later than the previous sample would read as infinite speed. */
    private fun isOutOfOrder(x: Float, y: Float, time: Long): Boolean =
        time <= lastTime && (x != lastX || y != lastY)

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
        maxDistanceFromDown = max(maxDistanceFromDown, hypot(x - downX, y - downY))
        recentX[recentNext] = x
        recentY[recentNext] = y
        recentTime[recentNext] = time
        recentNext = (recentNext + 1) % RECENT_SAMPLES
        recentCount = minOf(recentCount + 1, RECENT_SAMPLES)
    }

    private companion object {
        const val MAX_DROPPED_IN_A_ROW = 2
        const val RECENT_SAMPLES = 16
        const val VELOCITY_WINDOW_MS = 100L
        const val SPARSE_VELOCITY_WINDOW_MS = 250L
    }
}
