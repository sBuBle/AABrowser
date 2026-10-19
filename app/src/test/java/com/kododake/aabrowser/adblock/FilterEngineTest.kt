package com.kododake.aabrowser.adblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterEngineTest {

    private fun engine(vararg lines: String) = FilterEngine.Builder().addList(lines.asSequence()).build()

    private val page = "https://news.example/article"

    @Test
    fun hostAnchoredRuleBlocksHostAndSubdomainsOnly() {
        val engine = engine("||doubleclick.net^")
        assertTrue(engine.shouldBlockRequest("https://doubleclick.net/x", page))
        assertTrue(engine.shouldBlockRequest("https://securepubads.g.doubleclick.net/tag/js/gpt.js", page))
        assertFalse(engine.shouldBlockRequest("https://notdoubleclick.net/x", page))
        assertFalse(engine.shouldBlockRequest("https://doubleclick.net.evil.com/x", page))
        assertFalse(engine.shouldBlockRequest("https://example.com/?ref=doubleclick.net", page))
    }

    @Test
    fun hostRuleWithPathOnlyBlocksThatPath() {
        val engine = engine("||youtube.com/pagead/", "||youtube.com/api/stats/ads")
        assertTrue(engine.shouldBlockRequest("https://www.youtube.com/pagead/conversion/1", page))
        assertTrue(engine.shouldBlockRequest("https://m.youtube.com/api/stats/ads?x=1", page))
        assertFalse(engine.shouldBlockRequest("https://www.youtube.com/watch?v=1", page))
        assertFalse(engine.shouldBlockRequest("https://www.youtube.com/api/stats/playback", page))
    }

    @Test
    fun genericPatternsWithWildcardsAndSeparators() {
        val engine = engine("/ad_banner/*\$image", "-ad-300x250.", "&ad_type=", "/adframe^")
        assertTrue(engine.shouldBlockRequest("https://cdn.site.com/ad_banner/top.png", page))
        assertTrue(engine.shouldBlockRequest("https://site.com/img/foo-ad-300x250.jpg", page))
        assertTrue(engine.shouldBlockRequest("https://site.com/x?a=1&ad_type=video", page))
        assertTrue(engine.shouldBlockRequest("https://site.com/adframe?x", page))
        assertTrue(engine.shouldBlockRequest("https://site.com/adframe", page))
        assertFalse(engine.shouldBlockRequest("https://site.com/adframes/x", page))
        assertFalse(engine.shouldBlockRequest("https://site.com/my_banner/top.png", page))
    }

    @Test
    fun startAndEndAnchors() {
        val engine = engine("|https://ads.", ".swf|")
        assertTrue(engine.shouldBlockRequest("https://ads.site.com/x", page))
        assertFalse(engine.shouldBlockRequest("https://cdn.site.com/?u=https://ads.site.com", page))
        assertTrue(engine.shouldBlockRequest("https://site.com/movie.swf", page))
        assertFalse(engine.shouldBlockRequest("https://site.com/movie.swf?x=1", page))
    }

    @Test
    fun exceptionsOverrideBlocks() {
        val engine = engine("||ads.example.com^", "@@||ads.example.com/static/")
        assertTrue(engine.shouldBlockRequest("https://ads.example.com/banner.js", page))
        assertFalse(engine.shouldBlockRequest("https://ads.example.com/static/lib.js", page))
    }

    @Test
    fun thirdPartyOption() {
        val engine = engine("||tracker.com^\$third-party")
        assertTrue(engine.shouldBlockRequest("https://tracker.com/p.gif", "https://news.example/"))
        assertFalse(engine.shouldBlockRequest("https://tracker.com/p.gif", "https://www.tracker.com/"))
        assertFalse(engine.shouldBlockRequest("https://cdn.tracker.com/p.gif", "https://tracker.com/"))
    }

    @Test
    fun domainOption() {
        val engine = engine("/sponsor/*\$domain=news.example|~sports.news.example")
        assertTrue(engine.shouldBlockRequest("https://cdn.x.com/sponsor/a.js", "https://news.example/"))
        assertTrue(engine.shouldBlockRequest("https://cdn.x.com/sponsor/a.js", "https://www.news.example/"))
        assertFalse(engine.shouldBlockRequest("https://cdn.x.com/sponsor/a.js", "https://sports.news.example/"))
        assertFalse(engine.shouldBlockRequest("https://cdn.x.com/sponsor/a.js", "https://other.example/"))
    }

    @Test
    fun documentExceptionAllowlistsWholePage() {
        val engine = engine("||ads.com^", "@@||trusted.example^\$document")
        assertTrue(engine.shouldBlockRequest("https://ads.com/a.js", "https://news.example/"))
        assertFalse(engine.shouldBlockRequest("https://ads.com/a.js", "https://trusted.example/page"))
    }

    @Test
    fun unsupportedRulesAreSkipped() {
        val engine = engine(
            "/banner\\d+/",
            "||cdn.example^\$redirect=noop.js",
            "||cdn2.example^\$csp=script-src 'none'",
            "example.com#\$#abort-on-property-read x",
            "example.com##+js(nobab)",
            "example.com##.ad:has-text(Sponsored)"
        )
        assertEquals(0, engine.ruleCount)
        assertFalse(engine.shouldBlockRequest("https://cdn.example/x.js", page))
    }

    @Test
    fun hostsFileFormat() {
        val engine = engine("# comment", "0.0.0.0 ads.example.org", "127.0.0.1 localhost", "0.0.0.0 track.example.org # x")
        assertEquals(2, engine.networkRuleCount)
        assertTrue(engine.shouldBlockRequest("https://ads.example.org/x", page))
        assertTrue(engine.shouldBlockRequest("https://a.track.example.org/x", page))
        assertFalse(engine.shouldBlockRequest("http://localhost/x", page))
    }

    @Test
    fun popupAndDocumentRulesOnlyAffectNavigations() {
        val engine = engine("||popunder.example^\$popup", "||redirect.example^\$document", "||adserver.example^")
        assertFalse(engine.shouldBlockRequest("https://popunder.example/x.js", page))
        assertFalse(engine.shouldBlockRequest("https://redirect.example/x.js", page))
        assertTrue(engine.shouldBlockNavigation("https://popunder.example/go", page, isPopup = true))
        assertTrue(engine.shouldBlockNavigation("https://redirect.example/go", page, isPopup = false))
        // Plain ad server rules also stop pop-ups, but not ordinary navigations.
        assertTrue(engine.shouldBlockNavigation("https://adserver.example/land", page, isPopup = true))
        assertFalse(engine.shouldBlockNavigation("https://adserver.example/land", page, isPopup = false))
        assertFalse(engine.shouldBlockNavigation("https://news.example/next", page, isPopup = true))
    }

    @Test
    fun popupExceptions() {
        val engine = engine("||popunder.example^\$popup", "@@||popunder.example/login^\$popup")
        assertTrue(engine.shouldBlockNavigation("https://popunder.example/go", page, isPopup = true))
        assertFalse(engine.shouldBlockNavigation("https://popunder.example/login?x", page, isPopup = true))
    }

    @Test
    fun cosmeticRules() {
        val engine = engine(
            "##.ad-banner",
            "###sidebar-ad",
            "##div[data-ad-slot]",
            "news.example##.sponsored",
            "~sports.news.example,news.example##.promo",
            "news.example#@#.ad-banner",
            "other.example##.x { color: red }"
        )
        val css = engine.pageCss("https://www.news.example/", includeGenericComplex = true)
        assertTrue(css.contains(".sponsored{display:none!important}"))
        assertTrue(css.contains(".promo{"))
        assertTrue(css.contains("div[data-ad-slot]{"))
        assertFalse(css.contains("color: red"))
        assertFalse(engine.pageCss("https://sports.news.example/", true).contains(".promo"))
        assertFalse(engine.pageCss("https://www.news.example/", includeGenericComplex = false).contains("data-ad-slot"))

        val generic = engine.genericCss("https://other.example/", listOf(".ad-banner", ".content", "#sidebar-ad"))
        assertEquals(".ad-banner{display:none!important}\n#sidebar-ad{display:none!important}", generic)
        // Exception for news.example.
        assertEquals("", engine.genericCss("https://news.example/", listOf(".ad-banner")))
    }

    @Test
    fun elementHidingPageExceptions() {
        val engine = engine("##.ad-banner", "site.example##.box", "@@||site.example^\$generichide", "@@||clean.example^\$elemhide")
        assertEquals("", engine.genericCss("https://site.example/", listOf(".ad-banner")))
        assertTrue(engine.pageCss("https://site.example/", true).contains(".box"))
        assertEquals("", engine.pageCss("https://clean.example/", true))
    }

    @Test
    fun siteApproximation() {
        assertEquals("example.com", FilterEngine.siteOf("a.b.example.com"))
        assertEquals("example.co.uk", FilterEngine.siteOf("www.example.co.uk"))
        assertEquals("example.com.au", FilterEngine.siteOf("cdn.example.com.au"))
        assertEquals("example.de", FilterEngine.siteOf("www.example.de"))
    }
}
