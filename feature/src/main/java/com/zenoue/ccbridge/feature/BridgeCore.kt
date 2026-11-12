package com.zenoue.ccbridge.feature

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.PowerManager
import com.zenoue.ccbridge.api.ServiceDelegate

/**
 * Core of the foreground service (lives in feature.dex; the APK only forwards the Service lifecycle):
 * HTTP server on 127.0.0.1:<port> + localhost.run tunnel to a public https URL + [Pointer] that keeps
 * a fixed address pointing at the current tunnel URL.
 * Claude Code posts signed commands to `<tunnel url>/cmd`; they run through the in-app ADB client.
 *
 * Status texts, notifications and [Console] lines are UI text (Vietnamese).
 */
class BridgeCore(private val svc: Service) : ContextWrapper(svc), ServiceDelegate {

    companion object {
        private const val CHANNEL = "bridge"
        private const val NOTIF_ID = 11
        @Volatile var running = false; private set
        @Volatile var status = L.t("Chưa chạy", "Not running"); private set
        @Volatile var tunnel: Tunnel.State? = null; private set
        @Volatile var onStatus: (() -> Unit)? = null

        @Volatile var pointer: Pointer.State? = null; private set
        @Volatile private var instance: BridgeCore? = null

        /** Current public tunnel URL (changes on reconnect). */
        val tunnelUrl: String? get() = tunnel?.url

        /** Republish the fixed address (bridge id or password changed in the UI). */
        fun refreshPointer() { instance?.ptr?.refresh() }

        fun start(ctx: Context) {
            val i = Host.api.serviceIntent()
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun stop(ctx: Context) = ctx.stopService(Host.api.serviceIntent())
    }

    @Volatile private var alive = false
    private var worker: Thread? = null
    private var server: LocalServer? = null
    private var tun: Tunnel? = null
    private var ptr: Pointer? = null
    private var wake: PowerManager.WakeLock? = null
    private var netCb: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        createChannel()
        if (Build.VERSION.SDK_INT >= 34) svc.startForeground(NOTIF_ID, notif(L.t("Đang khởi động…", "Starting…")), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else svc.startForeground(NOTIF_ID, notif(L.t("Đang khởi động…", "Starting…")))
        alive = true; running = true; instance = this
        // keep the CPU awake with the screen off so the tunnel does not stall
        wake = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ccbridge:bridge")?.apply { setReferenceCounted(false); acquire() }
        worker = Thread { loop() }.apply { isDaemon = true; name = "bridge"; start() }
        Host.api.checkUpdate(false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = Service.START_STICKY

    override fun onDestroy() {
        alive = false; running = false
        worker?.interrupt()
        netCb?.let { try { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it) } catch (_: Throwable) { } }
        netCb = null
        tun?.stop(); tun = null
        ptr?.stop(); ptr = null
        instance = null; pointer = null
        server?.stop(); server = null
        LogStream.stop()
        try { wake?.release() } catch (_: Throwable) { }
        tunnel = null
        status = L.t("Đã dừng", "Stopped"); onStatus?.invoke()
        getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
        Console.info(L.t("Cầu nối đã dừng", "Bridge stopped"))
    }

    private var keptAlive = false

    /**
     * ColorOS/MIUI and friends kill background apps -> the app restarts -> the tunnel gets a new URL.
     * The app has shell rights through ADB, so it exempts itself from battery optimisation and
     * background limits (once per bridge start).
     */
    private fun keepAlive() {
        if (keptAlive || !Adb.isConnected(this)) return
        keptAlive = true
        val pkg = packageName
        val r = try {
            Adb.run(this, "cmd deviceidle whitelist +$pkg; cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow; " +
                "cmd appops set $pkg RUN_IN_BACKGROUND allow; am set-standby-bucket $pkg active", 15_000)
        } catch (t: Throwable) { Adb.onCommandError(this, t) }
        Console.info(L.t("Chống bị hệ thống tắt nền: ", "Protection from being killed in the background: ") + if (r.exit == 0) L.t("đã bật", "on") else L.t("lỗi ${r.exit}: ${r.output.take(200)}", "error ${r.exit}: ${r.output.take(200)}"))
    }

    private fun tryConnectAdb(cfg: Config) {
        if (Adb.isConnected(this)) { keepAlive(); return }
        // classic TCP port 5555 (no Wi-Fi needed) first, then wireless debugging
        if (cfg.tcpMode && Adb.tcpPortOpen() && Adb.connect(this, "127.0.0.1", Adb.TCP_PORT)) {
            Console.info(L.t("ADB kết nối 127.0.0.1:${Adb.TCP_PORT} (không cần Wi‑Fi)", "ADB connected to 127.0.0.1:${Adb.TCP_PORT} (no Wi‑Fi needed)")); return
        }
        if (!cfg.paired || cfg.adbPort <= 0) return
        if (Adb.connect(this, cfg.adbHost, cfg.adbPort)) Console.info(L.t("ADB kết nối ${cfg.adbHost}:${cfg.adbPort}", "ADB connected to ${cfg.adbHost}:${cfg.adbPort}"))
    }

    private fun loop() {
        val cfg = Config(this)
        tryConnectAdb(cfg)
        if (!startTunnel(cfg)) { svc.stopSelf(); return }
        watchNetwork()
        while (alive) {
            try {
                if (!Adb.isConnected(this)) tryConnectAdb(cfg)
                val adb = if (Adb.isConnected(this)) "ADB ✓" else L.t("ADB chưa kết nối", "ADB not connected")
                val t = when (tunnel?.phase) {
                    Tunnel.Phase.ONLINE -> L.t("Đường hầm ✓", "Tunnel ✓")
                    Tunnel.Phase.RETRYING -> L.t("Đang nối lại đường hầm…", "Reconnecting the tunnel…")
                    else -> L.t("Đang mở đường hầm…", "Opening the tunnel…")
                }
                setStatus("$t · $adb")
            } catch (t: Throwable) {
                Console.err(L.t("Lỗi vòng lặp: ${t.message}", "Loop error: ${t.message}"))
            }
            try { Thread.sleep(3000) } catch (_: InterruptedException) { break }
        }
    }

    private fun startTunnel(cfg: Config): Boolean {
        val api = HttpApi({ cfg.password }, { type, cmd, t -> Commands.run(this, cfg, type, cmd, t) })
        try {
            server = LocalServer(cfg.httpPort, api::handle).also { it.start() }
        } catch (t: Throwable) {
            Console.err(L.t("Không mở được cổng ${cfg.httpPort}: ${t.message}", "Cannot open port ${cfg.httpPort}: ${t.message}"))
            setStatus(L.t("Lỗi cổng ${cfg.httpPort}", "Port ${cfg.httpPort} error"))
            return false
        }
        val p = Pointer(Pointer.DEFAULT_BASE, { cfg.bridgeId }, { cfg.password }) { st ->
            val old = pointer
            pointer = st
            if (st.ok == true && (old?.ok != true || old.url != st.url)) Console.info(L.t("Địa chỉ cố định ${cfg.bridgeId} → ${st.url}", "Fixed address ${cfg.bridgeId} → ${st.url}"))
            else if (st.ok == false && old?.message != st.message) Console.err(st.message)
            onStatus?.invoke()
        }
        ptr = p.also { it.start() }
        tun = Tunnel(cfg.httpPort, cfg.sshKey) { st ->
            val old = tunnel
            tunnel = st
            p.set(st.url)
            when {
                st.phase == Tunnel.Phase.ONLINE && st.url != old?.url -> Console.info(L.t("Đường hầm: ${st.url}", "Tunnel: ${st.url}"))
                st.phase == Tunnel.Phase.RETRYING && st.message != old?.message -> Console.err(st.message)
            }
            onStatus?.invoke()
        }.also { it.start() }
        return true
    }

    /** On a network switch (Wi-Fi <-> mobile data) reconnect the tunnel right away instead of waiting for SSH keep-alive. */
    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            private var current: Network? = null
            override fun onAvailable(network: Network) {
                val prev = current
                current = network
                if (prev != null && prev != network) {
                    Console.info(L.t("Mạng thay đổi – nối lại đường hầm", "Network changed – reconnecting the tunnel"))
                    tun?.kick()
                }
            }
        }
        try { cm.registerDefaultNetworkCallback(cb); netCb = cb } catch (_: Throwable) { }
    }

    // ───────── notification ─────────

    private fun setStatus(s: String) {
        if (s == status) return
        status = s; onStatus?.invoke()
        if (alive) getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, notif(s))
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL, L.t("Cầu nối ADB", "ADB bridge"), NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
    }

    private fun notif(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Host.api.activityIntent(),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(Host.api.notificationIcon)
            .setContentTitle("Claude Code ↔ ADB")
            .setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(pi)
            .build()
    }
}
