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

package com.kododake.aabrowser.tabs

import android.app.Activity
import android.content.Context

import android.net.Uri
import android.os.SystemClock

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.kododake.aabrowser.data.BrowserPreferences
import com.kododake.aabrowser.ui.compose.components.BrowserLayout
import com.kododake.aabrowser.web.AdBlocker
import com.kododake.aabrowser.web.BrowserCallbacks
import com.kododake.aabrowser.web.ScrollJumpGuard
import com.kododake.aabrowser.web.SpeechRecognitionBridge
import com.kododake.aabrowser.web.TouchGlitchFilter
import com.kododake.aabrowser.web.configureWebView
import kotlin.math.hypot

/**
 * Factory for instantiating and configuring WebView instances and their associated BrowserTab wrappers.
 */
object BrowserTabFactory {

    fun createTab(
        context: Context,
        tabId: Long,
        initialUrl: String?,
        initialTitle: String,
        activate: Boolean,
        createBrowserCallbacks: (BrowserTab) -> BrowserCallbacks,
        onRequestSpeechMicrophone: (tabId: Long, pageUrl: String?) -> Unit,
        onSanitizeJsExternalUrl: (sourceWebView: android.webkit.WebView, rawUrl: String?) -> Uri?,
        onOpenUriExternally: (Uri) -> Unit,
        onShowMenuButtonTemporarily: () -> Unit
    ): BrowserTab {
        val tabView = android.webkit.WebView(context).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            overScrollMode = View.OVER_SCROLL_NEVER
            visibility = View.GONE
        }

        lateinit var tab: BrowserTab
        val speechBridge = SpeechRecognitionBridge(tabView) { pageUrl ->
            onRequestSpeechMicrophone(tab.id, pageUrl)
        }

        tab = BrowserTab(
            id = tabId,
            webView = tabView,
            speechBridge = speechBridge,
            currentUrl = initialUrl.orEmpty(),
            currentTitle = initialTitle,
            isActive = activate
        )

        configureWebView(
            tabView,
            createBrowserCallbacks(tab),
            BrowserPreferences.shouldUseDesktopMode(context),
            BrowserPreferences.getUserAgentProfile(context),
            BrowserPreferences.isDrmL3EnforcerEnabled(context),
            BrowserPreferences.isAdBlockerEnabled(context)
        )
        setupWebMessageListener(tabView, speechBridge)
        setupJavascriptInterface(context, tabView, onSanitizeJsExternalUrl, onOpenUriExternally)

        setupTouchStabilization(context, tabView, onShowMenuButtonTemporarily)
        tabView.onPause()

        return tab
    }

    /**
     * Cleans up the touch stream on car displays (Android Auto), where scrolling and lifting the
     * finger could throw the page back to the top; the phone's own screen is left untouched.
     *
     * On the car display, stray samples are dropped ([TouchGlitchFilter]), and a scroll ends with a
     * fling computed from trusted samples only, capped and never against the scroll direction,
     * instead of WebView's own fling from the raw lift-off. [ScrollJumpGuard] undoes any leap back
     * that still happens.
     */
    private fun setupTouchStabilization(
        context: Context,
        webView: android.webkit.WebView,
        onShowMenuButtonTemporarily: () -> Unit
    ) {
        val config = ViewConfiguration.get(context)
        val touchSlop = config.scaledTouchSlop
        val maxFlingPxPerMs = config.scaledMaximumFlingVelocity / 1000f
        val minFlingPxPerMs = config.scaledMinimumFlingVelocity / 1000f
        val touchFilter = TouchGlitchFilter(
            maxSpeedPxPerMs = maxFlingPxPerMs * 2f,
            minGlitchDistancePx = touchSlop * 4f
        )
        val jumpGuard = ScrollJumpGuard()
        var onCarDisplay = false

        webView.setOnTouchListener { view, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                // Only react to the start of a gesture: doing this for every move event
                // re-posted the menu button timers and recomposed it while scrolling.
                onShowMenuButtonTemporarily()
                onCarDisplay = BrowserLayout.isCarDisplay(view)
            }
            if (!onCarDisplay) return@setOnTouchListener false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchFilter.onDown(event.x, event.y, event.eventTime)
                    jumpGuard.onTouchDown(view.scrollY)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount == 1 && !acceptMoveSamples(touchFilter, event)) {
                        return@setOnTouchListener true
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (touchFilter.hasScrolled(touchSlop.toFloat())) {
                        val (vx, vy) = touchFilter.flingVelocity(maxFlingPxPerMs)
                        touchFilter.reset()
                        // End the scroll without WebView's own fling, then fling ourselves.
                        event.action = MotionEvent.ACTION_CANCEL
                        if (hypot(vx, vy) >= minFlingPxPerMs) {
                            // flingScroll takes scroll velocity: the page scrolls opposite to the finger.
                            webView.post { webView.flingScroll((-vx * 1000f).toInt(), (-vy * 1000f).toInt()) }
                        }
                    } else {
                        touchFilter.resolveUp(event.x, event.y, event.eventTime)?.let { (x, y) ->
                            event.setLocation(x, y)
                        }
                    }
                    jumpGuard.onGestureEnd(view.scrollY, event.eventTime, view.height, touchSlop)
                }
                MotionEvent.ACTION_POINTER_DOWN -> touchFilter.reset()
                MotionEvent.ACTION_CANCEL -> {
                    touchFilter.reset()
                    jumpGuard.onGestureEnd(view.scrollY, event.eventTime, view.height, touchSlop)
                }
            }
            false
        }

        webView.setOnScrollChangeListener { _, scrollX, scrollY, _, _ ->
            jumpGuard.onScrollChanged(scrollY, SystemClock.uptimeMillis())?.let { restoreY ->
                // Stop the bogus fling first so it does not keep dragging the page away.
                webView.flingScroll(0, 0)
                webView.scrollTo(scrollX, restoreY)
            }
        }
    }

    /** Batched move events carry earlier samples too; a glitch hidden among them also counts. */
    private fun acceptMoveSamples(filter: TouchGlitchFilter, event: MotionEvent): Boolean {
        for (i in 0 until event.historySize) {
            if (!filter.acceptMove(event.getHistoricalX(i), event.getHistoricalY(i), event.getHistoricalEventTime(i))) {
                return false
            }
        }
        return filter.acceptMove(event.x, event.y, event.eventTime)
    }

    private fun setupWebMessageListener(webView: android.webkit.WebView, speechBridge: SpeechRecognitionBridge) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(
                webView,
                SpeechRecognitionBridge.BRIDGE_OBJECT_NAME,
                setOf("*")
            ) { webViewInstance, message, sourceOrigin, isMainFrame, _ ->
                speechBridge.handleWebMessage(message, sourceOrigin, isMainFrame, webViewInstance.url)
            }
        }
    }

    private fun setupJavascriptInterface(
        context: Context,
        webView: android.webkit.WebView,
        onSanitizeJsExternalUrl: (android.webkit.WebView, String?) -> Uri?,
        onOpenUriExternally: (Uri) -> Unit
    ) {
        webView.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun openExternal(url: String) {
                (context as? Activity)?.runOnUiThread {
                    val safeUri = onSanitizeJsExternalUrl(webView, url)
                    if (safeUri != null) {
                        onOpenUriExternally(safeUri)
                    }
                }
            }
        }, "Android")
        webView.addJavascriptInterface(AdBlocker.CosmeticBridge(), "AABAdBlock")
    }
}
