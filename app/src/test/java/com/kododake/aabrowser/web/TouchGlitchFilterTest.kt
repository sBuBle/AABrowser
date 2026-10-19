package com.kododake.aabrowser.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchGlitchFilterTest {

    // 16 px/ms is twice Android's default max fling velocity (8000 px/s) at density 1.
    private fun filter() = TouchGlitchFilter(maxSpeedPxPerMs = 16f, minGlitchDistancePx = 32f)

    /** Finger moves up the screen (scrolling the page down) 20 px every 16 ms. */
    private fun TouchGlitchFilter.scrollDown(steps: Int = 10): Long {
        onDown(100f, 600f, 0L)
        var time = 0L
        for (i in 1..steps) {
            time += 16
            assertTrue(acceptMove(100f, 600f - 20f * i, time))
        }
        return time
    }

    @Test
    fun normalScrollIsUntouched() {
        val filter = filter()
        val time = filter.scrollDown()
        assertNull(filter.resolveUp(100f, 395f, time + 8))
    }

    @Test
    fun fastRealFlickIsUntouched() {
        val filter = filter()
        filter.onDown(100f, 600f, 0L)
        // 200 px per 16 ms = 12.5 px/ms, a hard but humanly possible flick.
        assertTrue(filter.acceptMove(100f, 400f, 16L))
        assertTrue(filter.acceptMove(100f, 200f, 32L))
        assertNull(filter.resolveUp(100f, 190f, 40L))
    }

    @Test
    fun liftOffReportedBackAtStartIsMovedToLastPosition() {
        val filter = filter()
        val time = filter.scrollDown()
        assertEquals(100f to 400f, filter.resolveUp(100f, 600f, time + 50))
    }

    @Test
    fun liftOffTeleportingAwayIsMovedToLastPosition() {
        val filter = filter()
        val time = filter.scrollDown()
        assertEquals(100f to 400f, filter.resolveUp(0f, 0f, time + 5))
    }

    @Test
    fun strayMoveIsDroppedAndGestureContinues() {
        val filter = filter()
        val time = filter.scrollDown()
        assertFalse(filter.acceptMove(100f, 600f, time + 16))
        assertTrue(filter.acceptMove(100f, 380f, time + 32))
        assertNull(filter.resolveUp(100f, 378f, time + 40))
    }

    @Test
    fun delayedButConsistentMoveIsAccepted() {
        val filter = filter()
        val time = filter.scrollDown()
        // The head unit stalled for 100 ms and then reported where the finger really is.
        assertTrue(filter.acceptMove(100f, 200f, time + 100))
    }

    @Test
    fun nothingIsFilteredAfterReset() {
        val filter = filter()
        val time = filter.scrollDown()
        filter.reset()
        assertTrue(filter.acceptMove(100f, 600f, time + 1))
        assertNull(filter.resolveUp(0f, 0f, time + 2))
    }

    @Test
    fun realChangeOfDirectionGetsThroughAfterTwoSamples() {
        val filter = filter()
        val time = filter.scrollDown()
        // The finger turns around and keeps going: the first big backward samples look like
        // glitches, but the gesture must not stay frozen.
        assertFalse(filter.acceptMove(100f, 520f, time + 16))
        assertFalse(filter.acceptMove(100f, 560f, time + 32))
        assertTrue(filter.acceptMove(100f, 600f, time + 48))
        assertTrue(filter.acceptMove(100f, 610f, time + 64))
    }

    @Test
    fun slowTurnAroundIsUntouched() {
        val filter = filter()
        val time = filter.scrollDown()
        assertTrue(filter.acceptMove(100f, 410f, time + 16))
        assertTrue(filter.acceptMove(100f, 430f, time + 32))
        assertTrue(filter.acceptMove(100f, 460f, time + 48))
    }

    @Test
    fun sampleRepeatingPreviousTimestampIsDropped() {
        val filter = filter()
        val time = filter.scrollDown()
        assertFalse(filter.acceptMove(100f, 380f, time))
        assertTrue(filter.acceptMove(100f, 380f, time + 16))
    }
}
