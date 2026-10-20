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

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import com.kododake.aabrowser.BuildConfig
import com.kododake.aabrowser.R
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Updates the app from its GitHub releases without leaving it.
 *
 * A release that supports this carries update.json (written by the makeReleaseUpdatePatch task)
 * next to the APK: the new version, the APK's size and SHA-256, and patches from earlier release
 * APKs. When one of those patches was made from the APK installed here, only the patch is
 * downloaded and the new APK is rebuilt on the device ([ApkPatch]); otherwise the full APK is.
 * Either way the result must match the SHA-256 in update.json, and Android itself refuses an
 * update not signed with the same key as the installed app.
 *
 * Once the user has let the app install updates, Android installs them without asking (the app
 * is updating itself) and restarts the app.
 */
object SelfUpdater {

    sealed interface Status {
        data object Idle : Status

        /** [downloadBytes] is what will be downloaded: the patch, or the full APK of [apkBytes]. */
        data class Available(
            val versionName: String,
            val downloadBytes: Long,
            val apkBytes: Long,
            val isPatch: Boolean
        ) : Status

        data class Downloading(val progress: Float) : Status
        data object Installing : Status
        data class Failed(val message: String) : Status
    }

    /** The latest release, for the version shown and the "View release" link. */
    class Release(val tagName: String, val pageUrl: String)

    var status: Status by mutableStateOf(Status.Idle)
        private set

    private const val TAG = "SelfUpdater"
    private const val UPDATE_MANIFEST = "update.json"
    private const val MAX_JSON_CHARS = 512 * 1024
    private const val CACHE_DIR = "update"

    private class Download(val url: String, val size: Long, val sha256: String)

    private class Plan(
        val versionName: String,
        val apk: Download,
        val patch: Download?
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var plan: Plan? = null

    private val isBusy: Boolean
        get() = status is Status.Downloading || status is Status.Installing

    /** Looks for a newer release; [onResult] gets the latest release, or null if it could not tell. */
    fun check(context: Context, onResult: (Release?) -> Unit) {
        val appContext = context.applicationContext
        executor.execute {
            val result = runCatching { fetchLatest(appContext) }
                .onFailure { Log.w(TAG, "Update check failed: ${it.message}") }
                .getOrNull()
            mainHandler.post {
                if (!isBusy) {
                    plan = result?.second
                    status = result?.second?.let { it.toStatus() } ?: Status.Idle
                }
                onResult(result?.first)
            }
        }
    }

    /** Downloads and installs the update found by [check]. */
    fun startUpdate(activity: Activity) {
        val plan = plan ?: return
        if (isBusy) return
        if (!activity.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(activity, R.string.update_allow_install, Toast.LENGTH_LONG).show()
            runCatching {
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${activity.packageName}".toUri())
                )
            }
            return
        }
        val appContext = activity.applicationContext
        status = Status.Downloading(0f)
        executor.execute {
            try {
                val apk = prepareApk(appContext, plan) { progress ->
                    mainHandler.post { if (status is Status.Downloading) status = Status.Downloading(progress) }
                }
                mainHandler.post { status = Status.Installing }
                install(appContext, apk)
            } catch (e: Exception) {
                Log.w(TAG, "Update failed", e)
                val message = when (e) {
                    is UnknownHostException, is ConnectException, is SocketTimeoutException ->
                        appContext.getString(R.string.update_error_network)
                    else -> e.message ?: e.javaClass.simpleName
                }
                mainHandler.post { status = Status.Failed(message) }
            }
        }
    }

    /** Called by [UpdateInstallReceiver] when Android did not install the update. */
    internal fun onInstallFailed(message: String) {
        mainHandler.post {
            status = Status.Failed(message)
        }
    }

    /** Called when Android needs the user to confirm the install; the update is no longer failing. */
    internal fun onInstallNeedsConfirmation() {
        mainHandler.post {
            plan?.let { status = it.toStatus() }
        }
    }

    internal fun clearDownloads(context: Context) {
        File(context.cacheDir, CACHE_DIR).deleteRecursively()
    }

    private fun Plan.toStatus() = Status.Available(
        versionName = versionName,
        downloadBytes = patch?.size ?: apk.size,
        apkBytes = apk.size,
        isPatch = patch != null
    )

    private fun fetchLatest(context: Context): Pair<Release, Plan?> {
        val release = JSONObject(fetchText(BuildConfig.UPDATE_RELEASE_API))
        val tag = release.getString("tag_name")
        val pageUrl = release.getString("html_url").takeIf(::isGitHubPage)
            ?: throw IOException("Unexpected release page")
        val assets = buildMap {
            val list = release.optJSONArray("assets")
            for (i in 0 until (list?.length() ?: 0)) {
                val asset = list!!.getJSONObject(i)
                put(asset.getString("name"), asset.getString("browser_download_url"))
            }
        }
        return Release(tag, pageUrl) to planUpdate(context, assets)
    }

