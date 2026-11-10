package com.zenoue.ccbridge.feature

import android.app.Activity
import android.app.Service
import com.zenoue.ccbridge.api.ActivityDelegate
import com.zenoue.ccbridge.api.FeatureEntry
import com.zenoue.ccbridge.api.HostApi
import com.zenoue.ccbridge.api.ServiceDelegate

/** Reference to the APK shell so the feature can call back (intents, icon, updates). */
object Host {
    lateinit var api: HostApi
    val ready: Boolean get() = ::api.isInitialized
}

/** Entry class of feature.dex (fixed name: [com.zenoue.ccbridge.api.FEATURE_ENTRY_CLASS]). */
class Entry : FeatureEntry {
    override fun attach(host: HostApi) { Host.api = host }
    override fun activity(activity: Activity): ActivityDelegate = MainScreen(activity)
    override fun service(service: Service): ServiceDelegate = BridgeCore(service)
}
