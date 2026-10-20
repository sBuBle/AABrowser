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

package com.kododake.aabrowser.ui.compose.screens.settings.sections

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LockReset
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kododake.aabrowser.AppConstants
import com.kododake.aabrowser.BuildConfig
import com.kododake.aabrowser.R
import com.kododake.aabrowser.data.BrowserPreferences
import com.kododake.aabrowser.model.UserAgentProfile
import com.kododake.aabrowser.ui.compose.components.ExpressiveListItem
import com.kododake.aabrowser.ui.compose.components.ListGroupPosition
import com.kododake.aabrowser.ui.compose.components.bouncyClickable
import com.kododake.aabrowser.ui.compose.screens.dialogs.AdBlockAllowedSitesDialog
import com.kododake.aabrowser.ui.compose.screens.dialogs.CustomUserAgentDialog
import com.kododake.aabrowser.ui.compose.screens.dialogs.ExpressiveConfirmationDialog
import com.kododake.aabrowser.ui.compose.screens.dialogs.ExpressiveSingleChoiceDialog
import com.kododake.aabrowser.adblock.FilterListManager
import com.kododake.aabrowser.web.AdBlocker
import com.kododake.aabrowser.web.SslErrorHandlerHelper
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

@Composable
fun PrivacyAndAboutComposable(
    context: Context,
    onDrmL3EnforcerChanged: () -> Unit = {},
    onAdBlockerChanged: () -> Unit = {},
    onUserAgentChanged: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var userAgent by remember { mutableStateOf(BrowserPreferences.getUserAgentProfile(context)) }
    var customUserAgent by remember { mutableStateOf(BrowserPreferences.getCustomUserAgent(context)) }
    var drmL3Enabled by remember { mutableStateOf(BrowserPreferences.isDrmL3EnforcerEnabled(context)) }
    var adBlockerEnabled by remember { mutableStateOf(BrowserPreferences.isAdBlockerEnabled(context)) }
    var filterListsStatus by remember { mutableStateOf(filterListsStatus(context)) }
    var filterListsUpdating by remember { mutableStateOf(false) }
    var allowedSites by remember { mutableStateOf(BrowserPreferences.getAdBlockAllowedSites(context).sorted()) }
    var showAllowedSitesDialog by remember { mutableStateOf(false) }
    var showUaDialog by remember { mutableStateOf(false) }
    var showCustomUaDialog by remember { mutableStateOf(false) }
    var showClearCookiesDialog by remember { mutableStateOf(false) }
    var showClearPermissionsDialog by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.settings_ad_block_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 12.dp, bottom = 8.dp)
        )

        ExpressiveListItem(
            headline = stringResource(R.string.settings_ad_blocker),
            supportingText = stringResource(R.string.settings_ad_blocker_description),
            leadingIcon = Icons.Rounded.Block,
            position = ListGroupPosition.Top,
            trailingContent = {
                Switch(
                    checked = adBlockerEnabled,
                    onCheckedChange = { checked ->
                        adBlockerEnabled = checked
                        BrowserPreferences.setAdBlockerEnabled(context, checked)
                        onAdBlockerChanged()
                    }
                )
            },
            onClick = {
                val next = !adBlockerEnabled
                adBlockerEnabled = next
                BrowserPreferences.setAdBlockerEnabled(context, next)
                onAdBlockerChanged()
            }
        )

        Spacer(Modifier.height(3.dp))

        ExpressiveListItem(
            headline = stringResource(R.string.settings_ad_block_lists),
            supportingText = if (filterListsUpdating) {
                stringResource(R.string.settings_ad_block_lists_updating)
            } else {
                filterListsStatus
            },
            leadingIcon = Icons.Rounded.FilterList,
            position = ListGroupPosition.Middle,
            onClick = {
                if (!filterListsUpdating) {
                    filterListsUpdating = true
                    AdBlocker.updateFilterLists(context) { updated ->
                        filterListsUpdating = false
                        filterListsStatus = filterListsStatus(context)
                        Toast.makeText(
                            context,
                            if (updated) R.string.settings_ad_block_lists_updated else R.string.settings_ad_block_lists_update_failed,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        )

        Spacer(Modifier.height(3.dp))

        ExpressiveListItem(
            headline = stringResource(R.string.settings_ad_block_allowed_sites),
            supportingText = if (allowedSites.isEmpty()) {
                stringResource(R.string.settings_ad_block_allowed_sites_none)
            } else {
                pluralStringResource(R.plurals.settings_ad_block_allowed_sites_count, allowedSites.size, allowedSites.size)
            },
            leadingIcon = Icons.Rounded.Language,
            position = ListGroupPosition.Bottom,
            onClick = { showAllowedSitesDialog = true }
        )

        Spacer(Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.settings_site_data_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 12.dp, bottom = 8.dp)
        )

        ExpressiveListItem(
            headline = stringResource(R.string.settings_user_agent),
            supportingText = stringResource(userAgent.titleRes),
            leadingIcon = Icons.Rounded.Devices,
            position = ListGroupPosition.Top,
            onClick = { showUaDialog = true }
        )

        if (userAgent == UserAgentProfile.CUSTOM) {
            Spacer(Modifier.height(3.dp))
            ExpressiveListItem(
                headline = stringResource(R.string.settings_user_agent_custom_hint),
                supportingText = customUserAgent.ifBlank { stringResource(R.string.settings_user_agent_custom_empty_fallback) },
                leadingIcon = Icons.Rounded.Edit,
                position = ListGroupPosition.Middle,
                onClick = { showCustomUaDialog = true }
            )
        }

        Spacer(Modifier.height(3.dp))

        ExpressiveListItem(
            headline = stringResource(R.string.settings_drm_l3_enforcer),
            supportingText = stringResource(R.string.settings_drm_l3_enforcer_description),
            leadingIcon = Icons.Rounded.Security,
            position = ListGroupPosition.Middle,
            trailingContent = {
                Switch(
                    checked = drmL3Enabled,
                    onCheckedChange = { checked ->
                        drmL3Enabled = checked
                        BrowserPreferences.setDrmL3EnforcerEnabled(context, checked)
                        onDrmL3EnforcerChanged()
                    }
                )
            },
            onClick = {
                val next = !drmL3Enabled
                drmL3Enabled = next
                BrowserPreferences.setDrmL3EnforcerEnabled(context, next)
                onDrmL3EnforcerChanged()
            }
        )

        Spacer(Modifier.height(3.dp))

        ExpressiveListItem(
            headline = stringResource(R.string.settings_clear_site_permissions),
            supportingText = stringResource(R.string.settings_clear_site_permissions_title),
            leadingIcon = Icons.Rounded.LockReset,
            position = ListGroupPosition.Middle,
            onClick = { showClearPermissionsDialog = true }
        )

        Spacer(Modifier.height(3.dp))

        ExpressiveListItem(
            headline = stringResource(R.string.settings_clear_cookies),
            supportingText = stringResource(R.string.settings_site_data_description),
            leadingIcon = Icons.Rounded.Delete,
            position = ListGroupPosition.Bottom,
            onClick = { showClearCookiesDialog = true }
        )

        Spacer(Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.settings_license),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 12.dp, bottom = 8.dp)
        )

        ExpressiveListItem(
            headline = stringResource(R.string.open_source_view_licenses),
            supportingText = stringResource(R.string.open_source_licenses_title),
            leadingIcon = Icons.Rounded.Code,
            position = ListGroupPosition.Top,
            onClick = {
                try {
                    val activityClass = Class.forName("com.google.android.gms.oss.licenses.OssLicensesMenuActivity")
                    val intent = Intent(context, activityClass)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(context, R.string.error_generic_message, Toast.LENGTH_SHORT).show()
                }
            }
        )

        Spacer(Modifier.height(3.dp))

        ExpressiveListItem(
            headline = stringResource(R.string.settings_license),
            supportingText = stringResource(R.string.settings_license_description),
            leadingIcon = Icons.Rounded.Info,
            position = ListGroupPosition.Middle,
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.gnu.org/licenses/gpl-3.0.html"))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        )

        Spacer(Modifier.height(3.dp))

        ExpressiveListItem(
            headline = stringResource(R.string.app_name),
            supportingText = "v${BuildConfig.VERSION_NAME} • ${AppConstants.GITHUB_REPO_URL}",
            leadingIcon = Icons.Rounded.Info,
            position = ListGroupPosition.Bottom,
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(AppConstants.GITHUB_REPO_URL))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        )
    }

    if (showUaDialog) {
        ExpressiveSingleChoiceDialog(
            title = stringResource(R.string.settings_user_agent),
            items = UserAgentProfile.entries,
            selectedItem = userAgent,
            onItemSelected = { profile ->
                if (profile != userAgent) {
                    userAgent = profile
                    BrowserPreferences.setUserAgentProfile(context, profile)
                    onUserAgentChanged()
                }
                if (profile == UserAgentProfile.CUSTOM) {
                    showCustomUaDialog = true
                }
            },
            onDismiss = { showUaDialog = false },
            itemLabel = { profile ->
                stringResource(profile.titleRes)
            }
        )
    }

    if (showCustomUaDialog) {
        CustomUserAgentDialog(
            initialValue = customUserAgent,
            onConfirm = { newUa ->
                BrowserPreferences.setCustomUserAgent(context, newUa)
                customUserAgent = BrowserPreferences.getCustomUserAgent(context)
                onUserAgentChanged()
            },
            onDismiss = { showCustomUaDialog = false }
        )
    }

    if (showClearPermissionsDialog) {
        ExpressiveConfirmationDialog(
            title = stringResource(R.string.settings_clear_site_permissions_title),
            message = stringResource(R.string.settings_clear_site_permissions_message),
            isDestructive = true,
            confirmButtonText = stringResource(android.R.string.ok),
            onConfirm = {
                BrowserPreferences.clearSavedSitePermissions(context)
                GeolocationPermissions.getInstance().clearAll()
                WebView.clearClientCertPreferences(null)
                SslErrorHandlerHelper.clearAllowedSslHosts()
                Toast.makeText(context, R.string.settings_clear_site_permissions_success_title, Toast.LENGTH_SHORT).show()
                showClearPermissionsDialog = false
            },
            onDismiss = { showClearPermissionsDialog = false }
        )
    }

    if (showAllowedSitesDialog) {
        AdBlockAllowedSitesDialog(
            sites = allowedSites,
            onRemoveSite = { site ->
                AdBlocker.setSiteAllowed(context, site, allowed = false)
                allowedSites = BrowserPreferences.getAdBlockAllowedSites(context).sorted()
            },
            onDismiss = { showAllowedSitesDialog = false }
        )
    }

    if (showClearCookiesDialog) {
        ExpressiveConfirmationDialog(
            title = stringResource(R.string.settings_clear_cookies_title),
            message = stringResource(R.string.settings_clear_cookies_message),
            isDestructive = true,
            confirmButtonText = stringResource(android.R.string.ok),
            onConfirm = {
                WebStorage.getInstance().deleteAllData()
                CookieManager.getInstance().removeAllCookies(null)
                Toast.makeText(context, R.string.settings_clear_cookies_success_title, Toast.LENGTH_SHORT).show()
                showClearCookiesDialog = false
            },
            onDismiss = { showClearCookiesDialog = false }
        )
    }
}

private fun filterListsStatus(context: Context): String {
    val lastUpdated = FilterListManager.lastUpdated(context)
    if (lastUpdated == 0L) return context.getString(R.string.settings_ad_block_lists_never_updated)
    return context.getString(
        R.string.settings_ad_block_lists_status,
        NumberFormat.getIntegerInstance().format(AdBlocker.ruleCount),
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(lastUpdated))
    )
}
