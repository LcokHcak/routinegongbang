package kr.routinegongbang.app.platform

/**
 * A notification body the Pleos notification centre never has to cut. The centre wraps a long line and shows
 * [MAX_ROWS] rows; a body with more rows it cuts, and then draws in #333333, dropping the colour span ([NoticeText]),
 * measured on the emulator. So the body is fitted here first: blank lines go, rows are counted, and the lines
 * past the last row shown become a count. The whole text stays in the app.
 */
internal object NoticeLines {
    const val MAX_ROWS = 3
    /** A row held 55 characters of a Korean sentence on the emulator; fewer, for wider glyphs. */
    const val ROW_CHARS = 48

    /**
     * [lines] may hold line breaks of their own (a run summary does); each counts. [hidden] were left out before
     * (a long drive's held notices) and are counted with the rest.
     */
    fun fit(lines: List<String>, more: (Int) -> String, hidden: Int = 0): List<String> {
        val shown = lines.flatMap { it.lines() }.map { it.trim() }.filter { it.isNotEmpty() }
        if (hidden == 0 && shown.sumOf(::rows) <= MAX_ROWS) return shown
        // A single line has nothing to count: it gets every row.
        if (hidden == 0 && shown.size == 1) return listOf(cut(shown.single(), MAX_ROWS))
        // One row goes to the count of the rest.
        val kept = mutableListOf<String>()
        var used = 0
        for (line in shown) {
            if (used + rows(line) > MAX_ROWS - 1) break
            kept += line
            used += rows(line)
        }
        if (kept.isEmpty() && shown.isNotEmpty()) kept += cut(shown.first(), MAX_ROWS - 1)
        return kept + more(shown.size - kept.size + hidden)
    }

    private fun rows(line: String) = (line.length + ROW_CHARS - 1) / ROW_CHARS

    private fun cut(line: String, rows: Int): String {
        val max = rows * ROW_CHARS
        return if (line.length <= max) line else line.take(max - 1) + "…"
    }
}