    /** What to download for the release with [assets], or null if it is not newer or not updatable. */
    private fun planUpdate(context: Context, assets: Map<String, String>): Plan? {
        if (!BuildConfig.SELF_UPDATE) return null
        val manifestUrl = assets[UPDATE_MANIFEST]?.takeIf(::isAllowedDownload) ?: return null
        val manifest = JSONObject(fetchText(manifestUrl))
        if (manifest.getLong("versionCode") <= BuildConfig.VERSION_CODE) return null

        val apkJson = manifest.getJSONObject("apk")
        val apkUrl = assets[apkJson.getString("name")]?.takeIf(::isAllowedDownload) ?: return null
        val apk = Download(apkUrl, apkJson.getLong("size"), apkJson.getString("sha256"))

        val patch = installedApk(context)?.let { installed ->
            val patches = manifest.optJSONArray("patches") ?: return@let null
            if (patches.length() == 0) return@let null
            val installedSha = sha256(installed)
            (0 until patches.length()).asSequence()
                .map { patches.getJSONObject(it) }
                .firstOrNull { it.optString("fromSha256") == installedSha }
                ?.let { entry ->
                    val url = assets[entry.getString("name")]?.takeIf(::isAllowedDownload) ?: return@let null
                    Download(url, entry.getLong("size"), entry.getString("sha256"))
                }
        }
        return Plan(manifest.getString("versionName"), apk, patch)
    }

    /** The installed APK a patch can start from; not available for installs split into several APKs. */
    private fun installedApk(context: Context): File? {
        val info = context.applicationInfo
        if (!info.splitSourceDirs.isNullOrEmpty()) return null
        return File(info.sourceDir).takeIf { it.canRead() }
    }

    private fun prepareApk(context: Context, plan: Plan, onProgress: (Float) -> Unit): File {
        val dir = File(context.cacheDir, CACHE_DIR).apply {
            deleteRecursively()
            mkdirs()
        }
        val apk = File(dir, "update.apk")
        val installed = installedApk(context)
        if (plan.patch != null && installed != null) {
            try {
                val patchFile = File(dir, "update.patch")
                download(plan.patch, patchFile, onProgress)
                ApkPatch.apply(installed, patchFile, apk)
                patchFile.delete()
                if (sha256(apk) == plan.apk.sha256) return apk
                Log.w(TAG, "Patched APK does not match the release; downloading the full APK")
            } catch (e: Exception) {
                // A bad patch must never block the update; the full APK still works.
                Log.w(TAG, "Patch update failed, downloading the full APK: ${e.message}")
            }
        }
        download(plan.apk, apk, onProgress)
        return apk
    }

    /** Downloads [download] to [target], refusing anything bigger than announced or with another hash. */
    private fun download(download: Download, target: File, onProgress: (Float) -> Unit) {
        val request = Request.Builder().url(download.url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Download failed (HTTP ${response.code})")
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            response.body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        written += n
                        if (written > download.size) throw IOException("Download is larger than announced")
                        digest.update(buffer, 0, n)
                        output.write(buffer, 0, n)
                        onProgress(written.toFloat() / download.size)
                    }
                }
            }
            if (written != download.size || digest.digest().toHex() != download.sha256) {
                target.delete()
                throw IOException("Downloaded update is damaged")
            }
        }
    }

    private fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            // Honoured because the app updates itself; Android asks instead when it does not allow it.
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val callback = PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(context, UpdateInstallReceiver::class.java),
                // Mutable so the installer can add the result; explicit, so nobody else can fill it.
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            session.commit(callback.intentSender)
        }
    }

    private fun fetchText(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body
            if (body.contentLength() > MAX_JSON_CHARS) throw IOException("Response too large")
            val text = body.charStream().use { reader ->
                val buffer = CharArray(MAX_JSON_CHARS + 1)
                var length = 0
                while (length < buffer.size) {
                    val n = reader.read(buffer, length, buffer.size - length)
                    if (n < 0) break
                    length += n
                }
                if (length > MAX_JSON_CHARS) throw IOException("Response too large")
                String(buffer, 0, length)
            }
            return text
        }
    }

    /** Release downloads must use HTTPS; debug builds may test against a local server. */
    private fun isAllowedDownload(url: String): Boolean {
        val scheme = runCatching { url.toUri().scheme }.getOrNull()
        return scheme == "https" || (BuildConfig.DEBUG && scheme == "http")
    }

    /** The release page is opened in the browser later, so only accept a GitHub page. */
    private fun isGitHubPage(url: String): Boolean {
        val uri = runCatching { url.toUri() }.getOrNull() ?: return false
        return uri.scheme == "https" && uri.host == "github.com"
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
