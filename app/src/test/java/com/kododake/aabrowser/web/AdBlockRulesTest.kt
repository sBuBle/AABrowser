package com.kododake.aabrowser.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdBlockRulesTest {

    private val rules = AdBlockRules.parse(
        """
        # comment line
        ! adblock-style comment
        doubleclick.net
        0.0.0.0 tracker.example
        Ads.Example.org.    # trailing comment and dot
        youtube.com/pagead/
        youtube.com/api/stats/ads

        """.trimIndent().lineSequence()
    )

    @Test
    fun parse_countsHostAndPathRules() {
        assertEquals(5, rules.size)
    }

    @Test
    fun blocksListedHostAndSubdomains() {
        assertTrue(rules.shouldBlock("doubleclick.net", "/"))
        assertTrue(rules.shouldBlock("securepubads.g.doubleclick.net", "/tag/js/gpt.js"))
        assertTrue(rules.shouldBlock("tracker.example", null))
    }

    @Test
    fun hostMatchingIsCaseInsensitiveAndIgnoresTrailingDot() {
        assertTrue(rules.shouldBlock("ADS.example.ORG.", "/banner.js"))
        assertTrue(rules.shouldBlock("cdn.ads.example.org", "/"))
    }

    @Test
    fun doesNotBlockUnrelatedOrLookalikeHosts() {
        assertFalse(rules.shouldBlock("example.org", "/"))
        assertFalse(rules.shouldBlock("notdoubleclick.net", "/"))
        assertFalse(rules.shouldBlock("doubleclick.net.evil.com", "/"))
        assertFalse(rules.shouldBlock(null, "/"))
        assertFalse(rules.shouldBlock("", "/"))
    }

    @Test
    fun pathRulesOnlyBlockMatchingPrefixes() {
        assertTrue(rules.shouldBlock("www.youtube.com", "/pagead/viewthroughconversion/1"))
        assertTrue(rules.shouldBlock("m.youtube.com", "/api/stats/ads"))
        assertFalse(rules.shouldBlock("www.youtube.com", "/watch"))
        assertFalse(rules.shouldBlock("www.youtube.com", "/api/stats/playback"))
        assertFalse(rules.shouldBlock("www.youtube.com", null))
    }
}
