package com.zenoue.ccbridge.feature

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.zenoue.ccbridge.api.ActivityDelegate
import java.util.concurrent.Executors

/**
 * Main screen (lives in feature.dex; the APK only forwards the Activity lifecycle). Two tabs:
 *  - CLI: type adb shell commands and see results; commands sent by Claude Code show up here too.
 *  - Connect: wireless debugging (in-app ADB), tunnel + fixed address, password, feature updates.
 *
 * All strings in this file are UI text and stay Vietnamese.
 */
class MainScreen(private val activity: Activity) : ContextWrapper(activity), ActivityDelegate {

    private lateinit var p: Palette
    private lateinit var ui: UiKit
    private lateinit var cfg: Config
    private lateinit var container: LinearLayout
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool()

    private val tabViews = ArrayList<TextView>()
    private var tab = TAB_CLI

    // CLI
    private var consoleList: LinearLayout? = null
    private var consoleScroll: ScrollView? = null
    private var cliHint: TextView? = null
    private var lastShownId = 0L
    private var input: EditText? = null
    private val history = ArrayList<String>()

    private var statusLabel: TextView? = null

    companion object {
        const val TAB_CLI = 0
        const val TAB_CONNECT = 1
        private const val EMPTY_TAG = "empty"
        private val QUICK = listOf(
            "getprop ro.product.model", "dumpsys battery", "pm list packages -3",
            "df -h", "ip addr show wlan0", "logcat -d -t 100",
        )
    }

    override fun onCreate(saved: Bundle?) {
        p = Palette(this); ui = UiKit(this, p); cfg = Config(this)

        val root = ui.column().apply { setBackgroundColor(p.bg) }
        val head = ui.column().apply { setPadding(dp(16), dp(14), dp(16), dp(6)) }
        head.addView(TextView(this).apply {
            text = "Claude Code ↔ ADB"; textSize = 20f; typeface = Typeface.DEFAULT_BOLD; setTextColor(p.text)
        })
        statusLabel = TextView(this).apply { textSize = 12.5f; setTextColor(p.muted) }
        head.addView(statusLabel)
        root.addView(head)

        // tab bar
        val bar = ui.row().apply { setPadding(dp(12), 0, dp(12), dp(6)) }
        listOf("CLI", "Connect").forEachIndexed { i, name ->
            val tv = TextView(this).apply {
                text = name; gravity = Gravity.CENTER; textSize = 14.5f
                setPadding(0, dp(10), 0, dp(10))
                setOnClickListener { selectTab(i) }
            }
            tabViews.add(tv)
            bar.addView(tv, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = dp(8) })
        }
        root.addView(bar)

