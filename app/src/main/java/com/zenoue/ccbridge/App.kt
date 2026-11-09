package com.zenoue.ccbridge

import android.app.Application
import org.conscrypt.Conscrypt
import java.security.Security

/**
 * Cài Conscrypt làm nhà cung cấp bảo mật đầu tiên.
 * Cần cho TLS 1.3 + export keying material mà giao thức ghép nối ADB dùng
 * (Conscrypt gói sẵn không bị chặn như Conscrypt ẩn của hệ thống).
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        try { Security.insertProviderAt(Conscrypt.newProvider(), 1) } catch (_: Throwable) { }
    }
}
