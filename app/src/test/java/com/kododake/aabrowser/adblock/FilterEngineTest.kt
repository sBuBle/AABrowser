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
            "||cdn2.example^\$csp=script-src 'none'",
            "||cdn3.example^\$redirect-rule=noop.js",
            "||cdn4.example^\$removeparam=utm_source",
            "example.com#\$#abort-on-property-read x",
            "example.com#%#window.x = 1;",
            "example.com##^script:has-text(ads)",
            "example.com\$\$script[tag-content=\"ads\"]",
            "example.com##.ad:others()"
        )
        assertEquals(0, engine.ruleCount)
        assertFalse(engine.shouldBlockRequest("https://cdn2.example/x.js", page))
        assertFalse(engine.shouldBlockRequest("https://cdn3.example/x.js", page))
    }

    @Test
    fun regexRules() {
        val engine = engine("/banner\\d+\\.gif/", "/^https?:\\/\\/[a-z]{12}\\.com\\/[0-9]+\\.js/\$third-party")
        assertTrue(engine.shouldBlockRequest("https://cdn.site.com/img/banner12.gif", page))
        assertFalse(engine.shouldBlockRequest("https://cdn.site.com/img/banner.gif", page))
        assertTrue(engine.shouldBlockRequest("https://abcdefghijkl.com/123.js", page))
        assertFalse(engine.shouldBlockRequest("https://abcdefghijkl.com/123.js", "https://abcdefghijkl.com/"))

        val alternatives = engine("/\\.(club|xyz)\\/ads?\\/[0-9]+/", "/^https?:\\/\\/promo|^https?:\\/\\/[a-z]+\\.top\\//")
        assertTrue(alternatives.shouldBlockRequest("https://x.club/ad/1", page))
        assertTrue(alternatives.shouldBlockRequest("https://x.xyz/ads/22", page))
        assertFalse(alternatives.shouldBlockRequest("https://x.com/ads/22", page))
        assertTrue(alternatives.shouldBlockRequest("https://promo.example/x", page))
        assertTrue(alternatives.shouldBlockRequest("https://abc.top/x", page))
    }

    @Test
    fun shortAndCommonTokenRules() {
        val engine = engine("/a.gif?", ".com/b.js|", "/0.gif?\$third-party")
        assertTrue(engine.shouldBlockRequest("https://x.example/a.gif?id=1", page))
        assertFalse(engine.shouldBlockRequest("https://x.example/ba.gif?id=1", page))
        assertTrue(engine.shouldBlockRequest("https://cdn.x.com/b.js", page))
        assertFalse(engine.shouldBlockRequest("https://cdn.x.com/b.js?v=2", page))
        assertTrue(engine.shouldBlockRequest("https://t.example/0.gif?x", page))
        assertFalse(engine.shouldBlockRequest("https://news.example/0.gif?x", page))
    }

    @Test
    fun badfilterOnHostsFileEntry() {
        val engine = engine("0.0.0.0 cdn.example", "||cdn.example^\$badfilter")
        assertFalse(engine.shouldBlockRequest("https://cdn.example/lib.js", page))
    }

    @Test
    fun redirectRulesBlock() {
        val engine = engine("||imasdk.example^\$script,redirect=google-ima.js", "@@||imasdk.example^\$redirect=noop.js")
        assertTrue(engine.shouldBlockRequest("https://imasdk.example/ima3.js", page))
    }

    @Test
    fun badfilterDisablesRule() {
        val engine = engine("||ads.example^", "||ads.example^\$badfilter", "/promo/*\$domain=a.example", "/promo/*\$domain=a.example,badfilter")
        assertFalse(engine.shouldBlockRequest("https://ads.example/x.js", page))
        assertFalse(engine.shouldBlockRequest("https://cdn.example/promo/x.js", "https://a.example/"))
    }

    @Test
    fun importantOverridesExceptions() {
        val engine = engine("||ads.example^\$important", "@@||ads.example^", "||track.example^", "@@||track.example^")
        assertTrue(engine.shouldBlockRequest("https://ads.example/x.js", page))
        assertFalse(engine.shouldBlockRequest("https://track.example/x.js", page))
    }

    @Test
    fun denyallowAndToOptions() {
        val engine = engine("*\$script,3p,denyallow=cdn.example|static.example,domain=pirate.example", "/x.js\$to=ads.example")
        assertTrue(engine.shouldBlockRequest("https://pop.example/a.js", "https://pirate.example/"))
        assertFalse(engine.shouldBlockRequest("https://cdn.example/a.js", "https://pirate.example/"))
        assertFalse(engine.shouldBlockRequest("https://pop.example/a.js", "https://other.example/"))
        assertTrue(engine.shouldBlockRequest("https://s.ads.example/x.js", page))
        assertFalse(engine.shouldBlockRequest("https://cdn.example/x.js", page))
    }

    @Test
    fun wildcardTldEntries() {
        val engine = engine("google.*##.ad-box", "~google.co.uk,news.*##.promo", "/sponsor.\$domain=shop.*")
        assertTrue(engine.pageCss("https://www.google.de/", true).contains(".ad-box{"))
        assertTrue(engine.pageCss("https://google.co.jp/", true).contains(".ad-box{"))
        assertFalse(engine.pageCss("https://notgoogle.com/", true).contains(".ad-box{"))
        assertTrue(engine.pageCss("https://news.fr/", true).contains(".promo{"))
        assertTrue(engine.shouldBlockRequest("https://cdn.x.com/sponsor.js", "https://shop.de/"))
        assertFalse(engine.shouldBlockRequest("https://cdn.x.com/sponsor.js", "https://shopping.de/"))
    }

    @Test
    fun styleAndCssInjectionRules() {
        val engine = engine(
            "site.example##.wrapper:style(margin-top: 0 !important)",
            "site.example#\$#.video_reka { visibility: hidden !important; }",
            "site.example##.bad:style(background: url(http://x))",
            "##.everywhere:style(display: block)"
        )
        val css = engine.pageCss("https://site.example/", true)
        assertTrue(css.contains(".wrapper{margin-top: 0 !important}"))
        assertTrue(css.contains(".video_reka{visibility: hidden !important;}"))
        assertFalse(css.contains("url("))
        assertFalse(css.contains("everywhere"))
    }

    @Test
    fun proceduralRules() {
        val engine = engine(
            "site.example##.post:has-text(Sponsored)",
            "site.example#?#div.news:has(> a:contains(/casino|Betchaser/))",
            "site.example#?#.plain-selector",
            "site.example##.box:has(.inner)",
            "site.example#@#.post:has-text(Sponsored)"
        )
        assertEquals(listOf("div.news:has(> a:has-text(/casino|Betchaser/))"), engine.proceduralSelectors("https://site.example/"))
        val css = engine.pageCss("https://site.example/", true)
        assertTrue(css.contains(".plain-selector{"))
        assertTrue(css.contains(".box:has(.inner){"))
    }

    @Test
    fun scriptletRules() {
        val engine = engine(
            "site.example##+js(aopr, adsBlocked)",
            "site.example##+js(set, ads.enabled, false)",
            "site.example,other.example##+js(nostif, /ad\\,block/, 100)",
            "other.example#@#+js(nostif, /ad\\,block/, 100)",
            "site.example#%#//scriptlet('abort-current-inline-script', 'document.write', 'ads')",
            "##+js(nowebrtc)",
            "clean.example##+js(aopr, x)",
            "clean.example#@#+js()"
        )
        assertEquals(
            listOf(
                listOf("aopr", "adsBlocked"),
                listOf("set", "ads.enabled", "false"),
                listOf("nostif", "/ad,block/", "100"),
                listOf("abort-current-inline-script", "document.write", "ads")
            ),
            engine.scriptlets("https://www.site.example/")
        )
        assertEquals(emptyList<List<String>>(), engine.scriptlets("https://other.example/"))
        assertEquals(emptyList<List<String>>(), engine.scriptlets("https://clean.example/"))
    }

    @Test
    fun preprocessorDirectives() {
        val engine = engine(
            "!#if env_mobile", "||mobile.example^", "!#else", "||desktop.example^", "!#endif",
            "!#if (env_firefox || adguard_app_ios)", "||firefox.example^", "!#endif",
            "!#if !cap_html_filtering", "!#if ext_ublock && env_chromium", "||nested.example^", "!#endif", "!#endif"
        )
        assertTrue(engine.shouldBlockRequest("https://mobile.example/x", page))
        assertFalse(engine.shouldBlockRequest("https://desktop.example/x", page))
        assertFalse(engine.shouldBlockRequest("https://firefox.example/x", page))
        assertTrue(engine.shouldBlockRequest("https://nested.example/x", page))
    }

    @Test
    fun scriptletArgumentSplitting() {
        assertEquals(listOf("set", "a.b", "1"), FilterEngine.splitScriptletArgs("set, a.b, 1"))
        assertEquals(listOf("acs", "x", "a, b"), FilterEngine.splitScriptletArgs("acs, x, 'a, b'"))
        assertEquals(listOf("ra", "onclick"), FilterEngine.splitScriptletArgs("'ra', \"onclick\""))
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