        container = ui.column().apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) }
        root.addView(container)
        activity.setContentView(root)

        Console.onChange = { main.post { if (tab == TAB_CLI) appendNewConsole() } }
        BridgeCore.onStatus = { main.post { refreshStatus() } }
        selectTab(if (Adb.isConnected(this)) TAB_CLI else TAB_CONNECT)
        // TCP port 5555 was enabled earlier: reconnect on app start (no Wi-Fi needed)
        if (!Adb.isConnected(this) && cfg.tcpMode) io.execute {
            if (Adb.tcpPortOpen() && Adb.connect(this, "127.0.0.1", Adb.TCP_PORT)) main.post {
                Console.info(L.t("ADB kết nối 127.0.0.1:${Adb.TCP_PORT} (không cần Wi‑Fi)", "ADB connected to 127.0.0.1:${Adb.TCP_PORT} (no Wi‑Fi needed)"))
                selectTab(TAB_CLI)
            }
        }
    }

    override fun onResume() {
        refreshStatus()
        Host.api.setUpdateListener { main.post { refreshUpdate() } }
        Host.api.checkUpdate(false)
    }

    override fun onPause() { Host.api.setUpdateListener(null) }

    override fun onDestroy() {
        Console.onChange = null
        BridgeCore.onStatus = null
        Host.api.setUpdateListener(null)
        io.shutdownNow()
    }

    private fun refreshStatus() {
        val adb = Adb.isConnected(this)
        statusLabel?.text = if (BridgeCore.running) "● " + BridgeCore.status
            else L.t("○ Cầu nối tắt · ADB ${if (adb) "✓" else "✗"}", "○ Bridge off · ADB ${if (adb) "✓" else "✗"}")
        statusLabel?.setTextColor(if (BridgeCore.running) p.accent else p.muted)
        cliHint?.text = cliHintText()
        tunnelLabel?.let {
            it.text = tunnelText()
            it.setTextColor(if (BridgeCore.tunnelUrl != null) p.accent else p.muted)
        }
        pointerLabel?.let {
            it.text = pointerText()
            it.setTextColor(when (BridgeCore.pointer?.ok) { true -> p.accent; false -> p.warn; else -> p.muted })
        }
    }

    private fun selectTab(i: Int) {
        tab = i
        tabViews.forEachIndexed { idx, tv ->
            val on = idx == i
            tv.setTextColor(if (on) p.onAccent else p.muted)
            tv.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            tv.background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(if (on) p.accent else p.card)
            }
        }
        container.removeAllViews()
        consoleList = null; consoleScroll = null; cliHint = null; input = null; tunnelLabel = null; pointerLabel = null
        updateLabel = null; applyButton = null
        if (i == TAB_CLI) container.addView(cliTab()) else container.addView(connectTab())
        refreshStatus()
    }

    // ───────────────────────── CLI tab ─────────────────────────

    private fun cliTab(): LinearLayout {
        val col = ui.column().apply { layoutParams = LinearLayout.LayoutParams(-1, -1) }

        val sv = ScrollView(this).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply { setColor(p.term) }
        }
        val list = ui.column()
        sv.addView(list)
        consoleScroll = sv; consoleList = list
        col.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f).apply { leftMargin = dp(10); rightMargin = dp(10) })
        renderConsoleAll()

        val barWrap = ui.column().apply { setPadding(dp(12), dp(8), dp(12), dp(10)) }

        // quick commands
        val chips = ui.row()
        for (q in QUICK) {
            chips.addView(TextView(this).apply {
                text = q; textSize = 12f; typeface = Typeface.MONOSPACE; setTextColor(p.accent)
                background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setStroke(dp(1), p.line) }
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setOnClickListener { input?.setText(q); input?.setSelection(q.length) }
            }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
        }
        barWrap.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; addView(chips)
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        val inputRow = ui.row()
        val prompt = TextView(this).apply {
            text = "$"; setTextColor(p.accent); textSize = 15f; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(0, 0, dp(8), 0)
        }
        inputRow.addView(prompt)
        val et = EditText(this).apply {
            hint = L.t("adb shell …  (vd: dumpsys battery)", "adb shell …  (e.g. dumpsys battery)")
            setTextColor(p.text); setHintTextColor(p.muted); textSize = 13.5f; typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEND
            maxLines = 3
            background = GradientDrawable().apply { setColor(p.field); cornerRadius = dp(10).toFloat() }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnEditorActionListener { v, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                    runLocal(text.toString()); (v as EditText).text.clear(); true
                } else false
            }
        }
        input = et
        inputRow.addView(et, LinearLayout.LayoutParams(0, -2, 1f))
        val runBtn = TextView(this).apply {
            text = L.t("Chạy", "Run"); setTextColor(p.onAccent); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(p.accent); cornerRadius = dp(10).toFloat() }
            setPadding(dp(18), dp(10), dp(18), dp(10))
            setOnClickListener { runLocal(et.text.toString()); et.text.clear() }
        }
        inputRow.addView(runBtn, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        barWrap.addView(inputRow)

        val hintRow = ui.row().apply { setPadding(0, dp(6), 0, 0) }
        cliHint = ui.muted(cliHintText())
        hintRow.addView(cliHint, LinearLayout.LayoutParams(0, -2, 1f))
        hintRow.addView(linkText(L.t("↑ Lệnh trước", "↑ Previous")) {
            if (history.isNotEmpty()) { val h = history.last(); et.setText(h); et.setSelection(h.length) }
        })
        hintRow.addView(linkText(L.t("Sao chép", "Copy")) { copy(Console.dump()); toast(L.t("Đã sao chép nhật ký", "Log copied")) })
        hintRow.addView(linkText(L.t("Xoá", "Clear")) { Console.clear(); renderConsoleAll() })
        barWrap.addView(hintRow)
        col.addView(barWrap)
        return col
    }

    private fun linkText(s: String, onClick: () -> Unit) = TextView(this).apply {
        text = s; setTextColor(p.accent); textSize = 12.5f
        setPadding(dp(10), dp(4), 0, dp(4))
        setOnClickListener { onClick() }
    }

    private fun cliHintText(): String = when {
        !Adb.isConnected(this) -> L.t("ADB chưa kết nối — sang tab Connect", "ADB not connected — go to the Connect tab")
        BridgeCore.running -> L.t("Đang nhận lệnh từ Claude Code", "Receiving commands from Claude Code")
        else -> L.t("ADB ✓ · cầu nối đang tắt", "ADB ✓ · bridge is off")
    }

    private fun renderConsoleAll() {
        val list = consoleList ?: return
        list.removeAllViews()
        lastShownId = 0L
        if (Console.snapshot().isEmpty()) {
            list.addView(ui.muted(L.t("Chưa có lệnh. Gõ lệnh bên dưới, hoặc để Claude Code gửi đến (dòng claude$).", "No commands yet. Type one below, or let Claude Code send some (claude$ lines)."))
                .apply { tag = EMPTY_TAG })
        }
        appendNewConsole()
    }

    private fun appendNewConsole() {
        val list = consoleList ?: return
        val fresh = Console.since(lastShownId)
        if (fresh.isEmpty()) {
            // console was cleared elsewhere -> redraw
            if (Console.snapshot().isEmpty() && list.childCount > 0 && list.getChildAt(0).tag != EMPTY_TAG) renderConsoleAll()
            return
        }
        if (list.childCount > 0 && list.getChildAt(0).tag == EMPTY_TAG) list.removeViewAt(0)
        for (e in fresh) { list.addView(entryView(e)); lastShownId = e.id }
        while (list.childCount > Console.MAX) list.removeViewAt(0)
        consoleScroll?.post { consoleScroll?.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun entryView(e: Console.Entry): TextView {
        val (prefix, color, bold) = when (e.kind) {
            Console.Kind.CMD_LOCAL -> Triple("$ ", p.accent, true)
            Console.Kind.CMD_REMOTE -> Triple("claude$ ", p.series2, true)
            Console.Kind.OUT -> Triple("", p.text, false)
            Console.Kind.INFO -> Triple("· ", p.muted, false)
            Console.Kind.ERR -> Triple("✗ ", p.bad, false)
        }
        return TextView(this).apply {
            val time = if (e.kind == Console.Kind.OUT) "" else "  [${Fmt.clock(e.time)}]"
            text = prefix + e.text + time
            setTextColor(color); textSize = 12f
            typeface = if (bold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, if (bold) dp(8) else dp(1), 0, dp(1))
        }
    }

    private fun runLocal(raw: String) {
        val cmd = raw.trim()
        if (cmd.isEmpty()) return
        if (history.lastOrNull() != cmd) history.add(cmd)
        Console.cmdLocal(cmd)
        if (!Adb.isConnected(this)) { Console.err(L.t("ADB chưa kết nối — sang tab Connect để ghép nối", "ADB not connected — pair in the Connect tab")); return }
        if (!cfg.allowWrite && !Policy.allowed(cmd)) {
            Console.err(L.t("Bị chặn ở chế độ chỉ-đọc (bật \"Cho phép mọi lệnh\" ở tab Connect)", "Blocked in read-only mode (turn on \"Allow all commands\" in the Connect tab)")); return
        }
        io.execute {
            val r = try { Adb.run(this, cmd, 120_000) } catch (t: Throwable) { Adb.onCommandError(this, t) }
            Console.out(r.output.ifBlank { L.t("(không có output)", "(no output)") })
            if (r.exit != 0) Console.err("exit ${r.exit}")
            main.post { refreshStatus() }
        }
    }

    // ───────────────────────── Connect tab ─────────────────────────

    private var tunnelLabel: TextView? = null
    private var pointerLabel: TextView? = null

    private fun connectTab(): ScrollView {
        val sv = ScrollView(this).apply { isFillViewport = true }
        val col = ui.column().apply { setPadding(dp(16), dp(8), dp(16), dp(28)) }
        sv.addView(col)
        col.addView(bridgeCard())
        col.addView(adbCard())
        col.addView(securityCard())
        col.addView(updateCard())
        return sv
    }

    private fun tunnelText(): String {
        val st = BridgeCore.tunnel
        return when {
            !BridgeCore.running -> L.t("Bấm \"Bắt đầu cầu nối\" để mở đường hầm", "Tap \"Start bridge\" to open the tunnel")
            st?.url != null -> st.url
            else -> st?.message ?: L.t("Đang mở đường hầm…", "Opening the tunnel…")
        }
    }

    private fun pointerText(): String {
        val st = BridgeCore.pointer
        return when {
            !BridgeCore.running -> L.t("Cố định, không đổi khi hầm nối lại", "Fixed; it stays the same when the tunnel reconnects")
            st == null || st.ok == null -> L.t("Đang chờ đường hầm…", "Waiting for the tunnel…")
            st.ok == true -> "✓ ${st.message} (${Fmt.clock(st.at)})"
            else -> "✗ ${st.message}"
        }
    }

    /** Block pasted into Claude Code: downloads the client and explains it, no repository clone needed. */
    private fun envBlock(): String =
        ClaudeSetup.block(Host.api.repo, cfg.bridgeId, cfg.password, BridgeCore.tunnelUrl)

    private fun bridgeCard(): LinearLayout {
        val c = ui.card()
        c.addView(ui.title(L.t("Cầu nối", "Bridge")))
        c.addView(ui.kv(L.t("Trạng thái", "Status"), if (BridgeCore.running) BridgeCore.status else L.t("Tắt", "Off"),
            if (BridgeCore.running) p.accent else p.muted))

        c.addView(ui.label(L.t("Địa chỉ cố định (BRIDGE_ID)", "Fixed address (BRIDGE_ID)")))
        c.addView(ui.mono(cfg.bridgeId).apply { textSize = 13f; setTextColor(p.text); setPadding(0, dp(4), 0, 0) })
        pointerLabel = ui.muted(pointerText())
        c.addView(pointerLabel)

        c.addView(ui.label(L.t("Địa chỉ hầm hiện tại (localhost.run, tự đổi)", "Current tunnel address (localhost.run, changes by itself)")))
        tunnelLabel = ui.mono(tunnelText()).apply {
            textSize = 13f; setTextColor(if (BridgeCore.tunnelUrl != null) p.accent else p.muted)
            setPadding(0, dp(4), 0, dp(4))
        }
        c.addView(tunnelLabel)
        c.addView(ui.button(if (BridgeCore.running) L.t("Dừng cầu nối", "Stop bridge") else L.t("Bắt đầu cầu nối", "Start bridge"), !BridgeCore.running) {
            if (BridgeCore.running) BridgeCore.stop(this)
            else {
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                BridgeCore.start(this)
            }
            main.postDelayed({ if (tab == TAB_CONNECT) selectTab(TAB_CONNECT) }, 600)
        })
        c.addView(ui.button(L.t("Sao chép cho Claude Code", "Copy for Claude Code"), primary = false) {
            copy(envBlock()); toast(L.t("Đã sao chép (có chứa mật khẩu)", "Copied (contains the password)"))
        })
        c.addView(ui.muted(L.t("Chỉ cần sao chép MỘT LẦN rồi dán vào Claude Code – không cần tải repo: ", "Copy only ONCE and paste it into Claude Code – no need to download the repo: ") +
            L.t("đoạn sao chép đã kèm lệnh tải công cụ và hướng dẫn sử dụng cho Claude. ", "the copied text includes the command that downloads the tool and usage notes for Claude. ") +
            L.t("Hầm nối lại hay đổi địa chỉ thì Claude Code tự tìm theo địa chỉ cố định. ", "When the tunnel reconnects or changes address, Claude Code finds it through the fixed address. ") +
            L.t("Chỉ phải sao chép lại khi đổi mật khẩu hoặc đổi địa chỉ cố định.", "Copy again only after changing the password or the fixed address.")))
        return c
    }

    private fun adbStateText() =
        if (Adb.isConnected(this)) L.t("Đã kết nối · ", "Connected · ") + (if (Adb.viaTcp) L.t("TCP ${Adb.TCP_PORT} (không cần Wi‑Fi)", "TCP ${Adb.TCP_PORT} (no Wi‑Fi needed)") else L.t("không dây (TLS)", "wireless (TLS)")) else if (cfg.paired) L.t("Đã ghép nối", "Paired") else L.t("Chưa ghép nối", "Not paired")

    private fun adbCard(): LinearLayout {
        val c = ui.card()
        c.addView(ui.title(L.t("Gỡ lỗi không dây (ADB)", "Wireless debugging (ADB)")))
        c.addView(ui.muted(L.t("Cài đặt → Tuỳ chọn nhà phát triển → Gỡ lỗi không dây (Android 11+). ", "Settings → Developer options → Wireless debugging (Android 11+). ") +
            L.t("ADB chạy ngay trong app, không cần root, Shizuku hay PC. Nên dùng chế độ chia đôi màn hình ", "ADB runs inside the app: no root, Shizuku or PC. Split screen helps: ") +
            L.t("để vừa xem mã ghép nối vừa nhập vào app.", "you can read the pairing code while typing it into the app.")))
        c.addView(ui.kv(L.t("Tình trạng", "State"), adbStateText(), if (Adb.isConnected(this)) p.accent else p.muted))

        c.addView(ui.label(L.t("Bước 1 – Ghép nối (chỉ một lần): \"Ghép nối thiết bị bằng mã ghép nối\"", "Step 1 – Pair (only once): \"Pair device with pairing code\"")))
        val pairPort = ui.field(L.t("cổng ghép nối, vd 41055", "pairing port, e.g. 41055"), "", numeric = true)
        val pairCode = ui.field(L.t("mã ghép nối 6 số", "6-digit pairing code"), "", numeric = true)
        c.addView(pairPort); c.addView(pairCode)
        val pairLog = ui.muted("")
        c.addView(ui.button(L.t("Ghép nối", "Pair")) {
            val port = pairPort.text.toString().trim().toIntOrNull()
            val code = pairCode.text.toString().trim()
            if (port == null || code.length < 6) { toast(L.t("Nhập cổng và mã 6 số", "Enter the port and the 6-digit code")); return@button }
            pairLog.text = L.t("Đang ghép nối…", "Pairing…")
            io.execute {
                val ok = Adb.pair(this, "127.0.0.1", port, code)
                main.post {
                    pairLog.text = if (ok) L.t("✓ Ghép nối xong — làm tiếp Bước 2", "✓ Paired — continue with Step 2") else "✗ ${Adb.lastError}"
                    if (ok) { cfg.paired = true; Console.info(L.t("ADB đã ghép nối", "ADB paired")) }
                }
            }
        })
        c.addView(pairLog)

        c.addView(ui.label(L.t("Bước 2 – Kết nối: \"Địa chỉ IP và cổng\" trên màn hình Gỡ lỗi không dây", "Step 2 – Connect: \"IP address & Port\" on the Wireless debugging screen")))
        val conPort = ui.field(L.t("cổng kết nối, vd 37219", "connect port, e.g. 37219"), if (cfg.adbPort > 0) cfg.adbPort.toString() else "", numeric = true)
        c.addView(conPort)
        val conLog = ui.muted("")
        c.addView(ui.button(L.t("Kết nối", "Connect"), primary = !Adb.isConnected(this)) {
            val port = conPort.text.toString().trim().toIntOrNull()
            if (port == null) { toast(L.t("Nhập cổng kết nối", "Enter the connect port")); return@button }
            cfg.adbPort = port; cfg.adbHost = "127.0.0.1"; conLog.text = L.t("Đang kết nối…", "Connecting…")
            io.execute {
                val ok = Adb.connect(this, "127.0.0.1", port)
                main.post {
                    conLog.text = if (ok) L.t("✓ Đã kết nối ADB", "✓ ADB connected") else "✗ ${Adb.lastError}"
                    if (ok) { cfg.paired = true; Console.info(L.t("ADB kết nối 127.0.0.1:$port", "ADB connected to 127.0.0.1:$port")) }
                    refreshStatus()
                }
            }
        })
        c.addView(ui.button(L.t("Tự dò cổng (mDNS)", "Find the port (mDNS)"), primary = false) {
            conLog.text = L.t("Đang dò…", "Searching…")
            io.execute {
                val ok = Adb.autoConnect(this)
                main.post {
                    conLog.text = if (ok) L.t("✓ Đã kết nối", "✓ Connected") else "✗ ${Adb.lastError}"
                    if (ok) { cfg.paired = true; Console.info(L.t("ADB kết nối (mDNS)", "ADB connected (mDNS)")) }
                    refreshStatus()
                }
            }
        })
        if (Adb.isConnected(this)) c.addView(ui.button(L.t("Ngắt ADB", "Disconnect ADB"), primary = false) {
            Adb.disconnect(this); selectTab(TAB_CONNECT)
        })
        c.addView(conLog)
        noWifiSection(c)
        c.addView(ui.button(L.t("Mở Tuỳ chọn nhà phát triển", "Open Developer options"), primary = false) {
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) } catch (_: Exception) { toast(L.t("Không mở được", "Could not open it")) }
        })
        return c
    }

    /**
     * Debugging without Wi-Fi: Android (17 and ADB Wi-Fi 2.0 included) only enables wireless debugging
     * while on Wi-Fi and turns it off when the network is left. While ADB is connected, send `tcpip:5555`
     * so adbd also listens on a plain TCP port on the device, then connect to 127.0.0.1:5555 - independent
     * of Wi-Fi, valid until the phone reboots.
     */
    private fun noWifiSection(c: LinearLayout) {
        c.addView(ui.divider())
        c.addView(ui.label(L.t("Gỡ lỗi không cần Wi‑Fi (Android 11–17)", "Debugging without Wi‑Fi (Android 11–17)")))
        c.addView(ui.muted(L.t("Android chỉ bật Gỡ lỗi không dây khi đang nối Wi‑Fi. Bấm \"Bật\" một lần khi ADB đang kết nối: ", "Android only enables Wireless debugging while on Wi‑Fi. Tap \"Enable\" once while ADB is connected: ") +
            L.t("adbd mở thêm cổng ${Adb.TCP_PORT} ngay trên máy, sau đó tắt Wi‑Fi / chỉ dùng 4G vẫn kết nối được ", "adbd also opens port ${Adb.TCP_PORT} on the phone, and the connection keeps working with Wi‑Fi off / on mobile data ") +
            L.t("cho tới khi khởi động lại điện thoại. Lần đầu máy hỏi \"Cho phép gỡ lỗi USB?\" → tích Luôn cho phép → Cho phép.", "until the phone reboots. The first time the phone asks \"Allow USB debugging?\" → tick Always allow → Allow.")))
        // check the port off the UI thread (a socket on the UI thread throws NetworkOnMainThreadException)
        val portLabel = ui.body(L.t("Cổng ${Adb.TCP_PORT}: đang kiểm tra…", "Port ${Adb.TCP_PORT}: checking…"), p.muted, 13f)
        c.addView(portLabel)
        io.execute {
            val open = Adb.tcpPortOpen()
            main.post {
                portLabel.text = L.t("Cổng ${Adb.TCP_PORT}: ", "Port ${Adb.TCP_PORT}: ") + if (open) L.t("đang mở", "open") else L.t("đóng", "closed")
                portLabel.setTextColor(if (open) p.accent else p.muted)
            }
        }
        val log = ui.muted("")

        c.addView(ui.button(L.t("Bật ADB không cần Wi‑Fi", "Enable ADB without Wi‑Fi"), primary = Adb.isConnected(this) && !cfg.tcpMode) {
            if (!Adb.isConnected(this)) { toast(L.t("Cần kết nối ADB trước (Bước 1–2, đang có Wi‑Fi)", "Connect ADB first (Steps 1–2, while on Wi‑Fi)")); return@button }
            log.text = L.t("Đang chuyển adbd sang cổng ${Adb.TCP_PORT}…", "Switching adbd to port ${Adb.TCP_PORT}…")
            io.execute {
                val msg = try { Adb.enableTcpip(this) } catch (t: Throwable) { "✗ ${t.message ?: t}" }
                Console.info("adbd: $msg")
                main.post { log.text = L.t("$msg\nĐang nối 127.0.0.1:${Adb.TCP_PORT} – nếu máy hỏi, bấm Cho phép…", "$msg\nConnecting to 127.0.0.1:${Adb.TCP_PORT} – tap Allow if the phone asks…") }
                Thread.sleep(1500)
                val ok = Adb.connectTcp(this)
                if (ok) cfg.tcpMode = true
                main.post {
                    log.text = if (ok) L.t("✓ Đã kết nối qua cổng ${Adb.TCP_PORT} – giờ có thể tắt Wi‑Fi", "✓ Connected through port ${Adb.TCP_PORT} – Wi‑Fi can be turned off now") else "✗ ${Adb.lastError}"
                    if (ok) Console.info(L.t("ADB kết nối 127.0.0.1:${Adb.TCP_PORT} (không cần Wi‑Fi)", "ADB connected to 127.0.0.1:${Adb.TCP_PORT} (no Wi‑Fi needed)"))
                    else {
                        // TCP port did not work -> fall back to wireless debugging if available
                        if (cfg.adbPort > 0) io.execute { Adb.connect(this, "127.0.0.1", cfg.adbPort); main.post { refreshStatus() } }
                    }
                    refreshStatus()
                }
            }
        })
        c.addView(ui.button(L.t("Kết nối không cần Wi‑Fi (127.0.0.1:${Adb.TCP_PORT})", "Connect without Wi‑Fi (127.0.0.1:${Adb.TCP_PORT})"), primary = false) {
            log.text = L.t("Đang nối 127.0.0.1:${Adb.TCP_PORT}…", "Connecting to 127.0.0.1:${Adb.TCP_PORT}…")
            io.execute {
                if (Adb.isConnected(this)) Adb.disconnect(this)
                val ok = Adb.connectTcp(this, attempts = 2)
                if (ok) cfg.tcpMode = true
                main.post {
                    log.text = if (ok) L.t("✓ Đã kết nối qua cổng ${Adb.TCP_PORT}", "✓ Connected through port ${Adb.TCP_PORT}") else "✗ ${Adb.lastError}"
                    if (ok) Console.info(L.t("ADB kết nối 127.0.0.1:${Adb.TCP_PORT} (không cần Wi‑Fi)", "ADB connected to 127.0.0.1:${Adb.TCP_PORT} (no Wi‑Fi needed)"))
                    refreshStatus()
                }
            }
        })
        if (cfg.tcpMode) c.addView(ui.button(L.t("Tắt cổng ${Adb.TCP_PORT}", "Close port ${Adb.TCP_PORT}"), primary = false) {
            cfg.tcpMode = false
            if (!Adb.isConnected(this)) { toast(L.t("Đã tắt tự nối cổng ${Adb.TCP_PORT}; cổng đóng khi khởi động lại máy", "Auto-connect to port ${Adb.TCP_PORT} is off; the port closes when the phone reboots")); selectTab(TAB_CONNECT); return@button }
            io.execute {
                val msg = try { Adb.disableTcpip(this) } catch (t: Throwable) { "✗ ${t.message ?: t}" }
                Console.info("adbd: $msg")
                main.post { toast(L.t("Đã tắt cổng ${Adb.TCP_PORT} – nối lại bằng Gỡ lỗi không dây", "Port ${Adb.TCP_PORT} closed – reconnect with Wireless debugging")); selectTab(TAB_CONNECT) }
            }
        })
        c.addView(log)
        c.addView(ui.muted(L.t("Cổng ${Adb.TCP_PORT} nghe trên mọi mạng của máy; máy lạ vẫn phải được bạn bấm Cho phép mới vào được. ", "Port ${Adb.TCP_PORT} listens on every network of the phone; an unknown device still needs you to tap Allow. ") +
            L.t("Tắt cổng khi dùng Wi‑Fi công cộng.", "Close the port on public Wi‑Fi.")))
    }

    private fun securityCard(): LinearLayout {
        val c = ui.card()
        c.addView(ui.title(L.t("Mật khẩu & chế độ", "Password & mode")))
        c.addView(ui.label(L.t("Mật khẩu ký lệnh (≥${Config.MIN_PASSWORD} ký tự, chia sẻ với Claude Code)", "Password that signs commands (≥${Config.MIN_PASSWORD} characters, shared with Claude Code)")))
        val fPass = ui.field(L.t("mật khẩu", "password"), cfg.password, password = true); c.addView(fPass)
        c.addView(ui.button(L.t("Tạo mật khẩu ngẫu nhiên", "Generate a random password"), primary = false) { fPass.setText(Config.random(24)) })
        c.addView(ui.button(L.t("Lưu mật khẩu", "Save password")) {
            val pw = fPass.text.toString()
            if (pw.length < Config.MIN_PASSWORD) { toast(L.t("Mật khẩu cần ≥${Config.MIN_PASSWORD} ký tự", "The password needs ≥${Config.MIN_PASSWORD} characters")); return@button }
            if (pw.contains('\'')) { toast(L.t("Mật khẩu không được chứa dấu nháy đơn", "The password must not contain a single quote")); return@button }
            cfg.password = pw
            BridgeCore.refreshPointer()
            toast(L.t("Đã lưu – sao chép lại cho Claude Code", "Saved – copy for Claude Code again"))
        })
        c.addView(ui.muted(L.t("Mật khẩu không bao giờ gửi qua mạng: mỗi lệnh được ký HMAC bằng mật khẩu, ", "The password is never sent over the network: each command is HMAC-signed with it, ") +
            L.t("app chỉ chạy lệnh có chữ ký đúng. Ai biết địa chỉ mà không có mật khẩu thì không làm được gì.", "and the app only runs correctly signed commands. Knowing the address without the password is useless.")))

        c.addView(ui.button(L.t("Đổi địa chỉ cố định", "Change the fixed address"), primary = false) {
            cfg.newBridgeId()
            BridgeCore.refreshPointer()
            toast(L.t("Đã đổi – sao chép lại cho Claude Code", "Changed – copy for Claude Code again"))
            selectTab(TAB_CONNECT)
        })

        c.addView(Switch(this).apply {
            text = L.t("Cho phép mọi lệnh (kể cả ghi/nguy hiểm)", "Allow all commands (including writing/dangerous ones)"); setTextColor(p.text); isChecked = cfg.allowWrite
            setOnCheckedChangeListener { _: CompoundButton, v: Boolean -> cfg.allowWrite = v }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
        })
        c.addView(ui.muted(L.t("Áp dụng cho cả lệnh từ Claude Code lẫn lệnh gõ tay. Tắt = chỉ lệnh đọc.", "Applies to commands from Claude Code and to typed ones. Off = reading commands only.")))
        return c
    }

    // ───────────────────────── Feature updates ─────────────────────────

    private var updateLabel: TextView? = null
    private var applyButton: TextView? = null

    private fun updateCard(): LinearLayout {
        val h = Host.api
        val c = ui.card()
        c.addView(ui.title(L.t("Cập nhật chức năng", "Feature updates")))
        c.addView(ui.kv(L.t("Chức năng", "Feature"), "#${h.featureVersion} · " +
            if (h.featureSource == "github") L.t("tải từ GitHub", "downloaded from GitHub") else L.t("đi kèm APK", "bundled with the APK")))
        c.addView(ui.kv("APK", "v${h.apkVersion} · API ${h.apiVersion}", p.muted))
        updateLabel = ui.muted("").apply { setPadding(0, dp(4), 0, 0) }
        c.addView(updateLabel)
        c.addView(ui.button(L.t("Kiểm tra cập nhật", "Check for updates"), primary = false) { h.checkUpdate(true) })
        applyButton = ui.button(L.t("Áp dụng ngay (khởi động lại app)", "Apply now (restarts the app)")) {
            h.applyUpdate(BridgeCore.running)
        }
        c.addView(applyButton)
        if (h.featureSource == "github") c.addView(ui.button(L.t("Quay về bản đi kèm APK", "Go back to the bundled version"), primary = false) {
            h.resetToBundled(); toast(L.t("Sẽ dùng bản đi kèm APK sau khi khởi động lại", "The version bundled with the APK will be used after a restart")); refreshUpdate()
        })
        c.addView(ui.muted(L.t("Code chức năng (giao diện, ADB, đường hầm) tự tải từ GitHub (nhánh live) khi có bản mới – ", "Feature code (interface, ADB, tunnel) downloads itself from GitHub (live branch) when a new version exists – ") +
            L.t("không cần cài lại APK. Chỉ khi đổi phần vỏ APK mới cần cài APK. ", "no APK reinstall. A new APK is only needed when the APK shell changes. ") +
            L.t("Sau khi khởi động lại, Claude Code vẫn tìm thấy máy qua địa chỉ cố định.", "After the restart, Claude Code still finds the phone through the fixed address.")))
        refreshUpdate()
        return c
    }

    /**
     * The update status text comes from the APK shell, which only speaks Vietnamese. In English it is
     * rebuilt from the state; error details keep the shell's original wording.
     */
    private fun updateText(u: com.zenoue.ccbridge.api.UpdateInfo): String {
        if (!L.english) return u.message
        val v = Regex("#(\\d+)").find(u.message)?.groupValues?.get(1) ?: "?"
        val notes = u.message.substringAfter('\n', "").substringAfter(": ", "")
        return when (u.state) {
            "idle" -> "Not checked yet"
            "checking" -> if (u.message.contains('#')) "Downloading feature #$v…" else "Checking GitHub…"
            "uptodate" -> "Up to date (#${Host.api.featureVersion})"
            "downloaded" ->
                if (u.pending > 0) "Feature #${u.pending} downloaded – restart to use it" + (if (notes.isNotBlank()) "\nNew: $notes" else "")
                else "The version bundled with the APK will be used after a restart"
            "need_apk" -> "Feature #$v needs a new APK – download it from Releases"
            else -> "Update problem: ${u.message}"
        }
    }

    private fun refreshUpdate() {
        val u = Host.api.updateInfo()
        val checked = if (u.checkedAt > 0) L.t(" · kiểm tra lúc ${Fmt.clock(u.checkedAt)}", " · checked at ${Fmt.clock(u.checkedAt)}") else ""
        updateLabel?.text = updateText(u) + checked
        updateLabel?.setTextColor(when (u.state) {
            "downloaded" -> p.accent
            "need_apk", "error" -> p.warn
            else -> p.muted
        })
        applyButton?.visibility = if (u.pending != 0) android.view.View.VISIBLE else android.view.View.GONE
    }

    // ───────────────────────── helpers ─────────────────────────

    private fun copy(s: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("bridge", s))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
