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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kododake.aabrowser.ui.compose.components.ExpressiveBottomSheetContainer
import com.kododake.aabrowser.ui.compose.components.rememberBrowserLayout

@Composable
fun BrowserMenuSheet(
    stateHolder: MenuStateHolder,
    actions: MenuActions,
    modifier: Modifier = Modifier
) {
    val isReturning = stateHolder.isReturningFromSubscreen
    if (isReturning) {
        stateHolder.isReturningFromSubscreen = false
    }

    ExpressiveBottomSheetContainer(
        isVisible = stateHolder.isMenuVisible,
        onDismissRequest = actions.onClose,
        onDismissFinished = actions.onDismissFinished,
        onProgress = actions.onProgress,
        isReturning = isReturning,
        modifier = modifier
    ) {
        val layout = rememberBrowserLayout()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = layout.screenHeight * 0.92f)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (layout.isWideAndShort) {
                // Car displays and phones in landscape: a slim header that also holds back/forward/
                // reload and the footer's buttons, and compact shortcuts side by side with the page
                // switches, so the whole menu fits a short screen without scrolling.
                MenuHeaderComponent(
                    pageTitle = stateHolder.pageTitle,
                    onClose = actions.onClose,
                    height = 64.dp,
                    trailing = {
                        MenuNavigationRow(
                            canGoBack = stateHolder.canGoBack,
                            canGoForward = stateHolder.canGoForward,
                            canReload = stateHolder.canReload,
                            onBack = actions.onBack,
                            onForward = actions.onForward,
                            onReload = actions.onReload,
                            modifier = Modifier.width(180.dp),
                            buttonHeight = 52.dp
                        )
                        Spacer(Modifier.width(12.dp))
                        MenuUpdateButtons(onCheckUpdate = actions.onCheckUpdate, onGitHub = actions.onGitHub)
                        Spacer(Modifier.width(4.dp))
                    }
                )

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        MenuBrowsingControls(stateHolder, actions, compact = true)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        MenuPageOptions(stateHolder, actions, showFooter = false)
                    }
                }
            } else {
                MenuHeaderComponent(
                    pageTitle = stateHolder.pageTitle,
                    onClose = actions.onClose
                )

                Spacer(Modifier.height(12.dp))

                MenuBrowsingControls(stateHolder, actions)

                Spacer(Modifier.height(10.dp))

                MenuPageOptions(stateHolder, actions)
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Address bar, back/forward/reload and the shortcuts grid. */
@Composable
private fun MenuBrowsingControls(stateHolder: MenuStateHolder, actions: MenuActions, compact: Boolean = false) {
    if (compact) {
        // Back/forward/reload are in the header on short screens.
        MenuAddressBar(
            url = stateHolder.url,
            onNavigate = actions.onNavigate,
            focusRequestKey = stateHolder.addressFocusRequest
        )
    } else {
        MenuAddressBar(
            url = stateHolder.url,
            onNavigate = actions.onNavigate,
            focusRequestKey = stateHolder.addressFocusRequest
        )

        Spacer(Modifier.height(12.dp))

        MenuNavigationRow(
            canGoBack = stateHolder.canGoBack,
            canGoForward = stateHolder.canGoForward,
            canReload = stateHolder.canReload,
            onBack = actions.onBack,
            onForward = actions.onForward,
            onReload = actions.onReload
        )
    }

    Spacer(Modifier.height(if (compact) 8.dp else 10.dp))

    MenuQuickActionsGrid(
        compact = compact,
        onBookmarks = actions.onBookmarks,
        onQrCode = actions.onQrCode,
        canQrCode = stateHolder.canQrCode,
        onSettings = actions.onSettings,
        onHome = actions.onHome,
        onTabs = actions.onTabs,
        onNewTab = actions.onNewTab
    )
}

/** Page switches (desktop site, full screen, ad blocker) and the footer. */
@Composable
private fun MenuPageOptions(stateHolder: MenuStateHolder, actions: MenuActions, showFooter: Boolean = true) {
    MenuDesktopSwitchCard(
        isDesktopMode = stateHolder.isDesktopMode,
        onDesktopToggle = actions.onDesktopToggle
    )

    Spacer(Modifier.height(8.dp))

    MenuFullscreenSwitchCard(
        isFullscreenMode = stateHolder.isFullscreenMode,
        onFullscreenToggle = actions.onFullscreenToggle
    )

    stateHolder.adBlockSite?.let { site ->
        Spacer(Modifier.height(8.dp))

        MenuAdBlockSwitchCard(
            site = site,
            isAdBlockerEnabled = stateHolder.isAdBlockerEnabled,
            isActiveOnSite = stateHolder.isAdBlockActiveOnSite,
            blockedCount = stateHolder.adsBlockedCount,
            onAdBlockToggle = actions.onAdBlockToggle
        )
    }

    if (!showFooter) return

    Spacer(Modifier.height(4.dp))

    MenuFooter(
        versionName = stateHolder.versionName,
        onCheckUpdate = actions.onCheckUpdate,
        onGitHub = actions.onGitHub
    )
}
