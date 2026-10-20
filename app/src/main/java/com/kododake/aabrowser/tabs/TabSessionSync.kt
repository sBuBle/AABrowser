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

import android.content.Context
import com.kododake.aabrowser.data.BrowserPreferences

/**
 * Keeps the tabs on the phone and on the car display the same.
 *
 * Android Auto runs a second browser activity for the car screen, with its own [TabManager] and
 * WebViews, in this same process. Both share one session here: the screen in use publishes its
 * tabs (and saves them), and the other catches up when the user turns to it. A screen nobody is
 * using never publishes, so its stale tabs cannot undo what was done on the other one.
 *
 * Main thread only.
 */
object TabSessionSync {

    data class Session(val tabs: List<BrowserPreferences.TabSessionEntry>, val activeIndex: Int)

    private val members = mutableSetOf<TabManager>()

    /** The tab manager on the screen the user touched last. */
    private var inUse: TabManager? = null

    /** Incremented on every change, so members can tell whether they are behind. */
    var version = 0L
        private set

    var session: Session? = null
        private set

    fun register(member: TabManager) {
        members.add(member)
    }

    fun unregister(member: TabManager) {
        members.remove(member)
        if (inUse === member) inUse = null
    }

    /** Whether another screen has the browser open, so a new one should show the same tabs. */
    fun hasOtherMembers(member: TabManager): Boolean = members.any { it !== member }

    fun claimUse(member: TabManager) {
        inUse = member
    }

    fun mayPublish(member: TabManager): Boolean = inUse === member || inUse == null || !hasOtherMembers(member)

    /** Makes [session] the shared one and saves it; returns its version. */
    fun publish(context: Context, session: Session): Long {
        if (session != this.session) {
            this.session = session
            version++
            BrowserPreferences.persistTabSession(context, session.tabs, session.activeIndex)
        }
        return version
    }
}
