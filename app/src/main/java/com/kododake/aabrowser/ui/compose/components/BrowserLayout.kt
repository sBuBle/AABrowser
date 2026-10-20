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

package com.kododake.aabrowser.ui.compose.components

import android.content.res.Configuration
import android.view.Display
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How the browser UI should be laid out on the screen it is shown on: a phone held either way,
 * a tablet, or an Android Auto car display (wide and short).
 */
@Immutable
data class BrowserLayout(
    val screenWidth: Dp,
    val screenHeight: Dp
) {
    /**
     * Wide but short screens (car displays, phones in landscape). Sheets put their content in
     * two columns there, since a single column would need scrolling.
     */
    val isWideAndShort: Boolean
        get() = screenWidth >= 600.dp && screenHeight < 600.dp && screenWidth > screenHeight

    /** Sheets stop growing past this width, so buttons don't stretch across tablets and wide car screens. */
    val maxSheetWidth: Dp
        get() = when {
            isWideAndShort -> 1040.dp
            screenWidth >= 700.dp -> 720.dp
            else -> Dp.Infinity
        }

    companion object {
        /**
         * Android Auto shows the browser on its own display; the phone's screen is the default one.
         * Car UI mode covers head units that host the app on the main display.
         */
        fun isCarDisplay(view: View): Boolean {
            val displayId = view.display?.displayId ?: Display.DEFAULT_DISPLAY
            val uiModeType = view.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
            return displayId != Display.DEFAULT_DISPLAY || uiModeType == Configuration.UI_MODE_TYPE_CAR
        }
    }
}

@Composable
fun rememberBrowserLayout(): BrowserLayout {
    val configuration = LocalConfiguration.current
    return BrowserLayout(
        screenWidth = configuration.screenWidthDp.dp,
        screenHeight = configuration.screenHeightDp.dp
    )
}
