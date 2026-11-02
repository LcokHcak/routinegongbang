package kr.routinegongbang.app.platform

/**
 * Notices wait while the car is known to be moving and come out together once it is parked (a notice
 * on the move asks the driver to read, and often to operate the car). A long drive keeps the newest [max] and counts
 * the rest, so the digest can say some were left out.
 */
internal class NoticeHold(private val max: Int = MAX_HELD) {
    private val held = ArrayDeque<String>()
    private var dropped = 0

    data class Released(val texts: List<String>, val dropped: Int) {
        val isEmpty: Boolean get() = texts.isEmpty() && dropped == 0

        /** Two releases that go out as one digest (the car moved again before the first went out). */
        operator fun plus(other: Released) = Released((texts + other.texts).distinct(), dropped + other.dropped)
    }

    /**
     * [text] to show now, or null when it waits for parking. [quiet] is asked under this hold's lock:
     * asked before, a notice could be held just after the release and wait for the next parking.
     */
    @Synchronized
    fun offer(text: String, quiet: () -> Boolean): String? {
        if (!quiet()) return text
        if (text in held) return null
        if (held.size >= max) {
            held.removeFirst()
            dropped++
        }
        held.addLast(text)
        return null
    }

    @Synchronized
    fun release(): Released = Released(held.toList(), dropped).also { clear() }

    /** A demo story starts afresh; what the previous one held belongs to it. */
    @Synchronized
    fun clear() {
        held.clear()
        dropped = 0
    }

    private companion object {
        const val MAX_HELD = 20
    }
}
