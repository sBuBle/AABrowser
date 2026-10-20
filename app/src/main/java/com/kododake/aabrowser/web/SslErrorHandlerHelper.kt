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

package com.kododake.aabrowser.web

import android.app.Activity
import android.net.http.SslError
import android.view.WindowManager
import android.webkit.SslErrorHandler
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.mutableStateSetOf
import androidx.core.net.toUri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.kododake.aabrowser.R
import com.kododake.aabrowser.ui.compose.screens.dialogs.DialogViews
import java.security.MessageDigest

object SslErrorHandlerHelper {

    /**
     * Certificates the user chose to trust anyway, as "host|SHA-256 of the certificate". Tied to the
     * certificate, like Chrome, so a different (possibly attacker's) certificate for the same host
     * asks again instead of being let through silently.
     */
    private val allowedSslExceptions = HashSet<String>()

    /**
     * Hosts in [allowedSslExceptions], so the address bar can drop the padlock for them. Snapshot
     * state, so an address bar already showing the page updates when the exception is added.
     */
    private val hostsWithExceptions = mutableStateSetOf<String>()

    /** Whether the user let an invalid certificate through for [url]'s host this session. */
    fun hasCertificateException(url: String): Boolean {
        val host = runCatching { url.toUri().host?.lowercase() }.getOrNull() ?: return false
        return host in hostsWithExceptions
    }

    fun handleSslError(activity: Activity, handler: SslErrorHandler, error: SslError) {
        val url = error.url ?: ""
        val host = runCatching { url.toUri().host?.lowercase() }.getOrNull()
        val exceptionKey = host?.let { h -> certificateFingerprint(error)?.let { "$h|$it" } }

        if (exceptionKey != null && exceptionKey in allowedSslExceptions) {
            handler.proceed()
            return
        }

        if (activity.isFinishing || activity.isDestroyed) {
            handler.cancel()
            return
        }

        val primaryError = error.primaryError
        val errorDescription = getSslErrorDescription(activity, primaryError)
        val hostLabel = host ?: url

        var dialog: AlertDialog? = null
        val view = DialogViews.createConfirmationDialogView(
            context = activity,
            title = activity.getString(R.string.ssl_error_title),
            message = activity.getString(R.string.ssl_error_message, hostLabel),
            hostLabel = activity.getString(R.string.location_access_host_label),
            hostValue = hostLabel,
            detailMessage = activity.getString(R.string.ssl_error_reason_prefix) + " " + errorDescription,
            isDestructive = true,
            confirmButtonText = activity.getString(R.string.ssl_error_proceed),
            onConfirm = {
                try { dialog?.dismiss() } catch (_: Exception) {}
                if (exceptionKey != null && host != null) {
                    allowedSslExceptions.add(exceptionKey)
                    hostsWithExceptions.add(host)
                }
                handler.proceed()
            },
            dismissButtonText = activity.getString(R.string.ssl_error_cancel),
            onDismiss = {
                try { dialog?.dismiss() } catch (_: Exception) {}
                handler.cancel()
            }
        )

        try {
            dialog = MaterialAlertDialogBuilder(activity, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
                .setView(view)
                .setCancelable(false)
                .create()
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.show()
            val width = (activity.resources.displayMetrics.widthPixels * 0.9).toInt()
            dialog.window?.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
        } catch (_: Exception) {
            handler.cancel()
        }
    }

    private fun getSslErrorDescription(activity: Activity, errorCode: Int): String {
        return when (errorCode) {
            SslError.SSL_EXPIRED -> activity.getString(R.string.ssl_error_expired)
            SslError.SSL_IDMISMATCH -> activity.getString(R.string.ssl_error_idmismatch)
            SslError.SSL_UNTRUSTED -> activity.getString(R.string.ssl_error_untrusted)
            SslError.SSL_NOTYETVALID -> activity.getString(R.string.ssl_error_notyetvalid)
            SslError.SSL_DATE_INVALID -> activity.getString(R.string.ssl_error_date_invalid)
            else -> activity.getString(R.string.ssl_error_invalid)
        }
    }

    private fun certificateFingerprint(error: SslError): String? = runCatching {
        val encoded = error.certificate.x509Certificate?.encoded ?: return null
        MessageDigest.getInstance("SHA-256").digest(encoded).toHexString()
    }.getOrNull()

    fun clearAllowedSslHosts() {
        allowedSslExceptions.clear()
        hostsWithExceptions.clear()
    }
}
