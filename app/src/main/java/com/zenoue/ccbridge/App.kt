package com.zenoue.ccbridge

import android.app.Application
import android.os.Build
import org.conscrypt.Conscrypt
import java.io.File
import java.security.Security

/**
 * - Cài Conscrypt làm nhà cung cấp bảo mật đầu tiên (TLS 1.3 + export keying material cho
 *   ghép nối ADB không dây; Conscrypt gói sẵn không bị chặn như Conscrypt ẩn của hệ thống).
 * - Khởi tạo bộ nạp/cập nhật feature (chỉ ở tiến trình chính).
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        try { Security.insertProviderAt(Conscrypt.newProvider(), 1) } catch (_: Throwable) { }
        if (processName().endsWith(":restart")) return
        FeatureHost.init(this)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { FeatureHost.onCrash(e) } catch (_: Throwable) { }
            prev?.uncaughtException(t, e)
        }
    }

    private fun processName(): String =
        if (Build.VERSION.SDK_INT >= 28) getProcessName()
        else try { File("/proc/self/cmdline").readText().trimEnd('\u0000') } catch (_: Throwable) { packageName }
}
