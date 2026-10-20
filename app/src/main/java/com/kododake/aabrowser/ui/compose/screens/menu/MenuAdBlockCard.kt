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

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.kododake.aabrowser.R

/**
 * Shows how many ads were blocked on the current page; the switch turns the ad blocker on or off
 * for the current site (or back on everywhere when it was turned off in settings).
 */
@Composable
fun MenuAdBlockSwitchCard(
    site: String,
    isAdBlockerEnabled: Boolean,
    isActiveOnSite: Boolean,
    blockedCount: Int,
    onAdBlockToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val status = when {
        !isAdBlockerEnabled -> stringResource(R.string.menu_ad_blocker_off)
        !isActiveOnSite -> stringResource(R.string.menu_ad_blocker_site_off, site)
        else -> pluralStringResource(R.plurals.menu_ad_blocker_count, blockedCount, blockedCount)
    }
    MenuSwitchCard(
        title = stringResource(R.string.menu_ad_blocker),
        supportingText = status,
        icon = Icons.Rounded.Shield,
        isChecked = isAdBlockerEnabled && isActiveOnSite,
        onCheckedChange = onAdBlockToggle,
        modifier = modifier
    )
}
