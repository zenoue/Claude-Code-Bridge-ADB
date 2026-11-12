package com.zenoue.ccbridge.feature

/**
 * Shared console behind the CLI tab: commands typed by hand and commands sent by Claude Code
 * (through the tunnel) both show up here with their results. Everything added here is UI text.
 *
 * Lines carry increasing ids so the UI can append only what is new, even after old lines were trimmed.
 */
object Console {
    enum class Kind { CMD_LOCAL, CMD_REMOTE, OUT, INFO, ERR }
    data class Entry(val id: Long, val kind: Kind, val text: String, val time: Long = System.currentTimeMillis())

    const val MAX = 500

    /** Output shown in the CLI tab is clipped: one huge selectable TextView froze the UI thread (ANR). */
    const val OUT_KEEP_LINES = 40
    const val OUT_MAX_CHARS = 8000
    private val lines = ArrayDeque<Entry>()
    private var nextId = 1L
    @Volatile var onChange: (() -> Unit)? = null

    fun add(kind: Kind, text: String) {
        val shown = if (kind == Kind.OUT) clip(text) else text.take(OUT_MAX_CHARS)
        synchronized(lines) {
            lines.addLast(Entry(nextId++, kind, shown))
            while (lines.size > MAX) lines.removeFirst()
        }
        onChange?.invoke()
    }

    fun cmdLocal(cmd: String) = add(Kind.CMD_LOCAL, cmd)
    fun cmdRemote(cmd: String) = add(Kind.CMD_REMOTE, cmd)
    fun out(text: String) = add(Kind.OUT, text)
    fun info(text: String) = add(Kind.INFO, text)
    fun err(text: String) = add(Kind.ERR, text)

    /**
     * Keeps the first and last [OUT_KEEP_LINES] lines (and at most [OUT_MAX_CHARS] chars) of a long output.
     * Only the on-screen copy is clipped; the reply sent to Claude Code keeps the full output.
     */
    fun clip(text: String): String {
        val all = text.split('\n')
        var head: String
        var tail: String
        val hiddenLines: Int
        if (all.size > OUT_KEEP_LINES * 2 + 1) {
            head = all.subList(0, OUT_KEEP_LINES).joinToString("\n")
            tail = all.subList(all.size - OUT_KEEP_LINES, all.size).joinToString("\n")
            hiddenLines = all.size - OUT_KEEP_LINES * 2
        } else if (text.length > OUT_MAX_CHARS) {
            head = text; tail = ""; hiddenLines = 0
        } else return text
        val half = OUT_MAX_CHARS / 2
        if (head.length > half) head = head.take(half) + "…"
        if (tail.length > half) tail = "…" + tail.takeLast(half)
        if (tail.isEmpty() && text.length > OUT_MAX_CHARS) tail = "…" + text.takeLast(half)
        val note = (if (hiddenLines > 0) L.t("ẩn $hiddenLines dòng", "$hiddenLines lines hidden") else L.t("ẩn bớt", "partly hidden")) +
            (if (text.length >= 1024) L.t(", tổng ${text.length / 1024} KB", ", ${text.length / 1024} KB in total") else "")
        return L.t("$head\n  ⋯ ($note – chỉ rút gọn trên màn hình) ⋯\n$tail", "$head\n  ⋯ ($note – shortened on screen only) ⋯\n$tail")
    }

    fun snapshot(): List<Entry> = synchronized(lines) { ArrayList(lines) }

    /** Lines with id > [afterId]. */
    fun since(afterId: Long): List<Entry> = synchronized(lines) { lines.filter { it.id > afterId } }

    fun clear() { synchronized(lines) { lines.clear() }; onChange?.invoke() }

    /** Whole console as text with timestamps (for copying). */
    fun dump(): String {
        val f = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT)
        return snapshot().joinToString("\n") { e -> "[${f.format(java.util.Date(e.time))}] " + line(e) }
    }

    private fun line(e: Entry): String =
        when (e.kind) {
            Kind.CMD_LOCAL -> "$ ${e.text}"
            Kind.CMD_REMOTE -> "claude$ ${e.text}"
            Kind.OUT -> e.text
            Kind.INFO -> "· ${e.text}"
            Kind.ERR -> "✗ ${e.text}"
        }
}
