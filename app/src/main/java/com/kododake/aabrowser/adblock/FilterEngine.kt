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
 * Ad blocking engine for Adblock Plus style filter lists (EasyList, EasyPrivacy, Peter Lowe's
 * list, ...) and hosts files. Free of Android types so it can be unit tested.
 *
 * Supported:
 * - network rules with `||`, `|`, `*`, `^`, `@@` exceptions and the options `third-party`,
 *   `first-party`, `domain=`, resource types (treated as "any type"), `popup`, `document`, `all`,
 *   `important` and `match-case` (both ignored)
 * - `@@...$document`, `$elemhide` and `$generichide` page exceptions
 * - element hiding: generic and site-specific `##` rules and `#@#` exceptions
 *
 * Rules using anything else (regular expressions, `redirect=`, `csp=`, scriptlets, procedural
 * cosmetic filters, ...) are skipped, so an unsupported rule never blocks more than intended.
 *
 * Rules are indexed by host and by a token taken from their pattern, so a request only has to be
 * checked against a handful of rules even with hundreds of thousands loaded.
 */
class FilterEngine private constructor(
    private val block: RuleIndex,
    private val allow: RuleIndex,
    private val navigationBlock: RuleIndex,
    private val navigationAllow: RuleIndex,
    private val documentAllow: RuleIndex,
    private val elemHideAllow: RuleIndex,
    private val genericHideAllow: RuleIndex,
    private val cosmetic: CosmeticFilters,
    val networkRuleCount: Int,
    val cosmeticRuleCount: Int
) {

    val ruleCount: Int get() = networkRuleCount + cosmeticRuleCount

    /**
     * Whether a sub-resource request should be blocked.
     *
     * @param pageUrl the URL of the page making the request, used for `third-party`, `domain=`
     * and page exceptions; null when unknown.
     */
    fun shouldBlockRequest(url: String, pageUrl: String?): Boolean {
        val request = Request.of(url, pageUrl) ?: return false
        if (request.page != null && documentAllow.matches(request.page)) return false
        return block.matches(request) && !allow.matches(request)
    }

    /**
     * Whether a top-level navigation is an ad: a pop-up window or a redirect the user did not ask
     * for. Uses the lists' `$popup` and `$document` rules, plus plain ad server host rules for
     * pop-ups since nobody wants a pop-up window from an ad server.
     */
    fun shouldBlockNavigation(url: String, openerUrl: String?, isPopup: Boolean): Boolean {
        val request = Request.of(url, openerUrl) ?: return false
        if (request.page != null && documentAllow.matches(request.page)) return false
        val blocked = navigationBlock.matches(request) || (isPopup && block.matchesHost(request))
        return blocked && !navigationAllow.matches(request) && !documentAllow.matches(request)
    }

    /** CSS for site-specific hiding rules, plus the generic ones that cannot be indexed. */
    fun pageCss(pageUrl: String, includeGenericComplex: Boolean): String {
        val page = Request.of(pageUrl, null) ?: return ""
        if (documentAllow.matches(page) || elemHideAllow.matches(page)) return ""
        val generic = includeGenericComplex && !genericHideAllow.matches(page)
        return toCss(cosmetic.pageSelectors(page.host, generic))
    }

    /**
     * CSS for generic hiding rules matching the given class (`.name`) and id (`#name`) tokens
     * found in the page.
     */
    fun genericCss(pageUrl: String, tokens: Collection<String>): String {
        val page = Request.of(pageUrl, null) ?: return ""
        if (documentAllow.matches(page) || elemHideAllow.matches(page) || genericHideAllow.matches(page)) return ""
        return toCss(cosmetic.genericSelectors(page.host, tokens))
    }

    private fun toCss(selectors: List<String>): String =
        // One rule per selector: a selector the browser does not understand only drops its own rule.
        selectors.joinToString("\n") { "$it{display:none!important}" }

    /** Collects rules from any number of lists, then [build]s an engine. Not thread safe. */
    class Builder {
        private val block = RuleIndex.Builder()
        private val allow = RuleIndex.Builder()
        private val navigationBlock = RuleIndex.Builder()
        private val navigationAllow = RuleIndex.Builder()
        private val documentAllow = RuleIndex.Builder()
        private val elemHideAllow = RuleIndex.Builder()
        private val genericHideAllow = RuleIndex.Builder()
        private val cosmetic = CosmeticFilters.Builder()
        private var networkRuleCount = 0
        private var cosmeticRuleCount = 0

        fun addList(lines: Sequence<String>): Builder {
            lines.forEach(::addLine)
            return this
        }

        fun addLine(raw: String) {
            val line = raw.trim()
            if (line.isEmpty() || line[0] == '!' || line[0] == '[') return
            // `#` starts a comment in hosts files, unless it is a generic cosmetic rule.
            if (line[0] == '#' && !line.startsWith("##") && !line.startsWith("#@#")) return
            if (addHostsFileLine(line)) return
            when {
                line.contains("#@#") -> addCosmetic(line, line.indexOf("#@#"), 3, exception = true)
                line.contains("##") -> addCosmetic(line, line.indexOf("##"), 2, exception = false)
                // Extended cosmetic, scriptlet and HTML filtering syntaxes are not supported.
                EXTENDED_COSMETIC.containsMatchIn(line) -> return
                else -> addNetwork(line)
            }
        }

        fun build(): FilterEngine = FilterEngine(
            block = block.build(),
            allow = allow.build(),
            navigationBlock = navigationBlock.build(),
            navigationAllow = navigationAllow.build(),
            documentAllow = documentAllow.build(),
            elemHideAllow = elemHideAllow.build(),
            genericHideAllow = genericHideAllow.build(),
            cosmetic = cosmetic.build(),
            networkRuleCount = networkRuleCount,
            cosmeticRuleCount = cosmeticRuleCount
        )

        /** `0.0.0.0 example.com` style lines from hosts files. */
        private fun addHostsFileLine(line: String): Boolean {
            val parts = line.substringBefore('#').trim().split(WHITESPACE)
            if (parts.size < 2 || parts[0] !in HOSTS_FILE_ADDRESSES) return false
            parts.drop(1)
                .map { it.lowercase().trimEnd('.') }
                .filter { it.contains('.') && it !in HOSTS_FILE_IGNORED }
                .forEach { host ->
                    block.addHost(host)
                    networkRuleCount++
                }
            return true
        }

        private fun addNetwork(line: String) {
            val isException = line.startsWith("@@")
            var text = if (isException) line.substring(2) else line

            val options = parseOptions(text) ?: return
            if (options.raw != null) text = text.substring(0, text.length - options.raw.length - 1)
            if (text.length > 1 && text.startsWith('/') && text.endsWith('/')) return // regular expression
            if (text.isEmpty() || text == "*" || text == "|" || text == "||") {
                // Only option-only page exceptions such as `@@$generichide,domain=x` make sense here.
                if (!isException || options.domains == null) return
                text = "*"
            }

            val rule = NetworkRule(UrlPattern(text), options.thirdParty, options.domains)
            if (isException) {
                if (options.elemHide) elemHideAllow.add(rule)
                if (options.genericHide) genericHideAllow.add(rule)
                if (options.document) documentAllow.add(rule)
                if (options.popup) navigationAllow.add(rule)
                if (options.appliesToRequests) allow.add(rule)
            } else {
                if (options.elemHide || options.genericHide) return
                if (options.popup || options.document) navigationBlock.add(rule)
                if (options.appliesToRequests) block.add(rule)
            }
            networkRuleCount++
        }

        private fun addCosmetic(line: String, separator: Int, separatorLength: Int, exception: Boolean) {
            val selector = line.substring(separator + separatorLength).trim()
            if (!isSupportedSelector(selector)) return
            val domains = line.substring(0, separator)
                .split(',')
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
            val includes = domains.filterNot { it.startsWith('~') }
            val excludes = domains.filter { it.startsWith('~') }.map { it.substring(1) }
            // Wildcard TLD entries (`example.*`) cannot be matched without a public suffix list.
            if (domains.isNotEmpty() && (includes + excludes).all { it.endsWith(".*") }) return
            cosmetic.add(
                selector = selector,
                includes = includes.filterNot { it.endsWith(".*") },
                excludes = excludes.filterNot { it.endsWith(".*") },
                exception = exception
            )
            cosmeticRuleCount++
        }

        private fun isSupportedSelector(selector: String): Boolean =
            selector.isNotEmpty() &&
                selector.none { it == '{' || it == '}' } &&
                !selector.startsWith("+js(") &&
                !selector.startsWith('^') &&
                !PROCEDURAL_SELECTOR.containsMatchIn(selector)

        private class Options(
            val raw: String?,
            val thirdParty: Boolean?,
            val domains: DomainConstraint?,
            val popup: Boolean,
            val document: Boolean,
            val requests: Boolean,
            val elemHide: Boolean,
            val genericHide: Boolean
        ) {
            val appliesToRequests: Boolean get() = requests || (!popup && !document && !elemHide && !genericHide)
        }

        /** Returns null when the rule uses an option this engine does not support. */
        private fun parseOptions(text: String): Options? {
            val dollar = text.lastIndexOf('$')
            if (dollar < 0) return Options(null, null, null, false, false, false, false, false)
            val raw = text.substring(dollar + 1)
            if (!OPTIONS_SHAPE.matches(raw)) {
                // Option values with spaces or quotes (`csp=...`) are unsupported options, not a
                // literal `$` in the pattern.
                return if (OPTION_START.containsMatchIn(raw)) null else Options(null, null, null, false, false, false, false, false)
            }

            var thirdParty: Boolean? = null
            var domains: DomainConstraint? = null
            var popup = false
            var document = false
            var requests = false
            var elemHide = false
            var genericHide = false
            for (option in raw.split(',')) {
                val negated = option.startsWith('~')
                val name = option.removePrefix("~").substringBefore('=').lowercase()
                when (name) {
                    "third-party", "3p" -> thirdParty = !negated
                    "first-party", "1p" -> thirdParty = negated
                    "domain", "from" -> domains = DomainConstraint.parse(option.substringAfter('=', ""), '|')
                    "popup" -> if (!negated) popup = true
                    "document", "doc" -> if (!negated) document = true
                    "all" -> {
                        popup = true
                        document = true
                        requests = true
                    }
                    "elemhide", "ehide", "specifichide", "shide" -> elemHide = true
                    "generichide", "ghide" -> genericHide = true
                    "important", "match-case" -> Unit
                    in RESOURCE_TYPES -> requests = true
                    else -> return null
                }
            }
            return Options(raw, thirdParty, domains, popup, document, requests, elemHide, genericHide)
        }
    }

    companion object {
        val EMPTY: FilterEngine = Builder().build()

        private val WHITESPACE = Regex("\\s+")
        private val OPTIONS_SHAPE = Regex("[A-Za-z0-9~,=|._*:-]+")
        private val OPTION_START = Regex("^~?[a-z0-9-]+(=|,|$)")
        private val EXTENDED_COSMETIC = Regex("#[?$%@]{1,2}#|\\$\\$|#\\+js")
        private val PROCEDURAL_SELECTOR = Regex(
            ":-abp-|:(has-text|contains|xpath|matches-[a-z]+|upward|remove|remove-attr|remove-class|style|" +
                "watch-attr|min-text-length|others|if|if-not|nth-ancestor)\\("
        )
        private val HOSTS_FILE_ADDRESSES = setOf("0.0.0.0", "127.0.0.1", "::", "::1")
        private val HOSTS_FILE_IGNORED = setOf("localhost", "localhost.localdomain", "local", "broadcasthost", "0.0.0.0")
        private val RESOURCE_TYPES = setOf(
            "script", "image", "stylesheet", "css", "object", "object-subrequest", "xmlhttprequest", "xhr",
            "subdocument", "frame", "ping", "beacon", "media", "font", "other", "websocket", "webrtc", "webbundle"
        )

        /** Approximates the registrable domain without a public suffix list. */
        internal fun siteOf(host: String): String {
            val labels = host.split('.')
            if (labels.size <= 2) return host
            val tld = labels[labels.size - 1]
            val second = labels[labels.size - 2]
            val keep = if (tld.length == 2 && (second.length <= 3 || second in SECOND_LEVEL_WORDS)) 3 else 2
            return labels.takeLast(keep).joinToString(".")
        }

        private val SECOND_LEVEL_WORDS = setOf("co", "com", "net", "org", "gov", "edu", "ac", "ne", "or", "go", "gob", "nic")
    }
}

