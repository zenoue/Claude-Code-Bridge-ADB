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
 * Màn hình chính (chạy trong feature.dex, APK chỉ chuyển tiếp vòng đời Activity vào đây). Hai tab:
 *  - CLI: gõ lệnh adb shell và xem kết quả; lệnh Claude Code gửi đến (qua đường hầm) cũng hiện ở đây.
 *  - Connect: kết nối Gỡ lỗi không dây (ADB trong app), mở đường hầm, bật/tắt cầu nối (không mật khẩu).
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

        // thanh tab ở trên
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
        // đã bật cổng 5555 trước đó: tự nối lại khi mở app (không cần Wi‑Fi)
        if (!Adb.isConnected(this) && cfg.tcpMode) io.execute {
            if (Adb.tcpPortOpen() && Adb.connect(this, "127.0.0.1", Adb.TCP_PORT)) main.post {
                Console.info("ADB kết nối 127.0.0.1:${Adb.TCP_PORT} (không cần Wi‑Fi)")
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
            else "○ Cầu nối tắt · ADB ${if (adb) "✓" else "✗"}"
        statusLabel?.setTextColor(if (BridgeCore.running) p.accent else p.muted)
        cliHint?.text = cliHintText()
        tunnelLabel?.let {
            it.text = tunnelText()
            it.setTextColor(if (BridgeCore.tunnelUrl != null) p.accent else p.muted)
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
        consoleList = null; consoleScroll = null; cliHint = null; input = null; tunnelLabel = null
        updateLabel = null; applyButton = null
        if (i == TAB_CLI) container.addView(cliTab()) else container.addView(connectTab())
        refreshStatus()
    }

    // ───────────────────────── Tab CLI ─────────────────────────

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

        // lệnh nhanh
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
            hint = "adb shell …  (vd: dumpsys battery)"
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
            text = "Chạy"; setTextColor(p.onAccent); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
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
        hintRow.addView(linkText("↑ Lệnh trước") {
            if (history.isNotEmpty()) { val h = history.last(); et.setText(h); et.setSelection(h.length) }
        })
        hintRow.addView(linkText("Sao chép") { copy(Console.dump()); toast("Đã sao chép nhật ký") })
        hintRow.addView(linkText("Xoá") { Console.clear(); renderConsoleAll() })
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
        !Adb.isConnected(this) -> "ADB chưa kết nối — sang tab Connect"
        BridgeCore.running -> "Đang nhận lệnh từ Claude Code"
        else -> "ADB ✓ · cầu nối đang tắt"
    }

    private fun renderConsoleAll() {
        val list = consoleList ?: return
        list.removeAllViews()
        lastShownId = 0L
        if (Console.snapshot().isEmpty()) {
            list.addView(ui.muted("Chưa có lệnh. Gõ lệnh bên dưới, hoặc để Claude Code gửi đến (dòng claude$).")
                .apply { tag = EMPTY_TAG })
        }
        appendNewConsole()
    }

    private fun appendNewConsole() {
        val list = consoleList ?: return
        val fresh = Console.since(lastShownId)
        if (fresh.isEmpty()) {
            // console bị xoá ở nơi khác -> vẽ lại
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
            val time = if (e.kind == Console.Kind.CMD_LOCAL || e.kind == Console.Kind.CMD_REMOTE) "  [${Fmt.clock(e.time)}]" else ""
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
        if (!Adb.isConnected(this)) { Console.err("ADB chưa kết nối — sang tab Connect để ghép nối"); return }
        if (!cfg.allowWrite && !Policy.allowed(cmd)) {
            Console.err("Bị chặn ở chế độ chỉ-đọc (bật \"Cho phép mọi lệnh\" ở tab Connect)"); return
        }
        io.execute {
            val r = try { Adb.run(this, cmd, 120_000) } catch (t: Throwable) { Adb.disconnect(this); Adb.Result(-1, t.toString(), "ADB") }
            Console.out(r.output.ifBlank { "(không có output)" })
            if (r.exit != 0) Console.err("exit ${r.exit}")
            main.post { refreshStatus() }
        }
    }

    // ───────────────────────── Tab Connect ─────────────────────────

    private var tunnelLabel: TextView? = null

    private fun connectTab(): ScrollView {
        val sv = ScrollView(this).apply { isFillViewport = true }
        val col = ui.column().apply { setPadding(dp(16), dp(8), dp(16), dp(28)) }
        sv.addView(col)
        col.addView(bridgeCard())
        col.addView(adbCard())
        col.addView(tunnelCard())
        col.addView(updateCard())
        return sv
    }

    private fun tunnelText(): String {
        val st = BridgeCore.tunnel
        return when {
            !BridgeCore.running -> "Bấm \"Bắt đầu cầu nối\" để mở đường hầm"
            st?.url != null -> st.url
            else -> st?.message ?: "Đang mở đường hầm…"
        }
    }

    private fun bridgeCard(): LinearLayout {
        val c = ui.card()
        c.addView(ui.title("Cầu nối"))
        c.addView(ui.kv("Trạng thái", if (BridgeCore.running) BridgeCore.status else "Tắt",
            if (BridgeCore.running) p.accent else p.muted))
        c.addView(ui.label("Địa chỉ công khai (đường hầm)"))
        tunnelLabel = ui.mono(tunnelText()).apply {
            textSize = 13f; setTextColor(if (BridgeCore.tunnelUrl != null) p.accent else p.muted)
            setPadding(0, dp(4), 0, dp(4))
        }
        c.addView(tunnelLabel)
        c.addView(ui.button(if (BridgeCore.running) "Dừng cầu nối" else "Bắt đầu cầu nối", !BridgeCore.running) {
            if (BridgeCore.running) BridgeCore.stop(this)
            else {
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                BridgeCore.start(this)
            }
            main.postDelayed({ if (tab == TAB_CONNECT) selectTab(TAB_CONNECT) }, 600)
        })
        c.addView(ui.button("Sao chép cho Claude Code", primary = false) {
            val url = BridgeCore.bridgeUrl(cfg)
            if (url == null) { toast("Chưa có địa chỉ đường hầm – bắt đầu cầu nối trước"); return@button }
            copy("export BRIDGE_URL='$url'"); toast("Đã sao chép địa chỉ")
        })
        c.addView(ui.muted("Dán vào terminal của Claude Code rồi chạy: python3 tools/bridge.py ping. " +
            "Không cần mật khẩu: địa chỉ đã kèm khoá ngẫu nhiên, đừng chia sẻ công khai. " +
            "Địa chỉ có thể đổi khi đường hầm nối lại – khi đó sao chép lại."))
        return c
    }

    private fun adbStateText() =
        if (Adb.isConnected(this)) "Đã kết nối · ${Adb.mode}" else if (cfg.paired) "Đã ghép nối" else "Chưa ghép nối"

    private fun adbCard(): LinearLayout {
        val c = ui.card()
        c.addView(ui.title("Gỡ lỗi không dây (ADB)"))
        c.addView(ui.muted("Cài đặt → Tuỳ chọn nhà phát triển → Gỡ lỗi không dây (Android 11+). " +
            "ADB chạy ngay trong app, không cần root, Shizuku hay PC. Nên dùng chế độ chia đôi màn hình " +
            "để vừa xem mã ghép nối vừa nhập vào app."))
        c.addView(ui.kv("Tình trạng", adbStateText(), if (Adb.isConnected(this)) p.accent else p.muted))

        c.addView(ui.label("Bước 1 – Ghép nối (chỉ một lần): \"Ghép nối thiết bị bằng mã ghép nối\""))
        val pairPort = ui.field("cổng ghép nối, vd 41055", "", numeric = true)
        val pairCode = ui.field("mã ghép nối 6 số", "", numeric = true)
        c.addView(pairPort); c.addView(pairCode)
        val pairLog = ui.muted("")
        c.addView(ui.button("Ghép nối") {
            val port = pairPort.text.toString().trim().toIntOrNull()
            val code = pairCode.text.toString().trim()
            if (port == null || code.length < 6) { toast("Nhập cổng và mã 6 số"); return@button }
            pairLog.text = "Đang ghép nối…"
            io.execute {
                val ok = Adb.pair(this, "127.0.0.1", port, code)
                main.post {
                    pairLog.text = if (ok) "✓ Ghép nối xong — làm tiếp Bước 2" else "✗ ${Adb.lastError}"
                    if (ok) { cfg.paired = true; Console.info("ADB đã ghép nối") }
                }
            }
        })
        c.addView(pairLog)

        c.addView(ui.label("Bước 2 – Kết nối: \"Địa chỉ IP và cổng\" trên màn hình Gỡ lỗi không dây"))
        val conPort = ui.field("cổng kết nối, vd 37219", if (cfg.adbPort > 0) cfg.adbPort.toString() else "", numeric = true)
        c.addView(conPort)
        val conLog = ui.muted("")
        c.addView(ui.button("Kết nối", primary = !Adb.isConnected(this)) {
            val port = conPort.text.toString().trim().toIntOrNull()
            if (port == null) { toast("Nhập cổng kết nối"); return@button }
            cfg.adbPort = port; cfg.adbHost = "127.0.0.1"; conLog.text = "Đang kết nối…"
            io.execute {
                val ok = Adb.connect(this, "127.0.0.1", port)
                main.post {
                    conLog.text = if (ok) "✓ Đã kết nối ADB" else "✗ ${Adb.lastError}"
                    if (ok) { cfg.paired = true; Console.info("ADB kết nối 127.0.0.1:$port") }
                    refreshStatus()
                }
            }
        })
        c.addView(ui.button("Tự dò cổng (mDNS)", primary = false) {
            conLog.text = "Đang dò…"
            io.execute {
                val ok = Adb.autoConnect(this)
                main.post {
                    conLog.text = if (ok) "✓ Đã kết nối" else "✗ ${Adb.lastError}"
                    if (ok) { cfg.paired = true; Console.info("ADB kết nối (mDNS)") }
                    refreshStatus()
                }
            }
        })
        if (Adb.isConnected(this)) c.addView(ui.button("Ngắt ADB", primary = false) {
            Adb.disconnect(this); selectTab(TAB_CONNECT)
        })
        c.addView(conLog)
        noWifiSection(c)
        c.addView(ui.button("Mở Tuỳ chọn nhà phát triển", primary = false) {
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) } catch (_: Exception) { toast("Không mở được") }
        })
        return c
    }

    /**
     * Gỡ lỗi không cần Wi‑Fi: Android (kể cả 17, ADB Wi‑Fi 2.0) chỉ bật Gỡ lỗi không dây khi đang nối Wi‑Fi
     * và tự tắt khi rời mạng. Trong lúc đang có kết nối ADB, gửi `tcpip:5555` để adbd mở thêm cổng TCP thường
     * trên máy; sau đó nối 127.0.0.1:5555 – không phụ thuộc Wi‑Fi, giữ tới khi khởi động lại điện thoại.
     */
    private fun noWifiSection(c: LinearLayout) {
        c.addView(ui.divider())
        c.addView(ui.label("Gỡ lỗi không cần Wi‑Fi (Android 11–17)"))
        c.addView(ui.muted("Android chỉ bật Gỡ lỗi không dây khi đang nối Wi‑Fi. Bấm \"Bật\" một lần khi ADB đang kết nối: " +
            "adbd mở thêm cổng ${Adb.TCP_PORT} ngay trên máy, sau đó tắt Wi‑Fi / chỉ dùng 4G vẫn kết nối được " +
            "cho tới khi khởi động lại điện thoại. Lần đầu máy hỏi \"Cho phép gỡ lỗi USB?\" → tích Luôn cho phép → Cho phép."))
        // kiểm tra cổng ở luồng nền (socket trên luồng UI -> NetworkOnMainThreadException)
        val portLabel = ui.body("Cổng ${Adb.TCP_PORT}: đang kiểm tra…", p.muted, 13f)
        c.addView(portLabel)
        io.execute {
            val open = Adb.tcpPortOpen()
            main.post {
                portLabel.text = "Cổng ${Adb.TCP_PORT}: " + if (open) "đang mở" else "đóng"
                portLabel.setTextColor(if (open) p.accent else p.muted)
            }
        }
        val log = ui.muted("")

        c.addView(ui.button("Bật ADB không cần Wi‑Fi", primary = Adb.isConnected(this) && !cfg.tcpMode) {
            if (!Adb.isConnected(this)) { toast("Cần kết nối ADB trước (Bước 1–2, đang có Wi‑Fi)"); return@button }
            log.text = "Đang chuyển adbd sang cổng ${Adb.TCP_PORT}…"
            io.execute {
                val msg = try { Adb.enableTcpip(this) } catch (t: Throwable) { "✗ ${t.message ?: t}" }
                Console.info("adbd: $msg")
                main.post { log.text = "$msg\nĐang nối 127.0.0.1:${Adb.TCP_PORT} – nếu máy hỏi, bấm Cho phép…" }
                Thread.sleep(1500)
                val ok = Adb.connectTcp(this)
                if (ok) cfg.tcpMode = true
                main.post {
                    log.text = if (ok) "✓ Đã kết nối qua cổng ${Adb.TCP_PORT} – giờ có thể tắt Wi‑Fi" else "✗ ${Adb.lastError}"
                    if (ok) Console.info("ADB kết nối 127.0.0.1:${Adb.TCP_PORT} (không cần Wi‑Fi)")
                    else {
                        // không nối được cổng TCP -> quay lại Gỡ lỗi không dây nếu còn
                        if (cfg.adbPort > 0) io.execute { Adb.connect(this, "127.0.0.1", cfg.adbPort); main.post { refreshStatus() } }
                    }
                    refreshStatus()
                }
            }
        })
        c.addView(ui.button("Kết nối không cần Wi‑Fi (127.0.0.1:${Adb.TCP_PORT})", primary = false) {
            log.text = "Đang nối 127.0.0.1:${Adb.TCP_PORT}…"
            io.execute {
                if (Adb.isConnected(this)) Adb.disconnect(this)
                val ok = Adb.connectTcp(this, attempts = 2)
                if (ok) cfg.tcpMode = true
                main.post {
                    log.text = if (ok) "✓ Đã kết nối qua cổng ${Adb.TCP_PORT}" else "✗ ${Adb.lastError}"
                    if (ok) Console.info("ADB kết nối 127.0.0.1:${Adb.TCP_PORT} (không cần Wi‑Fi)")
                    refreshStatus()
                }
            }
        })
        if (cfg.tcpMode) c.addView(ui.button("Tắt cổng ${Adb.TCP_PORT}", primary = false) {
            cfg.tcpMode = false
            if (!Adb.isConnected(this)) { toast("Đã tắt tự nối cổng ${Adb.TCP_PORT}; cổng đóng khi khởi động lại máy"); selectTab(TAB_CONNECT); return@button }
            io.execute {
                val msg = try { Adb.disableTcpip(this) } catch (t: Throwable) { "✗ ${t.message ?: t}" }
                Console.info("adbd: $msg")
                main.post { toast("Đã tắt cổng ${Adb.TCP_PORT} – nối lại bằng Gỡ lỗi không dây"); selectTab(TAB_CONNECT) }
            }
        })
        c.addView(log)
        c.addView(ui.muted("Cổng ${Adb.TCP_PORT} nghe trên mọi mạng của máy; máy lạ vẫn phải được bạn bấm Cho phép mới vào được. " +
            "Tắt cổng khi dùng Wi‑Fi công cộng."))
    }

    private fun tunnelCard(): LinearLayout {
        val c = ui.card()
        c.addView(ui.title("Đường hầm & chế độ"))
        c.addView(ui.muted("Đường hầm localhost.run (không cần tài khoản). App tự kiểm tra địa chỉ mỗi 45 giây " +
            "và nối lại ngay khi đổi Wi‑Fi/4G; địa chỉ có thể đổi sau khi nối lại."))

        c.addView(ui.button("Đổi khoá trong địa chỉ", primary = false) {
            cfg.newUrlKey()
            toast("Đã đổi khoá – địa chỉ cũ hết hiệu lực, hãy sao chép lại")
            refreshStatus()
        })

        c.addView(Switch(this).apply {
            text = "Cho phép mọi lệnh (kể cả ghi/nguy hiểm)"; setTextColor(p.text); isChecked = cfg.allowWrite
            setOnCheckedChangeListener { _: CompoundButton, v: Boolean -> cfg.allowWrite = v }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
        })
        c.addView(ui.muted("Áp dụng cho cả lệnh từ Claude Code lẫn lệnh gõ tay. Tắt = chỉ lệnh đọc."))
        return c
    }

    // ───────────────────────── Cập nhật chức năng ─────────────────────────

    private var updateLabel: TextView? = null
    private var applyButton: TextView? = null

    private fun updateCard(): LinearLayout {
        val h = Host.api
        val c = ui.card()
        c.addView(ui.title("Cập nhật chức năng"))
        c.addView(ui.kv("Chức năng", "v1.0.${h.featureVersion} · " +
            if (h.featureSource == "github") "tải từ GitHub" else "đi kèm APK"))
        c.addView(ui.kv("APK", "v${h.apkVersion} · API ${h.apiVersion}", p.muted))
        updateLabel = ui.muted("").apply { setPadding(0, dp(4), 0, 0) }
        c.addView(updateLabel)
        c.addView(ui.button("Kiểm tra cập nhật", primary = false) { h.checkUpdate(true) })
        applyButton = ui.button("Áp dụng ngay (khởi động lại app)") {
            h.applyUpdate(BridgeCore.running)
        }
        c.addView(applyButton)
        if (h.featureSource == "github") c.addView(ui.button("Quay về bản đi kèm APK", primary = false) {
            h.resetToBundled(); toast("Sẽ dùng bản đi kèm APK sau khi khởi động lại"); refreshUpdate()
        })
        c.addView(ui.muted("Code chức năng (giao diện, ADB, đường hầm) tự tải từ GitHub Releases khi có bản mới – " +
            "không cần cài lại APK. Chỉ khi đổi phần vỏ APK mới cần cài APK. " +
            "Sau khi khởi động lại, địa chỉ đường hầm có thể đổi."))
        refreshUpdate()
        return c
    }

    private fun refreshUpdate() {
        val u = Host.api.updateInfo()
        val checked = if (u.checkedAt > 0) " · kiểm tra lúc ${Fmt.clock(u.checkedAt)}" else ""
        updateLabel?.text = u.message + checked
        updateLabel?.setTextColor(when (u.state) {
            "downloaded" -> p.accent
            "need_apk", "error" -> p.warn
            else -> p.muted
        })
        applyButton?.visibility = if (u.pending != 0) android.view.View.VISIBLE else android.view.View.GONE
    }

    // ───────────────────────── tiện ích ─────────────────────────

    private fun copy(s: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("bridge", s))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
