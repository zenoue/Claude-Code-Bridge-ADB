package com.zenoue.ccbridge

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process

/**
 * Chạy ở tiến trình riêng (":restart"): giết tiến trình chính rồi mở lại app,
 * để nạp feature.dex mới (lớp đã nạp không gỡ được khỏi tiến trình cũ).
 */
class RestartActivity : Activity() {

    companion object {
        const val EXTRA_PID = "pid"
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val pid = intent.getIntExtra(EXTRA_PID, -1)
        if (pid > 0 && pid != Process.myPid()) Process.killProcess(pid)
        val startService = intent.getBooleanExtra(MainActivity.EXTRA_START_SERVICE, false)
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_START_SERVICE, startService)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            finish()
            Process.killProcess(Process.myPid())
        }, 500)
    }
}