/** A parsed request: lowercase URL with the position of its host, and the page it was made from. */
internal class Request(
    val url: String,
    val host: String,
    val hostStart: Int,
    val hostEnd: Int,
    val page: Request?
) {
    val isThirdParty: Boolean by lazy {
        page != null && FilterEngine.siteOf(host) != FilterEngine.siteOf(page.host)
    }

    companion object {
        fun of(url: String, pageUrl: String?): Request? {
            val lower = url.lowercase()
            val schemeEnd = lower.indexOf("://")
            if (schemeEnd <= 0) return null
            var hostStart = schemeEnd + 3
            val authorityEnd = lower.indexOfAny(charArrayOf('/', '?', '#'), hostStart).let { if (it < 0) lower.length else it }
            val at = lower.lastIndexOf('@', authorityEnd - 1)
            if (at >= hostStart) hostStart = at + 1
            var hostEnd = authorityEnd
            val colon = lower.lastIndexOf(':', authorityEnd - 1)
            if (colon >= hostStart && !lower.startsWith("[", hostStart)) hostEnd = colon
            if (hostEnd <= hostStart) return null
            val page = pageUrl?.let { of(it, null) }
            return Request(lower, lower.substring(hostStart, hostEnd).trimEnd('.'), hostStart, hostEnd, page)
        }
    }
}

