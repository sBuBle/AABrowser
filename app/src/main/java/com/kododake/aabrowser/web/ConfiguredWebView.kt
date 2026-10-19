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

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Message
import android.os.SystemClock
import android.view.View
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.webkit.Navigation
import androidx.webkit.NavigationListener
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.kododake.aabrowser.R
import com.kododake.aabrowser.model.UserAgentProfile

fun configureWebView(
    webView: WebView,
    callbacks: BrowserCallbacks = BrowserCallbacks(),
    useDesktopMode: Boolean = false,
    userAgentProfile: UserAgentProfile = UserAgentProfile.ANDROID_CHROME,
    enableDrmL3Enforcer: Boolean = true,
    enableAdBlocker: Boolean = true
) {
    with(webView) {
        setBackgroundColor(Color.TRANSPARENT)

        AdBlocker.initialize(context)

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            if (enableDrmL3Enforcer) {
                val handler = WebViewCompat.addDocumentStartJavaScript(this, WebScripts.DRM_L3_ENFORCER_JS, setOf("*"))
                setTag(R.id.webview_drm_l3_script_handler_tag, handler)
            }
            if (enableAdBlocker) {
                val handler = WebViewCompat.addDocumentStartJavaScript(this, WebScripts.AD_BLOCK_COSMETIC_JS, setOf("*"))
                setTag(R.id.webview_ad_block_script_handler_tag, handler)
            }
            WebViewCompat.addDocumentStartJavaScript(this, WebScripts.MEDIA_STREAM_RECOVERY_JS, setOf("*"))
        }

        isHorizontalScrollBarEnabled = false
        isVerticalScrollBarEnabled = true

        WebView.setWebContentsDebuggingEnabled(false)

        val originalUserAgent = settings.userAgentString
        setTag(R.id.webview_original_user_agent_tag, originalUserAgent)

        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true

            setSupportMultipleWindows(true)

            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            allowContentAccess = true
            allowFileAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }
            // Raster tiles ahead of scrolling only for the visible tab (see setForegroundTab):
            // enabling it on every tab multiplies GPU memory use and starves video playback.
            offscreenPreRaster = false
            // Car displays driven by a rotary knob or touchpad are often not in touch mode. In that
            // mode WebView moves focus to the first element on the page whenever it regains focus
            // (after the menu button hides, an overlay closes, a tab switch), which scrolls the
            // page back to the top while the user is reading further down.
            setNeedInitialFocus(false)
        }

        UserAgentManager.applyBrowserIdentity(this, userAgentProfile, useDesktopMode)

        CookieManager.getInstance().also {
            it.setAcceptCookie(true)
            it.setAcceptThirdPartyCookies(this, true)
        }

        webViewClient = BrowserWebViewClient(callbacks)


        webChromeClient = object : WebChromeClient() {
            private var lastPopupAt = 0L

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                callbacks.onProgressChange(newProgress)
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                callbacks.onTitleChange(title)
            }

            override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
                super.onReceivedIcon(view, icon)
                val pageUrl = view?.url?.takeIf { it.isNotBlank() } ?: return
                callbacks.onFaviconReceived(pageUrl, icon)
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view != null && callback != null) {
                    callbacks.onEnterFullscreen(view, callback)
                } else {
                    super.onShowCustomView(view, callback)
                }
            }

            override fun onHideCustomView() {
                callbacks.onExitFullscreen()
                super.onHideCustomView()
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) {
                    return
                }

                val allowed = setOf(
                    PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID,
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE
                )

                val grantable = request.resources.filter { it in allowed }.toTypedArray()

                if (grantable.isEmpty()) {
                    request.deny()
                    return
                }

                if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in grantable) {
                    callbacks.onPermissionRequest(request)
                } else {
                    this@with.post { request.grant(grantable) }
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: android.webkit.GeolocationPermissions.Callback?
            ) {
                callbacks.onGeolocationPermissionRequest(origin, callback)
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                if (resultMsg == null) return false
                if (AdBlocker.isEnabled) {
                    // Pop-ups nobody tapped for, and the extra windows pop-under scripts open on
                    // every tap, are refused outright.
                    if (!isUserGesture) return false
                    val now = SystemClock.uptimeMillis()
                    if (now - lastPopupAt < POPUP_COOLDOWN_MS) return false
                    lastPopupAt = now
                }
                val newWebView = callbacks.onCreateNewWindow()
                if (newWebView != null) {
                    val transport = resultMsg.obj as? WebView.WebViewTransport
                    if (transport != null) {
                        // Lets the new window's WebViewClient vet where the pop-up goes.
                        newWebView.setTag(R.id.webview_popup_opener_url_tag, view?.url.orEmpty())
                        transport.webView = newWebView
                        resultMsg.sendToTarget()
                        return true
                    }
                } else {
                    val hitTest = view?.hitTestResult
                    val extraUrl = hitTest?.extra
                    if (!extraUrl.isNullOrBlank() && !AdBlocker.shouldBlockNavigation(extraUrl, view.url, isPopup = true)) {
                        view.loadUrl(extraUrl)
                        return true
                    }
                }
                return false
            }
        }

        setDownloadListener(DownloadListener { url, _, _, _, _ ->
            val uri = url?.takeIf { it.isNotBlank() }?.toUri() ?: return@DownloadListener
            callbacks.onShowDownloadPrompt(uri)
        })

        if (WebViewFeature.isFeatureSupported(WebViewFeature.NAVIGATION_LISTENER)) {
            WebViewCompat.addNavigationListener(
                this,
                ContextCompat.getMainExecutor(context),
                object : NavigationListener {
                    override fun onNavigationStarted(navigation: Navigation) {}
                    override fun onNavigationCompleted(navigation: Navigation) {
                        val navUrl = navigation.url.takeIf { it.isNotBlank() } ?: navigation.page?.url
                        navUrl?.let(callbacks.onUrlChange)
                    }
                }
            )
        }
    }
}

