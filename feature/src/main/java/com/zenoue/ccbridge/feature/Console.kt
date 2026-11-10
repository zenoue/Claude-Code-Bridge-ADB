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
