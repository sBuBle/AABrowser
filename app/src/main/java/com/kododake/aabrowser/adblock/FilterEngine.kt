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
 * Ad blocking engine for Adblock Plus, uBlock Origin and AdGuard style filter lists (EasyList,
 * EasyPrivacy, uBlock filters, AdGuard, ...) and hosts files. Free of Android types so it can be
 * unit tested.
 *
 * Supported:
 * - network rules with `||`, `|`, `*`, `^`, `/regex/`, `@@` exceptions and the options
 *   `third-party`, `first-party`, `strict3p`, `strict1p`, `domain=`, `denyallow=`, `to=`,
 *   resource types (treated as "any type"), `popup`, `popunder`, `document`, `all`, `important`,
 *   `badfilter`, `match-case` (ignored), and `redirect=` / `rewrite=` / `empty` / `mp4` (blocked
 *   like a plain rule, since blocking is what matters here)
 * - `@@...$document`, `$elemhide` and `$generichide` page exceptions
 * - element hiding: generic and site-specific `##` rules and `#@#` exceptions, with `example.*`
 *   entries, `:style()` and AdGuard's `#$#selector { style }` rules
 * - procedural cosmetic rules (`:has-text()`, `:upward()`, `:remove()`, `:xpath()`, ... and the
 *   `#?#` syntax of Adblock Plus and AdGuard), which [WebScripts] applies in the page
 * - scriptlets: uBlock Origin's `##+js(...)` and AdGuard's `#%#//scriptlet(...)`, also run by the
 *   page script
 *
 * Rules using anything else (`csp=`, `removeparam=`, `redirect-rule=`, HTML filtering, raw
 * JavaScript, ...) are skipped, so an unsupported rule never blocks more than intended.
 *
 * Rules are indexed by host and by a token taken from their pattern, so a request only has to be
 * checked against a handful of rules even with hundreds of thousands loaded.
 */
