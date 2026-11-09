package com.zenoue.ccbridge.feature

import android.app.Activity
import android.app.Service
import com.zenoue.ccbridge.api.ActivityDelegate
import com.zenoue.ccbridge.api.FeatureEntry
import com.zenoue.ccbridge.api.HostApi
import com.zenoue.ccbridge.api.ServiceDelegate

/** APK giữ một tham chiếu tới host để feature gọi lại (intent, icon, cập nhật). */
object Host {
    lateinit var api: HostApi
    val ready: Boolean get() = ::api.isInitialized
}

/** Lớp vào của feature.dex (tên cố định: [com.zenoue.ccbridge.api.FEATURE_ENTRY_CLASS]). */
class Entry : FeatureEntry {
    override fun attach(host: HostApi) { Host.api = host }
    override fun activity(activity: Activity): ActivityDelegate = MainScreen(activity)
    override fun service(service: Service): ServiceDelegate = BridgeCore(service)
}
