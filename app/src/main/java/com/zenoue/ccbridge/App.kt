package com.zenoue.ccbridge

import android.app.Application
import android.os.Build
import org.conscrypt.Conscrypt
import java.io.File
import java.security.Security

/**
 * - Installs the bundled Conscrypt as the first security provider (TLS 1.3 + exported keying material
 *   for wireless ADB pairing; unlike the platform's hidden Conscrypt it is not blocked for apps).
 * - Initialises the feature loader/updater (main process only).
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
