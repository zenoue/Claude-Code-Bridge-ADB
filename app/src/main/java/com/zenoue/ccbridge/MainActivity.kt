package com.zenoue.ccbridge

import android.app.Activity
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.zenoue.ccbridge.api.ActivityDelegate

/**
 * Vỏ Activity: chuyển vòng đời vào feature (MainScreen trong feature.dex).
 * Feature lỗi -> hiện màn hình dự phòng tối giản để quay về bản đi kèm APK.
 */
class MainActivity : Activity() {

    companion object {
        const val EXTRA_START_SERVICE = "start_service"
    }

    private var d: ActivityDelegate? = null

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        guard { d = FeatureHost.entry().activity(this).also { it.onCreate(s) } }
        if (intent.getBooleanExtra(EXTRA_START_SERVICE, false) && d != null) {
            intent.removeExtra(EXTRA_START_SERVICE)
            val i = FeatureHost.serviceIntent()
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        }
    }

    override fun onResume() { super.onResume(); guard { d?.onResume() } }
    override fun onPause() { guard { d?.onPause() }; super.onPause() }
    override fun onDestroy() { guard { d?.onDestroy() }; super.onDestroy() }

    private inline fun guard(block: () -> Unit) {
        try { block() } catch (t: Throwable) {
            FeatureHost.onFeatureFailure(t)
            d = null
            fallback(t)
        }
    }

    private fun fallback(t: Throwable) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 60, 40, 40) }
        col.addView(TextView(this).apply {
            text = "Chức năng bị lỗi"; textSize = 20f; typeface = Typeface.DEFAULT_BOLD
        })
        col.addView(TextView(this).apply {
            text = "Chức năng v1.0.${FeatureHost.featureVersion} (${FeatureHost.featureSource}) gặp lỗi:\n\n" +
                t.stackTraceToString().take(2500)
            textSize = 12f; typeface = Typeface.MONOSPACE; setTextIsSelectable(true)
        })
        col.addView(Button(this).apply {
            text = "Dùng bản đi kèm APK và khởi động lại"; isAllCaps = false
            setOnClickListener { FeatureHost.resetToBundled(); FeatureHost.applyUpdate(false) }
        })
        col.addView(Button(this).apply {
            text = "Tải bản mới từ GitHub"; isAllCaps = false
            setOnClickListener {
                FeatureHost.setUpdateListener {
                    runOnUiThread {
                        val u = FeatureHost.updateInfo()
                        text = u.message
                        if (u.state == "downloaded") FeatureHost.applyUpdate(false)
                    }
                }
                FeatureHost.checkUpdate(true)
            }
        })
        setContentView(ScrollView(this).apply { addView(col) })
    }
}
