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

package com.kododake.aabrowser.main

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Watches the system default network and reports when internet connectivity is gained or lost.
 *
 * Losses are debounced so a Wi-Fi to cellular handover (common in a moving car) does not make
 * pages pause their streams; regaining connectivity is reported immediately.
 */
class NetworkStateMonitor(
    context: Context,
    private val onAvailabilityChanged: (Boolean) -> Unit
) {
    private val connectivityManager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var registered = false
    private var reportedAvailable = true

    private val reportLost = Runnable { report(false) }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            onDefaultNetworkChanged(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        }

        override fun onLost(network: Network) {
            onDefaultNetworkChanged(false)
        }
    }

    fun start() {
        if (registered || connectivityManager == null) return
        runCatching {
            // WebViews start out online, so only an initial offline state needs reporting.
            if (!isInternetAvailableNow(connectivityManager)) report(false)
            connectivityManager.registerDefaultNetworkCallback(callback, mainHandler)
            registered = true
        }.onFailure { e ->
            Log.w(TAG, "Unable to monitor network state: ${e.message}")
        }
    }

    fun stop() {
        mainHandler.removeCallbacks(reportLost)
        if (!registered) return
        runCatching { connectivityManager?.unregisterNetworkCallback(callback) }
        registered = false
    }

    private fun onDefaultNetworkChanged(available: Boolean) {
        mainHandler.removeCallbacks(reportLost)
        if (available) {
            report(true)
        } else if (reportedAvailable) {
            mainHandler.postDelayed(reportLost, LOSS_DEBOUNCE_MS)
        }
    }

    private fun report(available: Boolean) {
        if (reportedAvailable == available) return
        reportedAvailable = available
        onAvailabilityChanged(available)
    }

    private fun isInternetAvailableNow(manager: ConnectivityManager): Boolean {
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private companion object {
        const val TAG = "NetworkStateMonitor"
        const val LOSS_DEBOUNCE_MS = 1500L
    }
}
