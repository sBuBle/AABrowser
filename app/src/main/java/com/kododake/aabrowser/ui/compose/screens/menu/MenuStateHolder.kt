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

package com.kododake.aabrowser.ui.compose.screens.menu

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Observable UI state holder for the Compose browser menu sheet.
 */
class MenuStateHolder {
    var url by mutableStateOf("")
    var pageTitle by mutableStateOf("")
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var canReload by mutableStateOf(false)
    var canQrCode by mutableStateOf(false)
    var isDesktopMode by mutableStateOf(false)
    var isFullscreenMode by mutableStateOf(false)

    /** Site shown in the ad blocker card; null hides the card (start page, non-web pages). */
    var adBlockSite by mutableStateOf<String?>(null)
    var isAdBlockerEnabled by mutableStateOf(true)
    var isAdBlockActiveOnSite by mutableStateOf(true)
    var adsBlockedCount by mutableStateOf(0)
    var versionName by mutableStateOf("unknown")
    var isMenuVisible by mutableStateOf(false)
    var isReturningFromSubscreen by mutableStateOf(false)
    var isFabVisible by mutableStateOf(false)

    /** The phone's bottom toolbar is shown; it replaces the floating menu button. */
    var isToolbarVisible by mutableStateOf(false)
    var isOnStartPage by mutableStateOf(false)

    /** Incremented to focus the menu's address field when the menu opens. */
    var addressFocusRequest by mutableIntStateOf(0)
    var fabIsAddressBarMode by mutableStateOf(false)

    fun updateNavigationState(
        canBack: Boolean,
        canForward: Boolean,
        canRel: Boolean,
        desktop: Boolean,
        canQr: Boolean = false,
        onStartPage: Boolean = false
    ) {
        isOnStartPage = onStartPage
        canGoBack = canBack
        canGoForward = canForward
        canReload = canRel
        isDesktopMode = desktop
        canQrCode = canQr
    }

    fun updatePageInfo(newUrl: String, title: String? = null) {
        val decoded = Uri.decode(newUrl)
        if (url != decoded) {
            url = decoded
        }
        if (title != null && pageTitle != title) {
            pageTitle = title
        }
    }
}
