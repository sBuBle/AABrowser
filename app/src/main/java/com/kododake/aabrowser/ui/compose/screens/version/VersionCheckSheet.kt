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

package com.kododake.aabrowser.ui.compose.screens.version

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kododake.aabrowser.R
import com.kododake.aabrowser.update.SelfUpdater
import com.kododake.aabrowser.ui.compose.components.ExpressiveBottomSheetContainer
import com.kododake.aabrowser.ui.compose.components.bouncyClickable

@Composable
fun VersionCheckSheet(
    isVisible: Boolean,
    isChecking: Boolean,
    latestVersion: String?,
    installedVersion: String,
    releaseUrl: String?,
    updateStatus: SelfUpdater.Status,
    onStartUpdate: () -> Unit,
    onOpenRelease: (String) -> Unit,
    onClose: () -> Unit,
    onDismissFinished: () -> Unit,
    onDismiss: () -> Unit = onClose,
    animateEnter: Boolean = true,
    onProgress: (Float) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // An update the app can install itself is known to be newer, whatever its tag says.
    val isUpToDate = updateStatus is SelfUpdater.Status.Idle && isUpToDate(installedVersion, latestVersion)

    ExpressiveBottomSheetContainer(
        isVisible = isVisible,
        onDismissRequest = onDismiss,
        onDismissFinished = onDismissFinished,
        animateEnter = animateEnter,
        onProgress = onProgress,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            VersionSheetHeader(onClose = onClose)

            Spacer(Modifier.height(16.dp))

            VersionStatusCard(
                isChecking = isChecking,
                isUpToDate = isUpToDate,
                installedVersion = installedVersion,
                latestVersion = latestVersion
            )

            Spacer(Modifier.height(20.dp))

            if (!isChecking) {
                SelfUpdateControls(status = updateStatus, onStartUpdate = onStartUpdate)
            }

            if (!isChecking && !isUpToDate && !releaseUrl.isNullOrBlank() && updateStatus is SelfUpdater.Status.Idle) {
                Button(
                    onClick = { onOpenRelease(releaseUrl) },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .bouncyClickable { onOpenRelease(releaseUrl) }
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.check_latest_view_release))
                }
                Spacer(Modifier.height(12.dp))
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SelfUpdateControls(status: SelfUpdater.Status, onStartUpdate: () -> Unit) {
    val context = LocalContext.current
    when (status) {
        SelfUpdater.Status.Idle -> return
        is SelfUpdater.Status.Available -> {
            Button(
                onClick = onStartUpdate,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Icon(Icons.Rounded.SystemUpdate, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.update_now))
            }
            Spacer(Modifier.height(8.dp))
            val download = Formatter.formatShortFileSize(context, status.downloadBytes)
            Text(
                text = if (status.isPatch) {
                    stringResource(R.string.update_size_patch, download, Formatter.formatShortFileSize(context, status.apkBytes))
                } else {
                    stringResource(R.string.update_size_full, download)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        is SelfUpdater.Status.Downloading -> {
            LinearProgressIndicator(progress = { status.progress }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.update_downloading, (status.progress * 100).toInt()),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        SelfUpdater.Status.Installing -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.update_installing), style = MaterialTheme.typography.bodyMedium)
        }
        is SelfUpdater.Status.Failed -> {
            Text(
                text = stringResource(R.string.update_failed, status.message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onStartUpdate, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.update_retry))
            }
        }
    }
    Spacer(Modifier.height(12.dp))
}