class FilterEngine private constructor(
    private val block: RuleIndex,
    private val importantBlock: RuleIndex,
    private val allow: RuleIndex,
    private val navigationBlock: RuleIndex,
    private val navigationAllow: RuleIndex,
    private val documentAllow: RuleIndex,
    private val elemHideAllow: RuleIndex,
    private val genericHideAllow: RuleIndex,
    private val cosmetic: CosmeticFilters,
    private val styles: CosmeticFilters,
    private val procedural: CosmeticFilters,
    private val scriptlets: CosmeticFilters,
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
        if (importantBlock.matches(request)) return true
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

    /** CSS for site-specific hiding and style rules, plus the generic ones that cannot be indexed. */
    fun pageCss(pageUrl: String, includeGenericComplex: Boolean): String {
        val page = Request.of(pageUrl, null) ?: return ""
        if (documentAllow.matches(page) || elemHideAllow.matches(page)) return ""
        val generic = includeGenericComplex && !genericHideAllow.matches(page)
        val hide = toCss(cosmetic.pageSelectors(page.host, generic))
        val style = styles.pageSelectors(page.host, generic).joinToString("\n")
        return if (style.isEmpty()) hide else if (hide.isEmpty()) style else "$hide\n$style"
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

    /** Procedural cosmetic selectors for [pageUrl], in uBlock Origin syntax. */
    fun proceduralSelectors(pageUrl: String): List<String> {
        val page = Request.of(pageUrl, null) ?: return emptyList()
        if (documentAllow.matches(page) || elemHideAllow.matches(page)) return emptyList()
        return procedural.pageSelectors(page.host, !genericHideAllow.matches(page))
    }

    /**
     * Scriptlets to run on [pageUrl], each as its name followed by its arguments. A `#@#+js()`
     * exception turns all of them off for a site.
     */
    fun scriptlets(pageUrl: String): List<List<String>> {
        val page = Request.of(pageUrl, null) ?: return emptyList()
        if (documentAllow.matches(page)) return emptyList()
        if (scriptlets.isExcepted(page.host, "")) return emptyList()
        return scriptlets.pageSelectors(page.host, includeGenericComplex = true).mapNotNull(::parseScriptlet)
    }

    private fun toCss(selectors: List<String>): String =
        // One rule per selector: a selector the browser does not understand only drops its own rule.
        selectors.joinToString("\n") { "$it{display:none!important}" }

    /** Collects rules from any number of lists, then [build]s an engine. Not thread safe. */
    class Builder {
        private val block = RuleIndex.Builder()
        private val importantBlock = RuleIndex.Builder()
        private val allow = RuleIndex.Builder()
        private val navigationBlock = RuleIndex.Builder()
        private val navigationAllow = RuleIndex.Builder()
        private val documentAllow = RuleIndex.Builder()
        private val elemHideAllow = RuleIndex.Builder()
        private val genericHideAllow = RuleIndex.Builder()
        private val cosmetic = CosmeticFilters.Builder()
        private val styles = CosmeticFilters.Builder()
        private val procedural = CosmeticFilters.Builder()
        private val scriptlets = CosmeticFilters.Builder()
        /** Hashes of the rules `$badfilter` rules turn off, see [ruleKey]. */
        private val badFilters = HashSet<Long>()
        /** Hosts of turned off `||host^` rules, which are stored as plain hosts without a hash. */
        private val badHosts = HashSet<String>()
        private var networkRuleCount = 0
        private var cosmeticRuleCount = 0

        /** `!#if` blocks of the list being added: whether each one's lines are used. */
        private val conditions = ArrayList<Boolean>()

        fun addList(lines: Sequence<String>): Builder {
            conditions.clear()
            lines.forEach(::addLine)
            conditions.clear()
            return this
        }

        fun addLine(raw: String) {
            val line = raw.trim()
            if (line.startsWith("!#")) {
                handleDirective(line)
                return
            }
            if (conditions.any { !it }) return
            if (line.isEmpty() || line[0] == '!' || line[0] == '[') return
            // `#` starts a comment in hosts files, unless it is a generic cosmetic rule.
            if (line[0] == '#' && cosmeticSeparator(line, 0) == null) return
            if (addHostsFileLine(line)) return
            val hash = line.indexOf('#')
            val separator = if (hash >= 0 && COSMETIC_DOMAINS.matches(line.substring(0, hash))) {
                cosmeticSeparator(line, hash)
            } else {
                null
            }
            when {
                separator != null -> addCosmetic(
                    domainList = line.substring(0, hash),
                    separator = separator,
                    body = line.substring(hash + separator.length).trim()
                )
                // HTML filtering is not supported.
                line.contains("$$") || line.contains("$@$") -> return
                else -> addNetwork(line)
            }
        }

        fun build(): FilterEngine = FilterEngine(
            block = block.build(badFilters, badHosts),
            importantBlock = importantBlock.build(badFilters, badHosts),
            allow = allow.build(badFilters, badHosts),
            navigationBlock = navigationBlock.build(badFilters, badHosts),
            navigationAllow = navigationAllow.build(badFilters, badHosts),
            documentAllow = documentAllow.build(badFilters, badHosts),
            elemHideAllow = elemHideAllow.build(badFilters, badHosts),
            genericHideAllow = genericHideAllow.build(badFilters, badHosts),
            cosmetic = cosmetic.build(),
            styles = styles.build(),
            procedural = procedural.build(),
            scriptlets = scriptlets.build(),
            networkRuleCount = networkRuleCount,
            cosmeticRuleCount = cosmeticRuleCount
        )

        /** uBlock Origin and AdGuard `!#if`, `!#else` and `!#endif` preprocessor directives. */
        private fun handleDirective(line: String) {
            when {
                line.startsWith("!#if ") -> conditions.add(evaluateCondition(line.substring(5)))
                line == "!#else" -> if (conditions.isNotEmpty()) conditions[conditions.size - 1] = !conditions.last()
                line == "!#endif" -> if (conditions.isNotEmpty()) conditions.removeAt(conditions.size - 1)
            }
        }

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
            if (options.badFilter) {
                val key = ruleKey(line, options)
                badFilters.add(hashKey(key))
                PLAIN_HOST_RULE.matchEntire(key)?.let { badHosts.add(it.groupValues[1].lowercase()) }
                return
            }
            val isRegex = text.length > 2 && text.startsWith('/') && text.endsWith('/')
            if (text.isEmpty() || text == "*" || text == "|" || text == "||") {
                // Only rules limited to some sites make sense here: page exceptions such as
                // `@@$generichide,domain=x`, or uBlock Origin's `*$script,3p,denyallow=...,domain=x`.
                if (options.domains == null) return
                text = "*"
            }
            if (isException && options.redirect) return // only turns a redirect off, never blocks

            val pattern = if (isRegex) {
                val regex = runCatching { Regex(text.substring(1, text.length - 1), RegexOption.IGNORE_CASE) }
                    .getOrNull() ?: return
                RulePattern.OfRegex(regex)
            } else {
                RulePattern.OfUrl(UrlPattern(text))
            }
            val rule = NetworkRule(pattern, options.thirdParty, options.domains, options.targets, hashKey(ruleKey(line, options)))
            if (isException) {
                if (options.elemHide) elemHideAllow.add(rule)
                if (options.genericHide) genericHideAllow.add(rule)
                if (options.document) documentAllow.add(rule)
                if (options.popup) navigationAllow.add(rule)
                if (options.appliesToRequests) allow.add(rule)
            } else {
                if (options.elemHide || options.genericHide) return
                if (options.popup || options.document) navigationBlock.add(rule)
                if (options.appliesToRequests) {
                    block.add(rule)
                    if (options.important) importantBlock.add(rule)
                }
            }
            networkRuleCount++
        }

        /** The rule as `$badfilter` refers to it: its text without the `badfilter` option. */
        private fun ruleKey(line: String, options: Options): String {
            if (options.raw == null) return line
            val kept = options.raw.split(',').filterNot { it.equals("badfilter", ignoreCase = true) }
            val base = line.substring(0, line.length - options.raw.length - 1)
            return if (kept.isEmpty()) base else base + "$" + kept.joinToString(",")
        }

        /**
         * 64-bit FNV-1a hash of a rule key: rules keep this instead of their text, which would
         * double the memory the lists take. A collision would need two of a few hundred thousand
         * rules to share a hash, which is vanishingly unlikely.
         */
        private fun hashKey(key: String): Long {
            var hash = -0x340d631b7bdddcdbL
            for (c in key) {
                hash = hash xor c.code.toLong()
                hash *= 0x100000001b3L
            }
            return hash
        }

        private fun addCosmetic(domainList: String, separator: String, body: String) {
            if (body.isEmpty()) return
            val exception = separator.startsWith("#@")
            val domains = domainList
                .split(',')
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
            val includes = domains.filterNot { it.startsWith('~') }
            val excludes = domains.filter { it.startsWith('~') }.map { it.substring(1) }
            val target = when (separator) {
                "##", "#@#" -> classifyUboCosmetic(body)
                "#?#", "#@?#" -> classifyExtendedCss(body)
                "#$#", "#@$#" -> classifyCssInjection(body)
                "#%#", "#@%#" -> classifyAdGuardScriptlet(body)
                else -> null
            } ?: return
            val (store, value) = target
            // Scriptlets and style rules change how a page behaves; never apply them everywhere.
            if ((store === scriptlets || store === styles) && includes.isEmpty() && !exception) return
            store.add(selector = value, includes = includes, excludes = excludes, exception = exception)
            cosmeticRuleCount++
        }

        /** `##` rules: plain CSS, `+js(...)` scriptlets, `:style()` and procedural selectors. */
        private fun classifyUboCosmetic(body: String): Pair<CosmeticFilters.Builder, String>? {
            if (body.startsWith("+js(")) {
                if (!body.endsWith(")")) return null
                return scriptlets to normalizeScriptlet(body.substring(4, body.length - 1))
            }
            if (body.startsWith('^')) return null // HTML filtering
            STYLE_SUFFIX.find(body)?.let { match ->
                val selector = body.substring(0, match.range.first)
                val declarations = match.groupValues[1].trim()
                if (selector.isEmpty() || !isPlainSelector(selector) || !isSafeDeclarations(declarations)) return null
                return styles to "$selector{$declarations}"
            }
            if (body.contains('{') || body.contains('}')) return null
            return classifySelector(body)
        }

        /** Adblock Plus and AdGuard extended CSS (`#?#`), mapped to uBlock Origin's operators. */
        private fun classifyExtendedCss(body: String): Pair<CosmeticFilters.Builder, String>? {
            if (body.contains('{') || body.contains('}')) return null
            return classifySelector(body)
        }

        /** Plain CSS or a procedural selector, with the Adblock Plus and AdGuard operator names mapped to uBlock Origin's. */
        private fun classifySelector(body: String): Pair<CosmeticFilters.Builder, String>? {
            val selector = body
                .replace(":-abp-has(", ":has(")
                .replace(":-abp-contains(", ":has-text(")
                .replace(":contains(", ":has-text(")
            if (UNSUPPORTED_PROCEDURAL.containsMatchIn(selector)) return null
            return if (PROCEDURAL_SELECTOR.containsMatchIn(selector)) procedural to selector else cosmetic to selector
        }

        /** AdGuard's CSS injection: `selector { declarations }`. */
        private fun classifyCssInjection(body: String): Pair<CosmeticFilters.Builder, String>? {
            val match = CSS_INJECTION.matchEntire(body) ?: return null
            val selector = match.groupValues[1].trim()
            val declarations = match.groupValues[2].trim()
            if (selector.isEmpty() || !isPlainSelector(selector) || !isSafeDeclarations(declarations)) return null
            return styles to "$selector{$declarations}"
        }

        /** AdGuard's `#%#//scriptlet('name', 'arg', ...)`; raw JavaScript rules are not run. */
        private fun classifyAdGuardScriptlet(body: String): Pair<CosmeticFilters.Builder, String>? {
            if (!body.startsWith("//scriptlet(") || !body.endsWith(")")) return null
            val args = splitScriptletArgs(body.substring("//scriptlet(".length, body.length - 1))
            if (args.isEmpty()) return null
            val name = args[0].removePrefix("ubo-")
            return scriptlets to (listOf(name) + args.drop(1)).joinToString(", ") { it.replace(",", "\\,") }
        }

        private fun isPlainSelector(selector: String): Boolean =
            !selector.contains('{') && !selector.contains('}') && !PROCEDURAL_SELECTOR.containsMatchIn(selector) &&
                !UNSUPPORTED_PROCEDURAL.containsMatchIn(selector)

        private fun isSafeDeclarations(declarations: String): Boolean =
            declarations.isNotEmpty() && !declarations.contains('{') && !declarations.contains('}') &&
                !declarations.contains("url(", ignoreCase = true) && !declarations.contains("expression", ignoreCase = true) &&
                !declarations.contains('\\')

        private class Options(
            val raw: String?,
            val thirdParty: Boolean?,
            val domains: DomainConstraint?,
            val targets: DomainConstraint?,
            val popup: Boolean,
            val document: Boolean,
            val requests: Boolean,
            val elemHide: Boolean,
            val genericHide: Boolean,
            val important: Boolean,
            val badFilter: Boolean,
            val redirect: Boolean
        ) {
            val appliesToRequests: Boolean get() = requests || (!popup && !document && !elemHide && !genericHide)
        }

        /** Returns null when the rule uses an option this engine does not support. */
        private fun parseOptions(text: String): Options? {
            val dollar = text.lastIndexOf('$')
            if (dollar < 0) return NO_OPTIONS
            val raw = text.substring(dollar + 1)
            if (!OPTIONS_SHAPE.matches(raw)) {
                // Option values with spaces or quotes (`csp=...`) are unsupported options, not a
                // literal `$` in the pattern.
                return if (OPTION_START.containsMatchIn(raw)) null else NO_OPTIONS
            }

            var thirdParty: Boolean? = null
            var domains: DomainConstraint? = null
            var targets: DomainConstraint? = null
            var popup = false
            var document = false
            var requests = false
            var elemHide = false
            var genericHide = false
            var important = false
            var badFilter = false
            var redirect = false
            for (option in raw.split(',')) {
                val negated = option.startsWith('~')
                val name = option.removePrefix("~").substringBefore('=').lowercase()
                val value = option.substringAfter('=', "")
                when (name) {
                    "third-party", "3p", "strict3p" -> thirdParty = !negated
                    "first-party", "1p", "strict1p" -> thirdParty = negated
                    "domain", "from" -> domains = DomainConstraint.parse(value, '|')
                    "to" -> targets = DomainConstraint.parse(value, '|')
                    "denyallow" -> targets = DomainConstraint.parse(
                        value.split('|').filter { it.isNotBlank() }.joinToString("|") { "~$it" }, '|'
                    )
                    "popup", "popunder" -> if (!negated) popup = true
                    "document", "doc" -> if (!negated) document = true
                    "all" -> {
                        popup = true
                        document = true
                        requests = true
                    }
                    "elemhide", "ehide", "specifichide", "shide" -> elemHide = true
                    "generichide", "ghide" -> genericHide = true
                    "important" -> important = true
                    "badfilter" -> badFilter = true
                    // Blocking with a stand-in resource: plain blocking is close enough.
                    "redirect", "rewrite", "empty", "mp4" -> redirect = true
                    "match-case" -> Unit
                    in RESOURCE_TYPES -> requests = true
                    else -> return null
                }
            }
            return Options(raw, thirdParty, domains, targets, popup, document, requests, elemHide, genericHide, important, badFilter, redirect)
        }

        private companion object {
            val NO_OPTIONS = Options(null, null, null, null, false, false, false, false, false, false, false, false)
        }
    }

    companion object {
        val EMPTY: FilterEngine = Builder().build()

        private val WHITESPACE = Regex("\\s+")
        private val OPTIONS_SHAPE = Regex("[A-Za-z0-9~,=|._*:-]+")
        private val OPTION_START = Regex("^~?[a-z0-9-]+(=|,|$)")
        private val COSMETIC_DOMAINS = Regex("[A-Za-z0-9.,~*_\\-\\u0080-\\uFFFF]*")
        private val COSMETIC_SEPARATORS = listOf("#@$?#", "#$?#", "#@%#", "#@$#", "#@?#", "#@#", "#%#", "#$#", "#?#", "##")
        private val PLAIN_HOST_RULE = Regex("\\|\\|([^/^*|$:]+)\\^")
        private val STYLE_SUFFIX = Regex(":style\\((.*)\\)$")
        private val CSS_INJECTION = Regex("(.+?)\\s*\\{(.*)\\}")

        /** Operators run by the page script; `:has()` and `:not()` only count with one of these inside. */
        private val PROCEDURAL_SELECTOR = Regex(
            ":(has-text|upward|remove|remove-attr|remove-class|xpath|matches-css|matches-css-before|" +
                "matches-css-after|matches-attr|matches-path|min-text-length|watch-attr|matches-media)\\("
        )
        private val UNSUPPORTED_PROCEDURAL = Regex(
            ":-abp-|:(others|if|if-not|nth-ancestor|matches-property|properties|matches-prop|shadow|is-visible)\\("
        )
        private val HOSTS_FILE_ADDRESSES = setOf("0.0.0.0", "127.0.0.1", "::", "::1")
        private val HOSTS_FILE_IGNORED = setOf("localhost", "localhost.localdomain", "local", "broadcasthost", "0.0.0.0")
        private val RESOURCE_TYPES = setOf(
            "script", "image", "stylesheet", "css", "object", "object-subrequest", "xmlhttprequest", "xhr",
            "subdocument", "frame", "ping", "beacon", "media", "font", "other", "websocket", "webrtc", "webbundle"
        )

        /** Preprocessor tokens that hold here: AABrowser behaves like uBlock Origin on mobile Chromium. */
        private val TRUE_CONDITIONS = setOf("ext_ublock", "env_chromium", "env_mobile")

        /** Evaluates `!#if` expressions with `!`, `&&`, `||` and parentheses; unknown tokens are false. */
        internal fun evaluateCondition(expression: String): Boolean {
            val tokens = Regex("&&|\\|\\||[!()]|[A-Za-z0-9_]+").findAll(expression).map { it.value }.toList()
            var position = 0
            fun or(): Boolean {
                fun unary(): Boolean {
                    val token = tokens.getOrNull(position++) ?: return false
                    return when (token) {
                        "!" -> !unary()
                        "(" -> or().also { if (tokens.getOrNull(position) == ")") position++ }
                        else -> token in TRUE_CONDITIONS
                    }
                }
                fun and(): Boolean {
                    var value = unary()
                    while (tokens.getOrNull(position) == "&&") {
                        position++
                        value = unary() && value
                    }
                    return value
                }
                var value = and()
                while (tokens.getOrNull(position) == "||") {
                    position++
                    value = and() || value
                }
                return value
            }
            return or()
        }

        private fun cosmeticSeparator(line: String, at: Int): String? =
            COSMETIC_SEPARATORS.firstOrNull { line.startsWith(it, at) }

        /** `name, arg1, arg2` with `\,` kept inside arguments and quotes around arguments removed. */
        internal fun splitScriptletArgs(text: String): List<String> {
            val args = ArrayList<String>()
            val current = StringBuilder()
            var quote: Char? = null
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    c == '\\' && i + 1 < text.length && (text[i + 1] == ',' || text[i + 1] == quote) -> {
                        current.append(text[i + 1])
                        i++
                    }
                    quote != null -> if (c == quote) quote = null else current.append(c)
                    (c == '\'' || c == '"' || c == '`') && current.isBlank() -> {
                        current.setLength(0)
                        quote = c
                    }
                    c == ',' -> {
                        args.add(current.toString().trim())
                        current.setLength(0)
                    }
                    else -> current.append(c)
                }
                i++
            }
            if (args.isNotEmpty() || current.isNotBlank()) args.add(current.toString().trim())
            return args
        }

        /** One spelling per scriptlet, so `#@#+js(...)` exceptions match however the rule was written. */
        private fun normalizeScriptlet(inner: String): String {
            val args = splitScriptletArgs(inner)
            if (args.isEmpty()) return ""
            return args.joinToString(", ") { it.replace(",", "\\,") }
        }

        private fun parseScriptlet(normalized: String): List<String>? {
            val args = splitScriptletArgs(normalized)
            val name = args.firstOrNull()?.removeSuffix(".js")?.takeIf { it.isNotEmpty() } ?: return null
            return listOf(name) + args.drop(1)
        }

        /** Approximates the registrable domain without a public suffix list. */
        internal fun siteOf(host: String): String {
            // Called for every request, so this avoids splitting the host into a list.
            val last = host.lastIndexOf('.')
            if (last <= 0) return host
            val second = host.lastIndexOf('.', last - 1)
            if (second < 0) return host
            val tldLength = host.length - last - 1
            val secondLabel = host.substring(second + 1, last)
            val keep = if (tldLength == 2 && (secondLabel.length <= 3 || secondLabel in SECOND_LEVEL_WORDS)) 3 else 2
            if (keep == 2) return host.substring(second + 1)
            val third = host.lastIndexOf('.', second - 1)
            return if (third < 0) host else host.substring(third + 1)
        }

        /** The public suffix part of [host] (`com`, `co.uk`), approximated like [siteOf]. */
        internal fun suffixOf(host: String): String {
            val site = siteOf(host)
            val dot = site.indexOf('.')
            return if (dot < 0) "" else site.substring(dot + 1)
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
internal class DomainConstraint(val includes: List<String>, private val excludes: List<String>) {

    fun matches(host: String?): Boolean {
        if (host == null) return includes.isEmpty()
        if (excludes.any { covers(it, host) }) return false
        return includes.isEmpty() || includes.any { covers(it, host) }
    }

    companion object {
        fun covers(domain: String, host: String): Boolean {
            if (domain.endsWith(".*")) {
                // `example.*`: example under any public suffix.
                val suffix = FilterEngine.suffixOf(host)
                if (suffix.isEmpty() || host.length <= suffix.length + 1) return false
                return covers(domain.substring(0, domain.length - 2), host.substring(0, host.length - suffix.length - 1))
            }
            return host == domain || (host.endsWith(domain) && host[host.length - domain.length - 1] == '.')
        }

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

/** What a network rule matches the URL with: an Adblock Plus pattern or a regular expression. */
internal sealed class RulePattern {
    class OfUrl(val pattern: UrlPattern) : RulePattern()
    class OfRegex(val regex: Regex) : RulePattern() {
        /** Text every match contains, checked before running the regular expression. */
        private val literal: String? = requiredLiteral(regex.pattern)

        fun matches(url: String): Boolean =
            (literal == null || url.contains(literal)) && regex.containsMatchIn(url)

        private companion object {
            /** The longest lowercase literal outside groups, or null if the pattern has `|` at its top level. */
            fun requiredLiteral(source: String): String? {
                var best = ""
                val current = StringBuilder()
                var depth = 0
                var inClass = false
                fun endRun() {
                    if (current.length > best.length) best = current.toString()
                    current.setLength(0)
                }
                var i = 0
                while (i < source.length) {
                    val c = source[i]
                    when {
                        c == '\\' && i + 1 < source.length -> {
                            val next = source[i + 1]
                            if (!inClass && depth == 0 && !next.isLetterOrDigit()) current.append(next.lowercaseChar()) else endRun()
                            i++
                        }
                        inClass -> if (c == ']') inClass = false
                        c == '[' -> { endRun(); inClass = true }
                        c == '(' -> { endRun(); depth++ }
                        c == ')' -> { endRun(); depth-- }
                        c == '|' -> if (depth == 0) return null
                        c == '?' || c == '*' || c == '{' -> {
                            // The quantified character is optional or repeated.
                            if (current.isNotEmpty()) current.setLength(current.length - 1)
                            endRun()
                            if (c == '{') while (i < source.length && source[i] != '}') i++
                        }
                        c == '+' || c == '.' || c == '^' || c == '$' -> endRun()
                        depth == 0 -> current.append(c.lowercaseChar())
                        else -> Unit
                    }
                    i++
                }
                endRun()
                return best.takeIf { it.length >= 3 }
            }
        }
    }
}

internal class NetworkRule(
    private val pattern: RulePattern,
    private val thirdParty: Boolean?,
    private val domains: DomainConstraint?,
    /** `to=` / `denyallow=`: constraints on the requested host itself. */
    private val targets: DomainConstraint? = null,
    /** Hash of the rule's text, which `$badfilter` rules refer to. */
    val key: Long = 0L
) {
    private val urlPattern: UrlPattern? = (pattern as? RulePattern.OfUrl)?.pattern

    /** The pattern without anchors; empty for regular expressions, which are never indexed. */
    val body: String get() = urlPattern?.body ?: ""
    val isHostAnchored: Boolean get() = urlPattern?.isHostAnchored == true
    val isUnconstrained: Boolean get() = thirdParty == null && domains == null && targets == null

    fun isWholeToken(start: Int, end: Int): Boolean = urlPattern?.isWholeToken(start, end) == true

    /** The sites a `domain=` rule is limited to, when they can be looked up directly. */
    val pageDomains: List<String>?
        get() = domains?.includes?.takeIf { list -> list.isNotEmpty() && list.none { it.endsWith(".*") } }

    fun matches(request: Request): Boolean {
        if (thirdParty != null && request.isThirdParty != thirdParty) return false
        if (domains != null && !domains.matches(request.page?.host)) return false
        if (targets != null && !targets.matches(request.host)) return false
        return when (pattern) {
            is RulePattern.OfUrl -> pattern.pattern.matches(request.url, request.hostStart, request.hostEnd)
            is RulePattern.OfRegex -> pattern.matches(request.url)
        }
    }
}

/** Indexes rules by host (for `||host^...` rules) and by a token from their pattern. */
internal class RuleIndex private constructor(
    private val plainHosts: Set<String>,
    private val byHost: Map<String, List<NetworkRule>>,
    private val byToken: Map<String, List<NetworkRule>>,
    /** Rules without a usable token that only apply on some sites, by those sites. */
    private val byPageDomain: Map<String, List<NetworkRule>>,
    private val unindexed: List<NetworkRule>
) {

    fun matches(request: Request): Boolean {
        if (matchesHost(request)) return true
        if (unindexed.any { it.matches(request) }) return true
        if (byPageDomain.isNotEmpty() && request.page != null) {
            var candidate = request.page.host
            while (true) {
                if (byPageDomain[candidate]?.any { it.matches(request) } == true) return true
                val dot = candidate.indexOf('.')
                if (dot < 0) break
                candidate = candidate.substring(dot + 1)
            }
        }
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
        private val byPageDomain = HashMap<String, MutableList<NetworkRule>>()
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
            val pageDomains = rule.pageDomains
            when {
                token != null -> byToken.getOrPut(token) { ArrayList(1) }.add(rule)
                pageDomains != null -> pageDomains.forEach { byPageDomain.getOrPut(it) { ArrayList(1) }.add(rule) }
                else -> unindexed.add(rule)
            }
        }

        /** Builds the index without the rules turned off by `$badfilter` rules. */
        fun build(badFilters: Set<Long> = emptySet(), badHosts: Set<String> = emptySet()): RuleIndex {
            plainHosts.removeAll(badHosts)
            if (badFilters.isEmpty()) return RuleIndex(plainHosts, byHost, byToken, byPageDomain, unindexed)
            fun List<NetworkRule>.kept() = filter { it.key !in badFilters }
            return RuleIndex(
                plainHosts,
                byHost.mapValues { it.value.kept() },
                byToken.mapValues { it.value.kept() },
                byPageDomain.mapValues { it.value.kept() },
                unindexed.kept()
            )
        }

        private fun NetworkRule.isPlainHostRule(host: String): Boolean = isUnconstrained && body == "$host^"

        /**
         * The token a matching URL must contain: the longest whole token of the pattern, preferring
         * rare ones. Short or common tokens (`a` in `/a.gif?`, `gif`) still beat leaving the rule
         * unindexed, where it would be checked against every request.
         */
        private fun bestToken(rule: NetworkRule): String? {
            val body = rule.body
            var best: String? = null
            var bestScore = 0
            var i = 0
            while (i < body.length) {
                if (!UrlPattern.isTokenChar(body[i])) {
                    i++
                    continue
                }
                val start = i
                while (i < body.length && UrlPattern.isTokenChar(body[i])) i++
                val token = body.substring(start, i)
                if (rule.isWholeToken(start, i)) {
                    val score = if (token in COMMON_TOKENS) 1 else 2 + minOf(token.length, 64)
                    if (score > bestScore) {
                        best = token
                        bestScore = score
                    }
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

    /** Whether [selector] is turned off for [host] by an exception rule. */
    fun isExcepted(host: String, selector: String): Boolean =
        selector in globalExceptions || selector in exceptionsFor(host)

    private fun exceptionsFor(host: String): Set<String> {
        val result = HashSet<String>()
        forEachSuffix(host) { suffix -> specificExceptions[suffix]?.let(result::addAll) }
        return result
    }

    /** Calls [action] with the host, its parent domains and their `example.*` forms. */
    private inline fun forEachSuffix(host: String, action: (String) -> Unit) {
        val publicSuffix = FilterEngine.suffixOf(host)
        var candidate = host
        while (true) {
            action(candidate)
            if (publicSuffix.isNotEmpty() && candidate.length > publicSuffix.length + 1 && candidate.endsWith(".$publicSuffix")) {
                action(candidate.substring(0, candidate.length - publicSuffix.length) + "*")
            }
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
