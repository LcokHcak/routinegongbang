package kr.routinegongbang.core.engine

import kr.routinegongbang.core.model.AreaKey
import kr.routinegongbang.core.model.AreaKeys
import kr.routinegongbang.core.model.Rounding
import kr.routinegongbang.core.model.SignalCatalog
import kr.routinegongbang.core.model.SignalId
import kr.routinegongbang.core.model.SignalSpec
import kr.routinegongbang.core.model.SignalValue

/** Who caused an observed change. Used by user overrides, self-trigger protection and habit detection. */
enum class ChangeOrigin {
    /** Matches a pending write of this app. */
    APP,
    /** No pending write, or the pending write already expired. */
    USER,
    /**
     * A signal the car changes along with our write changed within the deadline: the car or the user, nobody can
     * tell. Both safe readings apply: it fires nothing and is no habit, and the app leaves it alone
     * for the rest of the trip, as after a user change.
     */
    COUPLED,
    /** A write is pending but the value differs (e.g. intermediate step). Neither app nor user. */
    UNCERTAIN,
}

/**
 * [lastObserved] is the latest differing value seen while pending; non-null on expiry means a likely user override.
 * [coupled] marks a signal the car changes by itself because of another write: any one change of
 * it before the deadline is the app's, whatever the value; it is never a write of its own and never a result.
 */
data class PendingWrite(
    val signal: SignalId,
    val area: AreaKey,
    val expected: SignalValue,
    val deadlineMs: Long,
    val lastObserved: SignalValue? = null,
    val coupled: Boolean = false,
)

/**
 * Single source of truth for "did the app or the user change this?".
 * Immutable: every operation returns a new ledger, so the single-threaded engine keeps it as plain state.
 * Times are monotonic milliseconds. Unknown signals never throw; they use neutral defaults.
 */
class WriteLedger(
    private val catalog: SignalCatalog,
    val pending: List<PendingWrite> = emptyList(),
) {

    /** Skip writes that would not change anything; such writes would never produce a confirming event. */
    fun needsWrite(signal: SignalId, current: SignalValue?, target: SignalValue): Boolean =
        current == null || !current.sameAs(target, roundingOf(signal))

    /**
     * Every write stays expected until its echo or deadline, also when the same signal is written again: the car
     * reports each change in order, and an echo of an older write must never look like the user (RandomCompositionTest
     * seed 33). The signals the car changes along with this one are expected too.
     */
    fun record(signal: SignalId, expected: SignalValue, nowMs: Long, area: AreaKey? = null): WriteLedger {
        val key = resolveArea(signal, area)
        val timeout = catalog[signal]?.ledgerTimeoutMs ?: SignalSpec.DEFAULT_LEDGER_TIMEOUT_MS
        val entry = PendingWrite(signal, key, expected, nowMs + timeout)
        val coupled = catalog[signal]?.profile?.coupledSignals.orEmpty().map { other ->
            PendingWrite(other, resolveArea(other, null), expected, nowMs + timeout, coupled = true)
        }
        return copy(pending + entry + coupled)
    }

    /**
     * An echo matches the oldest write of that value; it retires that write and every older one of the signal (the
     * car moved past them). Otherwise a waiting coupling takes it. A value nobody expects, while writes still wait,
     * is UNCERTAIN; with nothing waiting it is the user's.
     */
    fun classify(signal: SignalId, value: SignalValue, nowMs: Long, area: AreaKey? = null): Pair<ChangeOrigin, WriteLedger> =
        classifyDetailed(signal, value, nowMs, area).let { it.origin to it.ledger }

    /**
     * [confirmed]: how many of the signal's writes, oldest first, this echo settles (the last one is confirmed, the
     * older ones were overtaken). [expired]: writes found past their deadline, to be settled like at a tick.
     */
    data class Classification(val origin: ChangeOrigin, val ledger: WriteLedger, val confirmed: Int = 0, val expired: List<PendingWrite> = emptyList())

    /** Couplings stay expected until their deadline: a fan climbing step by step changes several times. */
    fun classifyDetailed(signal: SignalId, value: SignalValue, nowMs: Long, area: AreaKey? = null): Classification {
        val key = resolveArea(signal, area)
        val mine = pending.filter { it.matches(signal, key) }
        if (mine.isEmpty()) return Classification(ChangeOrigin.USER, this)
        val (live, dead) = mine.partition { nowMs <= it.deadlineMs }
        val expired = dead.filterNot { it.coupled }
        val others = pending - mine.toSet()
        if (live.isEmpty()) return Classification(ChangeOrigin.USER, copy(others), expired = expired)
        val writes = live.filterNot { it.coupled }
        val matched = writes.indexOfFirst { value.sameAs(it.expected, roundingOf(signal)) }
        if (matched >= 0) {
            // By position, never by equality: two identical writes in one millisecond need two echoes (seed 619).
            var toSettle = matched + 1
            val remaining = live.filter { entry -> if (!entry.coupled && toSettle > 0) false.also { toSettle-- } else true }
            return Classification(ChangeOrigin.APP, copy(others + remaining), confirmed = matched + 1, expired = expired)
        }
        if (live.any { it.coupled }) return Classification(ChangeOrigin.COUPLED, copy(others + live), expired = expired)
        return Classification(ChangeOrigin.UNCERTAIN, copy(others + live.map { it.copy(lastObserved = value) }), expired = expired)
    }

    /**
     * Removes entries past their deadline and returns the expired writes; unused couplings just disappear.
     * Caller marks results SENT_UNCONFIRMED and treats [PendingWrite.lastObserved] as a user override.
     */
    fun expire(nowMs: Long): Pair<List<PendingWrite>, WriteLedger> {
        val (expired, alive) = pending.partition { nowMs > it.deadlineMs }
        return expired.filterNot { it.coupled } to copy(alive)
    }

    private fun resolveArea(signal: SignalId, area: AreaKey?): AreaKey = area ?: catalog[signal]?.area ?: AreaKeys.GLOBAL

    private fun roundingOf(signal: SignalId): Rounding = catalog[signal]?.rounding ?: Rounding.None

    private fun copy(pending: List<PendingWrite>) = WriteLedger(catalog, pending)

    private fun PendingWrite.matches(signal: SignalId, area: AreaKey) = this.signal == signal && this.area == area
}
