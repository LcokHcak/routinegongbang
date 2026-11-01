package kr.routinegongbang.core.engine

import kr.routinegongbang.core.model.Rounding
import kr.routinegongbang.core.model.SignalId
import kr.routinegongbang.core.model.SignalValue

/**
 * The app's own writes of the last hour: per-signal rate limits and the flip-back check, which stops the app
 * from flipping a signal back (A → B → A) within [FLIP_WINDOW_MS]. Immutable; dry runs are kept out of it.
 */
class WriteHistory private constructor(private val entries: List<Entry>) {

    private data class Entry(val signal: SignalId, val value: SignalValue, val atMs: Long)

    constructor() : this(emptyList())

    fun record(signal: SignalId, value: SignalValue, atMs: Long): WriteHistory =
        WriteHistory(entries.filter { atMs - it.atMs < KEEP_MS } + Entry(signal, value, atMs))

    fun countSince(signal: SignalId, sinceMs: Long): Int = entries.count { it.signal == signal && it.atMs >= sinceMs }

    /** True when writing [target] now would undo a change the app made after writing [target] itself. */
    fun flipsBack(signal: SignalId, target: SignalValue, nowMs: Long, rounding: Rounding): Boolean {
        val recent = entries.filter { it.signal == signal && nowMs - it.atMs < FLIP_WINDOW_MS }
        val lastSame = recent.indexOfLast { it.value.sameAs(target, rounding) }
        return lastSame >= 0 && recent.drop(lastSame + 1).any { !it.value.sameAs(target, rounding) }
    }

    override fun equals(other: Any?): Boolean = other is WriteHistory && other.entries == entries

    override fun hashCode(): Int = entries.hashCode()

    companion object {
        const val FLIP_WINDOW_MS: Long = kr.routinegongbang.core.model.FLIP_WINDOW_MS
        private const val KEEP_MS: Long = 60 * 60_000
    }
}
