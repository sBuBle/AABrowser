package com.kododake.aabrowser.ui.compose.screens.version

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionComparisonTest {

    @Test
    fun sameVersionIsUpToDate() {
        assertTrue(isUpToDate("3.3", "v3.3"))
    }

    @Test
    fun buildSuffixIsIgnored() {
        assertTrue(isUpToDate("3.3-debug", "v3.3"))
    }

    @Test
    fun newerInstalledVersionIsUpToDate() {
        // A fork release ahead of upstream must not be offered an "update" to an older version.
        assertTrue(isUpToDate("3.4", "v3.3"))
        assertTrue(isUpToDate("3.10", "v3.9"))
    }

    @Test
    fun olderInstalledVersionIsOutOfDate() {
        assertFalse(isUpToDate("3.3", "v3.4"))
        assertFalse(isUpToDate("3.3", "v3.3.1"))
    }

    @Test
    fun missingOrUnreadableLatestIsNotUpToDate() {
        assertFalse(isUpToDate("3.3", null))
        assertFalse(isUpToDate("3.3", "nightly"))
    }
}