/** `domain=` style include/exclude lists. */
internal class DomainConstraint(private val includes: List<String>, private val excludes: List<String>) {

    fun matches(host: String?): Boolean {
        if (host == null) return includes.isEmpty()
        if (excludes.any { covers(it, host) }) return false
        return includes.isEmpty() || includes.any { covers(it, host) }
    }

    companion object {
        fun covers(domain: String, host: String): Boolean =
            host == domain || (host.endsWith(domain) && host[host.length - domain.length - 1] == '.')

        fun parse(value: String, separator: Char): DomainConstraint? {
            val entries = value.split(separator).map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            if (entries.isEmpty()) return null
            return DomainConstraint(
                includes = entries.filterNot { it.startsWith('~') },
                excludes = entries.filter { it.startsWith('~') }.map { it.substring(1) }
            )
        }
    }
}

internal class NetworkRule(
    private val pattern: UrlPattern,
    private val thirdParty: Boolean?,
    private val domains: DomainConstraint?
) {
    val body: String get() = pattern.body
    val isHostAnchored: Boolean get() = pattern.isHostAnchored
    val isUnconstrained: Boolean get() = thirdParty == null && domains == null

    fun isWholeToken(start: Int, end: Int): Boolean = pattern.isWholeToken(start, end)

    fun matches(request: Request): Boolean {
        if (thirdParty != null && request.isThirdParty != thirdParty) return false
        if (domains != null && !domains.matches(request.page?.host)) return false
        return pattern.matches(request.url, request.hostStart, request.hostEnd)
    }
}

