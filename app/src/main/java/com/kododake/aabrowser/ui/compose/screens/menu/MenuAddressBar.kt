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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.kododake.aabrowser.ui.compose.components.SearchBarComponent
import kotlinx.coroutines.delay

/**
 * @param focusRequestKey changing it (to a value above 0) focuses the field and opens the
 * keyboard, e.g. when the menu is opened from the address bar button.
 */
@Composable
fun MenuAddressBar(
    url: String,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
    focusRequestKey: Int = 0
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(focusRequestKey) {
        if (focusRequestKey <= 0) return@LaunchedEffect
        // Let the sheet slide in first; a field still off screen does not take focus.
        delay(ADDRESS_FOCUS_DELAY_MS)
        runCatching { focusRequester.requestFocus() }
        keyboard?.show()
    }

    SearchBarComponent(
        initialQuery = url,
        onNavigate = onNavigate,
        showSecurityStatus = true,
        focusRequester = focusRequester,
        modifier = modifier
    )
}

private const val ADDRESS_FOCUS_DELAY_MS = 250L
