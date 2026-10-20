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

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url
        if (request.isForMainFrame && blockAdNavigation(view, uri?.toString())) {
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

    private fun handleUri(uri: Uri?): Boolean {
        if (uri == null) return false
        val scheme = uri.scheme?.lowercase()
        if (scheme == null || scheme in setOf("http", "https", "about", "file", "data", "javascript")) {
            return false
        }
        return true
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
            return AdBlocker.blockedResponse()
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

        if (scheme == "http") {
            val allowedOnce = view.getTag(R.id.webview_allow_once_uri_tag) as? String
            if (allowedOnce == stringUrl) {
                view.setTag(R.id.webview_allow_once_uri_tag, null)
            } else if (CleartextNavigationHandler.handleCleartextIfNeeded(view, uri, callbacks, onPageStart = true)) {
                return
            }
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
                val assetUrl = "file:///android_asset/error.html?failedUrl=${Uri.encode(failed)}&code=$code&message=${Uri.encode(message)}"
                try {
                    view.loadUrl(assetUrl)
                } catch (_: Exception) {
                    callbacks.onError(code, error.description?.toString())
                }
                return
            }
        }
        callbacks.onError(error.errorCode, error.description?.toString())
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
        if (request.isForMainFrame) {
            val code = errorResponse.statusCode
            if (code in 400..599 && code != 429) {
                val failed = request.url?.toString().orEmpty()
                val message = errorResponse.reasonPhrase.orEmpty()
                val assetUrl = "file:///android_asset/error.html?failedUrl=${Uri.encode(failed)}&code=$code&message=${Uri.encode(message)}"
                try {
                    view.loadUrl(assetUrl)
                } catch (_: Exception) {
                    callbacks.onError(code, message)
                }
                return
            }
        }
    }

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
