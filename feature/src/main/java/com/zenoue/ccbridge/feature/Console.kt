package com.zenoue.ccbridge.feature

/**
 * Cửa sổ lệnh dùng chung cho tab CLI: lệnh gõ tay và lệnh Claude Code gửi đến
 * (qua đường hầm) cùng hiện ở đây, kèm kết quả.
 *
 * Mỗi dòng có id tăng dần để UI chỉ vẽ thêm phần mới, kể cả khi bộ đệm đã xoá bớt dòng cũ.
 */
object Console {
    enum class Kind { CMD_LOCAL, CMD_REMOTE, OUT, INFO, ERR }
    data class Entry(val id: Long, val kind: Kind, val text: String, val time: Long = System.currentTimeMillis())

    const val MAX = 500
    private val lines = ArrayDeque<Entry>()
    private var nextId = 1L
    @Volatile var onChange: (() -> Unit)? = null

    fun add(kind: Kind, text: String) {
        synchronized(lines) {
            lines.addLast(Entry(nextId++, kind, text))
            while (lines.size > MAX) lines.removeFirst()
        }
        onChange?.invoke()
    }

    fun cmdLocal(cmd: String) = add(Kind.CMD_LOCAL, cmd)
    fun cmdRemote(cmd: String) = add(Kind.CMD_REMOTE, cmd)
    fun out(text: String) = add(Kind.OUT, text)
    fun info(text: String) = add(Kind.INFO, text)
    fun err(text: String) = add(Kind.ERR, text)

    fun snapshot(): List<Entry> = synchronized(lines) { ArrayList(lines) }

    /** Các dòng có id > [afterId]. */
    fun since(afterId: Long): List<Entry> = synchronized(lines) { lines.filter { it.id > afterId } }

    fun clear() { synchronized(lines) { lines.clear() }; onChange?.invoke() }

    /** Toàn bộ nội dung dạng text (để sao chép). */
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