/** Indexes rules by host (for `||host^...` rules) and by a token from their pattern. */
internal class RuleIndex private constructor(
    private val plainHosts: Set<String>,
    private val byHost: Map<String, List<NetworkRule>>,
    private val byToken: Map<String, List<NetworkRule>>,
    private val unindexed: List<NetworkRule>
) {

    fun matches(request: Request): Boolean {
        if (matchesHost(request)) return true
        if (unindexed.any { it.matches(request) }) return true
        if (byToken.isEmpty()) return false
        val url = request.url
        val seen = HashSet<String>()
        var i = 0
        while (i < url.length) {
            if (!UrlPattern.isTokenChar(url[i])) {
                i++
                continue
            }
            val start = i
            while (i < url.length && UrlPattern.isTokenChar(url[i])) i++
            val token = url.substring(start, i)
            if (seen.add(token) && byToken[token]?.any { it.matches(request) } == true) return true
        }
        return false
    }

    /** Matches only the host-indexed rules, i.e. rules aimed at whole (ad) servers. */
    fun matchesHost(request: Request): Boolean {
        var candidate = request.host
        while (true) {
            if (candidate in plainHosts) return true
            if (byHost[candidate]?.any { it.matches(request) } == true) return true
            val dot = candidate.indexOf('.')
            if (dot < 0) return false
            candidate = candidate.substring(dot + 1)
        }
    }

    class Builder {
        private val plainHosts = HashSet<String>()
        private val byHost = HashMap<String, MutableList<NetworkRule>>()
        private val byToken = HashMap<String, MutableList<NetworkRule>>()
        private val unindexed = ArrayList<NetworkRule>()

        fun addHost(host: String) {
            plainHosts.add(host)
        }

        fun add(rule: NetworkRule) {
            val body = rule.body
            if (rule.isHostAnchored) {
                val hostEnd = body.indexOfFirst { it == '^' || it == '/' || it == ':' || it == '|' || it == '*' }
                val host = if (hostEnd < 0) null else body.substring(0, hostEnd)
                val terminated = hostEnd >= 0 && body[hostEnd] != '*'
                if (host != null && terminated && host.contains('.') && !host.endsWith('.') && !host.startsWith('.')) {
                    if (rule.isPlainHostRule(host)) plainHosts.add(host) else byHost.getOrPut(host) { ArrayList(1) }.add(rule)
                    return
                }
            }
            val token = bestToken(rule)
            if (token == null) unindexed.add(rule) else byToken.getOrPut(token) { ArrayList(1) }.add(rule)
        }

        fun build(): RuleIndex = RuleIndex(plainHosts, byHost, byToken, unindexed)

        private fun NetworkRule.isPlainHostRule(host: String): Boolean = isUnconstrained && body == "$host^"

        private fun bestToken(rule: NetworkRule): String? {
            val body = rule.body
            var best: String? = null
            var i = 0
            while (i < body.length) {
                if (!UrlPattern.isTokenChar(body[i])) {
                    i++
                    continue
                }
                val start = i
                while (i < body.length && UrlPattern.isTokenChar(body[i])) i++
                val token = body.substring(start, i)
                if (token.length >= 2 && token !in COMMON_TOKENS && rule.isWholeToken(start, i) &&
                    (best == null || token.length > best.length)
                ) {
                    best = token
                }
            }
            return best
        }
    }

    private companion object {
        val COMMON_TOKENS = setOf("http", "https", "www", "com", "net", "org", "js", "html", "php", "png", "jpg", "gif")
    }
}

