package com.zenoue.ccbridge

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import java.util.concurrent.Executors

/**
 * Hai tab:
 *  - CLI: gõ lệnh adb shell và xem kết quả; lệnh Claude Code gửi đến (qua đường hầm) cũng hiện ở đây.
 *  - Connect: kết nối Gỡ lỗi không dây (ADB trong app), mở đường hầm, bật/tắt cầu nối (không mật khẩu).
 */
class MainActivity : Activity() {

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

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
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
        setContentView(root)

        Console.onChange = { main.post { if (tab == TAB_CLI) appendNewConsole() } }
        BridgeService.onStatus = { main.post { refreshStatus() } }
        selectTab(if (Adb.isConnected(this)) TAB_CLI else TAB_CONNECT)
    }

    override fun onResume() { super.onResume(); refreshStatus() }

    override fun onDestroy() {
        Console.onChange = null
        BridgeService.onStatus = null
        io.shutdownNow()
        super.onDestroy()
    }

    private fun refreshStatus() {
        val adb = Adb.isConnected(this)
        statusLabel?.text = if (BridgeService.running) "● " + BridgeService.status
            else "○ Cầu nối tắt · ADB ${if (adb) "✓" else "✗"}"
        statusLabel?.setTextColor(if (BridgeService.running) p.accent else p.muted)
        cliHint?.text = cliHintText()
        tunnelLabel?.let {
            it.text = tunnelText()
            it.setTextColor(if (BridgeService.tunnelUrl != null) p.accent else p.muted)
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
        BridgeService.running -> "Đang nhận lệnh từ Claude Code"
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
        return sv
    }

    private fun tunnelText(): String {
        val st = BridgeService.tunnel
        return when {
            !BridgeService.running -> "Bấm \"Bắt đầu cầu nối\" để mở đường hầm"
            st?.url != null -> "${st.url}\n${st.provider?.label ?: ""}"
            else -> st?.message ?: "Đang mở đường hầm…"
        }
    }

    private fun bridgeCard(): LinearLayout {
        val c = ui.card()
        c.addView(ui.title("Cầu nối"))
        c.addView(ui.kv("Trạng thái", if (BridgeService.running) BridgeService.status else "Tắt",
            if (BridgeService.running) p.accent else p.muted))
        c.addView(ui.label("Địa chỉ công khai (đường hầm)"))
        tunnelLabel = ui.mono(tunnelText()).apply {
            textSize = 13f; setTextColor(if (BridgeService.tunnelUrl != null) p.accent else p.muted)
            setPadding(0, dp(4), 0, dp(4))
        }
        c.addView(tunnelLabel)
        c.addView(ui.button(if (BridgeService.running) "Dừng cầu nối" else "Bắt đầu cầu nối", !BridgeService.running) {
            if (BridgeService.running) BridgeService.stop(this)
            else {
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                BridgeService.start(this)
            }
            main.postDelayed({ if (tab == TAB_CONNECT) selectTab(TAB_CONNECT) }, 600)
        })
        c.addView(ui.button("Sao chép cho Claude Code", primary = false) {
            val url = BridgeService.bridgeUrl(cfg)
            if (url == null) { toast("Chưa có địa chỉ đường hầm – bắt đầu cầu nối trước"); return@button }
            copy("export BRIDGE_URL='$url'"); toast("Đã sao chép địa chỉ")
        })
        c.addView(ui.muted("Dán vào terminal của Claude Code rồi chạy: python3 tools/bridge.py ping. " +
            "Không cần mật khẩu: địa chỉ đã kèm khoá ngẫu nhiên, đừng chia sẻ công khai. " +
            "Địa chỉ có thể đổi khi đường hầm nối lại – khi đó sao chép lại."))
        return c
    }

    private fun adbStateText() =
        if (Adb.isConnected(this)) "Đã kết nối" else if (cfg.paired) "Đã ghép nối" else "Chưa ghép nối"

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
        c.addView(ui.button("Mở Tuỳ chọn nhà phát triển", primary = false) {
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) } catch (_: Exception) { toast("Không mở được") }
        })
        return c
    }

    private fun tunnelCard(): LinearLayout {
        val c = ui.card()
        c.addView(ui.title("Đường hầm & chế độ"))
        c.addView(ui.label("Nhà cung cấp đường hầm"))
        val options = listOf(Tunnel.Provider.AUTO to "Tự động") + Tunnel.Provider.ALL.map { it.id to it.label }
        val chips = ui.row().apply { setPadding(0, dp(6), 0, 0) }
        val chipViews = ArrayList<TextView>()
        fun paint() = chipViews.forEachIndexed { i, tv ->
            val on = options[i].first == cfg.provider ||
                (i == 0 && Tunnel.Provider.ALL.none { it.id == cfg.provider })
            tv.setTextColor(if (on) p.onAccent else p.accent)
            tv.background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                if (on) setColor(p.accent) else setStroke(dp(1), p.accent)
            }
        }
        options.forEach { (id, label) ->
            val tv = TextView(this).apply {
                text = label; textSize = 13f; setPadding(dp(14), dp(7), dp(14), dp(7))
                setOnClickListener {
                    if (cfg.provider == id) return@setOnClickListener
                    cfg.provider = id; paint()
                    if (BridgeService.running) { toast("Đang nối lại với ${label}…"); BridgeService.reconnect(this@MainActivity) }
                }
            }
            chipViews.add(tv)
            chips.addView(tv, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
        }
        paint()
        c.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
        c.addView(ui.muted("Tự động: thử localhost.run → Pinggy → serveo.net, nhà nào lỗi liên tục thì chuyển nhà kế. " +
            "App tự kiểm tra địa chỉ mỗi 45 giây và nối lại ngay khi đổi Wi‑Fi/4G."))

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

    // ───────────────────────── tiện ích ─────────────────────────

    private fun copy(s: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("bridge", s))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
