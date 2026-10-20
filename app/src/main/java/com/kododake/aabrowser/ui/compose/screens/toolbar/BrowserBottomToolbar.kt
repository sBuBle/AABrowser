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

package com.kododake.aabrowser.ui.compose.screens.toolbar

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kododake.aabrowser.R
import com.kododake.aabrowser.ui.compose.components.bouncyClickable
import com.kododake.aabrowser.ui.compose.screens.menu.MenuStateHolder
import com.kododake.aabrowser.ui.compose.theme.AABrowserTheme

/** Action callbacks for [BrowserBottomToolbar]. */
data class ToolbarActions(
    val onBack: () -> Unit = {},
    val onForward: () -> Unit = {},
    val onAddress: () -> Unit = {},
    val onTabs: () -> Unit = {},
    val onMenu: () -> Unit = {}
)

/**
 * Phone toolbar along the bottom of the screen, within thumb reach: back, forward, the current
 * address (tap to edit), tabs and the browser menu. On the car display the floating menu button
 * is used instead, leaving the short screen to the page.
 */
@Composable
fun BrowserBottomToolbar(
    stateHolder: MenuStateHolder,
    tabCount: () -> Int,
    actions: ToolbarActions
) {
    AABrowserTheme {
        val isDark = isSystemInDarkTheme()
        Surface(
            color = if (isDark) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier
                .fillMaxWidth()
                .height(dimensionResource(R.dimen.bottom_toolbar_height))
        ) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ToolbarIconButton(
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    description = stringResource(R.string.menu_back),
                    enabled = stateHolder.canGoBack,
                    onClick = actions.onBack
                )
                ToolbarIconButton(
                    icon = Icons.AutoMirrored.Rounded.ArrowForward,
                    description = stringResource(R.string.menu_forward),
                    enabled = stateHolder.canGoForward,
                    onClick = actions.onForward
                )

                AddressPill(
                    url = if (stateHolder.isOnStartPage) "" else stateHolder.url,
                    onClick = actions.onAddress,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp)
                )

                TabsButton(count = tabCount(), onClick = actions.onTabs)

                IconButton(onClick = actions.onMenu, modifier = Modifier.size(48.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_menu_24px),
                        contentDescription = stringResource(R.string.menu_open_description),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolbarIconButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f)
        )
    }
}

@Composable
private fun AddressPill(url: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(22.dp)
    val isSecure = url.startsWith("https://", ignoreCase = true)
    val isInsecure = url.startsWith("http://", ignoreCase = true)
    val label = displayAddress(url).ifEmpty { stringResource(R.string.menu_address_label) }
    val isDark = isSystemInDarkTheme()

    Surface(
        shape = shape,
        color = if (isDark) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
        modifier = modifier
            .height(44.dp)
            .bouncyClickable(shape = shape, onClick = onClick)
            .semantics { contentDescription = label }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when {
                    isSecure -> Icons.Rounded.Lock
                    isInsecure -> Icons.Rounded.LockOpen
                    else -> Icons.Rounded.Search
                },
                contentDescription = null,
                tint = if (isInsecure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (url.isEmpty()) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** The tab count in an outlined square, like other mobile browsers. */
@Composable
private fun TabsButton(count: Int, onClick: () -> Unit) {
    val description = pluralStringResource(R.plurals.toolbar_open_tabs, count, count)
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp).semantics { contentDescription = description }) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(24.dp)
                .border(2.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(6.dp))
        ) {
            Text(
                text = count.toString(),
                fontSize = if (count > 9) 10.sp else 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/** Host and path without the scheme and `www.`, which is what people read an address by. */
private fun displayAddress(url: String): String {
    if (url.isBlank()) return ""
    val uri = runCatching { Uri.parse(url) }.getOrNull()
    val host = uri?.host?.removePrefix("www.")
    if (host.isNullOrEmpty()) return url
    val path = uri.path?.takeIf { it.length > 1 }.orEmpty()
    return host + path
}
