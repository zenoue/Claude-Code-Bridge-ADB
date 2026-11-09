package com.zenoue.ccbridge.api

import android.app.Activity
import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.Bundle

/**
 * Hợp đồng giữa APK (vỏ, cài một lần) và feature.dex (toàn bộ chức năng, tự cập nhật từ GitHub).
 *
 * Quy tắc: thay đổi bất kỳ chữ ký nào trong file này -> tăng [API_VERSION]. APK chỉ nạp feature.dex
 * có cùng API_VERSION; khác thì báo cần cài APK mới.
 */
const val API_VERSION = 1

/** Lớp vào của feature.dex, có hàm tạo không tham số. */
const val FEATURE_ENTRY_CLASS = "com.zenoue.ccbridge.feature.Entry"

interface FeatureEntry {
    /** Gọi một lần khi nạp feature (mỗi tiến trình). */
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

/** Trạng thái cập nhật feature (bất biến, chỉ đọc). */
class UpdateInfo(
    /** idle | checking | uptodate | downloaded | need_apk | error */
    val state: String,
    val message: String,
    /** Phiên bản feature đã tải sẵn, chờ khởi động lại (0 = không có). */
    val pending: Int,
    /** Thời điểm kiểm tra gần nhất (ms), 0 = chưa. */
    val checkedAt: Long,
)

/** Những gì APK cung cấp cho feature. */
interface HostApi {
    val app: Application
    val apiVersion: Int
    val apkVersion: String
    val featureVersion: Int
    /** "bundled" (đi kèm APK) | "github" (đã tải về) */
    val featureSource: String
    val repo: String
    /** Icon thông báo (tài nguyên nằm trong APK). */
    val notificationIcon: Int

    fun serviceIntent(): Intent
    fun activityIntent(): Intent

    fun updateInfo(): UpdateInfo
    /** Kiểm tra (và tải) bản mới ở nền; [force] = bỏ qua giới hạn tần suất. */
    fun checkUpdate(force: Boolean)
    fun setUpdateListener(listener: Runnable?)
    /** Khởi động lại tiến trình để nạp bản đã tải; [restartService] = bật lại cầu nối sau khi mở lại. */
    fun applyUpdate(restartService: Boolean)
    /** Bỏ bản đã tải, quay về bản đi kèm APK (cần khởi động lại). */
    fun resetToBundled()
}
