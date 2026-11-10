package com.zenoue.ccbridge.api

import android.app.Activity
import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.Bundle

/**
 * Contract between the APK (shell, installed once) and feature.dex (all features, self-updating
 * from GitHub).
 *
 * Rule: any signature change in this file -> bump [API_VERSION]. The APK only loads a feature.dex
 * with the same API_VERSION; otherwise it reports that a new APK is required.
 */
const val API_VERSION = 1

/** Entry class of feature.dex; has a no-arg constructor. */
const val FEATURE_ENTRY_CLASS = "com.zenoue.ccbridge.feature.Entry"

interface FeatureEntry {
    /** Called once when the feature is loaded (per process). */
    fun attach(host: HostApi)
    fun activity(activity: Activity): ActivityDelegate
    fun service(service: Service): ServiceDelegate
}

interface ActivityDelegate {
    fun onCreate(saved: Bundle?)
    fun onResume()
    fun onPause()
    fun onDestroy()
}

interface ServiceDelegate {
    fun onCreate()
    fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int
    fun onDestroy()
}

/** Feature update status (immutable). */
class UpdateInfo(
    /** idle | checking | uptodate | downloaded | need_apk | error */
    val state: String,
    /** Human readable status, shown in the UI. */
    val message: String,
    /** Feature version already downloaded and waiting for a restart (0 = none). */
    val pending: Int,
    /** Time of the last check (ms), 0 = never. */
    val checkedAt: Long,
)

/** What the APK provides to the feature. */
interface HostApi {
    val app: Application
    val apiVersion: Int
    val apkVersion: String
    val featureVersion: Int
    /** "bundled" (shipped with the APK) | "github" (downloaded) */
    val featureSource: String
    val repo: String
    /** Notification icon (the resource lives in the APK). */
    val notificationIcon: Int

    fun serviceIntent(): Intent
    fun activityIntent(): Intent

    fun updateInfo(): UpdateInfo
    /** Check for (and download) a new version in the background; [force] skips the rate limit. */
    fun checkUpdate(force: Boolean)
    fun setUpdateListener(listener: Runnable?)
    /** Restart the process to load the downloaded version; [restartService] = start the bridge again afterwards. */
    fun applyUpdate(restartService: Boolean)
    /** Drop the downloaded version and go back to the bundled one (needs a restart). */
    fun resetToBundled()
}
