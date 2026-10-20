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

import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.ClientCertRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewFeature
import com.kododake.aabrowser.R
import com.kododake.aabrowser.data.BrowserPreferences
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class BrowserWebViewClient(
    private val callbacks: BrowserCallbacks
) : WebViewClient() {

    /** URL of the page shown in this WebView; read from WebView's network threads. */
    @Volatile
    private var pageUrl: String? = null

    /** Requests and redirects blocked on the current page; counted on WebView's network threads. */
    private val blockedCount = AtomicInteger()
    private val blockedCountUpdatePending = AtomicBoolean()

    /** Whether the page being started was loaded from the network, which already reset [blockedCount]. */
    @Volatile
    private var mainFrameRequested = false

    /** When the current page started loading, to tell redirect chains from later redirects. */
    private var pageStartedAt = 0L

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url
        if (request.isForMainFrame && blockAdNavigation(view, uri?.toString())) {
            return true
        }
        if (request.isForMainFrame && blockRedirect(view, request)) {
            return true
        }
        if (CleartextNavigationHandler.handleCleartextIfNeeded(view, uri, callbacks, onPageStart = false)) {
            return true
        }
        return handleUri(uri)
    }

    /**
     * Stops "jumping windows": a pop-up window that opens an ad, or a page that sends itself to an
     * ad or redirect network. Pop-up windows are closed again and the user is returned to the page
     * that opened them.
     */
    private fun blockAdNavigation(view: WebView, url: String?): Boolean {
        if (url == null || !BrowserPreferences.isHttpOrHttps(url)) return false
        val openerUrl = view.getTag(R.id.webview_popup_opener_url_tag) as? String
        val isPopup = openerUrl != null
        if (!AdBlocker.shouldBlockNavigation(url, if (isPopup) openerUrl else pageUrl, isPopup)) return false
        view.stopLoading()
        if (isPopup) {
            view.setTag(R.id.webview_popup_opener_url_tag, null)
            callbacks.onPopupBlocked()
        } else {
            blockedCount.incrementAndGet()
            publishBlockedCount(view)
            callbacks.onAdRedirectBlocked()
        }
        return true
    }

    /**
     * Stops a page from sending the tab to another site unless a link was tapped. Redirects within
     * the first moments of a page load pass: link shorteners, sign-in and consent hops.
     */
    private fun blockRedirect(view: WebView, request: WebResourceRequest): Boolean {
        val target = request.url?.toString() ?: return false
        if (request.isRedirect || !BrowserPreferences.isHttpOrHttps(target)) return false
        if (view.getTag(R.id.webview_popup_opener_url_tag) != null) return false
        if (request.hasGesture()) {
            if (RedirectBlocker.isLinkTap(view)) return false
        } else if (SystemClock.uptimeMillis() - pageStartedAt < REDIRECT_CHAIN_WINDOW_MS) {
            return false
        }
        val from = view.url ?: pageUrl
        if (!RedirectBlocker.shouldBlockRedirect(from, target)) return false
        blockedCount.incrementAndGet()
        publishBlockedCount(view)
        callbacks.onRedirectBlocked(from, target)
        return true
    }

    /**
     * Lets web content navigate only to web schemes. Like Chrome, pages may not open local files or
     * top-level `data:` URLs (a common phishing trick); the app itself loads those with loadUrl,
     * which never passes through here.
     */
    private fun handleUri(uri: Uri?): Boolean {
        if (uri == null) return false
        val scheme = uri.scheme?.lowercase() ?: return false
        return scheme !in PAGE_NAVIGABLE_SCHEMES
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        if (request.isForMainFrame) {
            pageUrl = request.url?.toString()
            mainFrameRequested = true
            blockedCount.set(0)
            publishBlockedCount(view)
        }
        if (AdBlocker.shouldBlock(request, pageUrl)) {
            blockedCount.incrementAndGet()
            publishBlockedCount(view)
            return AdBlocker.blockedResponse(request)
        }
        return super.shouldInterceptRequest(view, request)
    }

    /** Hands the count to the UI; coalesced, since a page can block hundreds of requests at once. */
    private fun publishBlockedCount(view: WebView) {
        if (!blockedCountUpdatePending.compareAndSet(false, true)) return
        view.post {
            blockedCountUpdatePending.set(false)
            val count = blockedCount.get()
            view.setTag(R.id.webview_ads_blocked_count_tag, count)
            callbacks.onAdsBlockedChanged(count)
        }
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        val stringUrl = url ?: return
        pageUrl = stringUrl
        pageStartedAt = SystemClock.uptimeMillis()
        // Pages restored from the back/forward cache never pass through shouldInterceptRequest.
        if (!mainFrameRequested) {
            blockedCount.set(0)
            publishBlockedCount(view)
        }
        mainFrameRequested = false
        // A pop-up's first page load does not always pass through shouldOverrideUrlLoading.
        if (view.getTag(R.id.webview_popup_opener_url_tag) != null && blockAdNavigation(view, stringUrl)) {
            return
        }
        val uri = Uri.parse(stringUrl)
        val scheme = uri.scheme?.lowercase()

        CleartextNavigationHandler.onMainFramePageStarted(view, uri)
        if (scheme == "http" && CleartextNavigationHandler.handleCleartextIfNeeded(view, uri, callbacks, onPageStart = true)) {
            return
        }
    }

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        if (url != null && BrowserPreferences.isHttpOrHttps(url)) {
            // The pop-up made it to a real page; from here on it is an ordinary tab.
            view.setTag(R.id.webview_popup_opener_url_tag, null)
        }
        view.evaluateJavascript(SpeechRecognitionBridge.POLYFILL_JS, null)
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            if (BrowserPreferences.isDrmL3EnforcerEnabled(view.context)) {
                view.evaluateJavascript(WebScripts.DRM_L3_ENFORCER_JS, null)
            }
            if (AdBlocker.isEnabled) {
                view.evaluateJavascript(WebScripts.AD_BLOCK_COSMETIC_JS, null)
            }
            view.evaluateJavascript(WebScripts.MEDIA_STREAM_RECOVERY_JS, null)
            view.evaluateJavascript(WebScripts.MEDIA_BUFFER_TUNING_JS, null)
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.NAVIGATION_LISTENER)) {
            url?.let(callbacks.onUrlChange)
        }
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame) {
            val code = error.errorCode
            val shouldShowErrorPage = when (code) {
                ERROR_HOST_LOOKUP,
                ERROR_CONNECT,
                ERROR_TIMEOUT,
                ERROR_UNKNOWN,
                ERROR_PROXY_AUTHENTICATION -> true
                else -> false
            }

            if (shouldShowErrorPage) {
                val failed = request.url?.toString().orEmpty()
                val message = error.description?.toString().orEmpty()
                try {
                    view.loadUrl(ErrorPage.urlFor(failed, code, message))
                } catch (_: Exception) {
                    callbacks.onError(code, error.description?.toString())
                }
                return
            }
            callbacks.onError(error.errorCode, error.description?.toString())
        }
        // Sub-resource errors (blocked ads among them) are the page's business, not the user's.
    }

    // HTTP error statuses (404, 500, ...) are deliberately not replaced with the error page: like
    // any browser, show the page the server sent. It explains the problem better, and bot checks
    // such as Cloudflare's are served as 403 pages that must stay usable.

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        val activity = view.context as? Activity
        if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
            SslErrorHandlerHelper.handleSslError(activity, handler, error)
        } else {
            handler.cancel()
        }
    }

    override fun onReceivedClientCertRequest(view: WebView, request: ClientCertRequest) {
        val activity = view.context as? Activity
        if (activity != null) {
            ClientCertHandler.handleClientCertRequest(activity, request)
        } else {
            request.cancel()
        }
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        val parent = view.parent as? ViewGroup
        parent?.removeView(view)
        view.destroy()
        callbacks.onRenderProcessGone(detail.didCrash())
        return true
    }
}

/** How long after a page starts loading it may still redirect on its own (shorteners, sign-in hops). */
private const val REDIRECT_CHAIN_WINDOW_MS = 3_000L

private val PAGE_NAVIGABLE_SCHEMES = setOf("http", "https", "about", "javascript")
