package com.kododake.aabrowser.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScrollJumpGuardTest {

    private val viewport = 480
    private val slop = 8

    /** Scroll the page down from 1000 to 1600 and lift the finger at t=0. */
    private fun guardAfterScrollDown() = ScrollJumpGuard(windowMs = 1000L).apply {
        onTouchDown(1000)
        onGestureEnd(1600, 0L, viewport, slop)
    }

    @Test
    fun flingContinuingTheScrollIsLeftAlone() {
        val guard = guardAfterScrollDown()
        assertNull(guard.onScrollChanged(1800, 50L))
        assertNull(guard.onScrollChanged(2100, 100L))
        // A small settle-back (e.g. scroll snapping) is fine.
        assertNull(guard.onScrollChanged(2050, 150L))
    }

    @Test
    fun jumpBackToTopIsRestored() {
        val guard = guardAfterScrollDown()
        assertNull(guard.onScrollChanged(1700, 30L))
        assertEquals(1700, guard.onScrollChanged(0, 60L))
        // Restoring triggers another scroll change; the guard must not fight it.
        assertNull(guard.onScrollChanged(1700, 70L))
    }

    @Test
    fun jumpAfterTheWindowIsLeftAlone() {
        val guard = guardAfterScrollDown()
        assertNull(guard.onScrollChanged(0, 1500L))
    }

    @Test
    fun touchingAgainDisarmsTheGuard() {
        val guard = guardAfterScrollDown()
        guard.onTouchDown(1600)
        assertNull(guard.onScrollChanged(0, 100L))
    }

    @Test
    fun tapWithoutScrollingDoesNotArm() {
        val guard = ScrollJumpGuard()
        guard.onTouchDown(1000)
        guard.onGestureEnd(1003, 0L, viewport, slop)
        assertNull(guard.onScrollChanged(0, 100L))
    }

    @Test
    fun jumpToBottomAfterScrollingUpIsRestored() {
        val guard = ScrollJumpGuard()
        guard.onTouchDown(3000)
        guard.onGestureEnd(2500, 0L, viewport, slop)
        assertNull(guard.onScrollChanged(2400, 20L))
        assertEquals(2400, guard.onScrollChanged(9000, 40L))
    }
}