/**
 * Marks this tab as the visible one (or not) so only it pre-rasters content off screen for smooth
 * scrolling, keeping background tabs from competing with it for memory and GPU time.
 */
fun WebView.setForegroundTab(foreground: Boolean) {
    settings.offscreenPreRaster = foreground
}

fun WebView.updateDesktopMode(enable: Boolean, profile: UserAgentProfile) {
    UserAgentManager.applyBrowserIdentity(this, profile, enable)
    reload()
}

fun WebView.updateUserAgentProfile(profile: UserAgentProfile, desktop: Boolean) {
    UserAgentManager.applyBrowserIdentity(this, profile, desktop)
    reload()
}

fun WebView.updateAdBlocker(enabled: Boolean, reload: Boolean = true) {
    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
        val existingHandler = getTag(R.id.webview_ad_block_script_handler_tag) as? ScriptHandler
        if (enabled) {
            if (existingHandler == null) {
                val handler = WebViewCompat.addDocumentStartJavaScript(this, WebScripts.AD_BLOCK_COSMETIC_JS, setOf("*"))
                setTag(R.id.webview_ad_block_script_handler_tag, handler)
            }
        } else {
            existingHandler?.remove()
            setTag(R.id.webview_ad_block_script_handler_tag, null)
        }
    }
    if (reload) {
        reload()
    }
}

/**
 * Propagates the device's connectivity to the page (`navigator.onLine` and `online`/`offline`
 * events, which media players use to pause and resume streams) and serves cached copies while
 * offline so back/forward navigation keeps working without a connection.
 */
fun WebView.applyNetworkAvailability(available: Boolean) {
    setNetworkAvailable(available)
    settings.cacheMode = if (available) WebSettings.LOAD_DEFAULT else WebSettings.LOAD_CACHE_ELSE_NETWORK
}

fun WebView.releaseCompletely() {
    stopLoading()
    (parent as? android.view.ViewGroup)?.removeView(this)
    removeAllViews()
    webChromeClient = null
    webViewClient = WebViewClient()
    destroy()
}

fun WebView.updateDrmL3Enforcer(enabled: Boolean, reload: Boolean = true) {
    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
        val existingHandler = getTag(R.id.webview_drm_l3_script_handler_tag) as? ScriptHandler
        if (enabled) {
            if (existingHandler == null) {
                val handler = WebViewCompat.addDocumentStartJavaScript(this, WebScripts.DRM_L3_ENFORCER_JS, setOf("*"))
                setTag(R.id.webview_drm_l3_script_handler_tag, handler)
            }
        } else {
            existingHandler?.remove()
            setTag(R.id.webview_drm_l3_script_handler_tag, null)
        }
    }
    if (reload) {
        reload()
    }
}

private const val POPUP_COOLDOWN_MS = 1500L
