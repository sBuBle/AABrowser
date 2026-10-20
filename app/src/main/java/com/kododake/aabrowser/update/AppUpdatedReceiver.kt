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
package com.kododake.aabrowser.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Runs after the app was updated and removes the downloaded update. It does not reopen the app:
 * Android blocks activity starts from here; the system brings back an app that was updated while
 * on screen (on One UI at least), and elsewhere the user opens it again.
 */
class AppUpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        SelfUpdater.clearDownloads(context)
    }
}