/** Element hiding rules. Generic `.class` / `#id` rules are indexed so pages only get what they use. */
internal class CosmeticFilters private constructor(
    private val genericSimple: Set<String>,
    private val genericComplex: List<String>,
    private val specific: Map<String, List<SpecificSelector>>,
    private val scopedGeneric: List<SpecificSelector>,
    private val globalExceptions: Set<String>,
    private val specificExceptions: Map<String, Set<String>>
) {

    fun pageSelectors(host: String, includeGenericComplex: Boolean): List<String> {
        val exceptions = exceptionsFor(host)
        val result = LinkedHashSet<String>()
        forEachSuffix(host) { suffix ->
            specific[suffix]?.forEach { if (it.appliesTo(host)) result.add(it.selector) }
        }
        scopedGeneric.forEach { if (it.appliesTo(host)) result.add(it.selector) }
        if (includeGenericComplex) result.addAll(genericComplex)
        return result.filter { it !in exceptions && it !in globalExceptions }
    }

    fun genericSelectors(host: String, tokens: Collection<String>): List<String> {
        val exceptions = exceptionsFor(host)
        return tokens.filter { it in genericSimple && it !in exceptions && it !in globalExceptions }.distinct()
    }

    private fun exceptionsFor(host: String): Set<String> {
        val result = HashSet<String>()
        forEachSuffix(host) { suffix -> specificExceptions[suffix]?.let(result::addAll) }
        return result
    }

    private inline fun forEachSuffix(host: String, action: (String) -> Unit) {
        var candidate = host
        while (true) {
            action(candidate)
            val dot = candidate.indexOf('.')
            if (dot < 0) return
            candidate = candidate.substring(dot + 1)
        }
    }

    class SpecificSelector(val selector: String, private val excludes: List<String>) {
        fun appliesTo(host: String): Boolean = excludes.none { DomainConstraint.covers(it, host) }
    }

    class Builder {
        private val genericSimple = HashSet<String>()
        private val genericComplex = LinkedHashSet<String>()
        private val specific = HashMap<String, MutableList<SpecificSelector>>()
        private val scopedGeneric = ArrayList<SpecificSelector>()
        private val globalExceptions = HashSet<String>()
        private val specificExceptions = HashMap<String, MutableSet<String>>()

        fun add(selector: String, includes: List<String>, excludes: List<String>, exception: Boolean) {
            if (exception) {
                if (includes.isEmpty()) {
                    globalExceptions.add(selector)
                } else {
                    includes.forEach { specificExceptions.getOrPut(it) { HashSet() }.add(selector) }
                }
                return
            }
            when {
                includes.isNotEmpty() -> {
                    val rule = SpecificSelector(selector, excludes)
                    includes.forEach { specific.getOrPut(it) { ArrayList(1) }.add(rule) }
                }
                excludes.isNotEmpty() -> scopedGeneric.add(SpecificSelector(selector, excludes))
                SIMPLE_SELECTOR.matches(selector) -> genericSimple.add(selector)
                else -> genericComplex.add(selector)
            }
        }

        fun build(): CosmeticFilters = CosmeticFilters(
            genericSimple, genericComplex.toList(), specific, scopedGeneric, globalExceptions, specificExceptions
        )
    }

    private companion object {
        val SIMPLE_SELECTOR = Regex("[.#][A-Za-z0-9_-]+")
    }
}
