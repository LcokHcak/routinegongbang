package kr.routinegongbang.core.safety

import kr.routinegongbang.core.model.Action
import kr.routinegongbang.core.model.Routine
import kr.routinegongbang.core.model.SignalCatalog
import kr.routinegongbang.core.model.SignalId

/**
 * One writer per signal in a plan. Routines come in list order, which is their priority;
 * a write loses to an earlier routine's write of the same signal or of a signal directly coupled with it.
 * Couplings are never chained: two signals that only share a source (fan and A/C, both moved by max defrost) do
 * not compete. Inside one routine only a repeated signal loses; a later write may deliberately
 * correct what the car changed along with an earlier one.
 */
class Arbiter(private val catalog: SignalCatalog) {

    /** Same signal, or one is changed by the car when the other is written. */
    fun related(a: SignalId, b: SignalId): Boolean = a == b || b in coupled(a) || a in coupled(b)

    /** The signal plus the ones directly coupled with it in either direction (watching, ambiguity windows). */
    fun group(signal: SignalId): Set<SignalId> =
        setOf(signal) + coupled(signal) + catalog.all.filter { signal in it.profile?.coupledSignals.orEmpty() }.map { it.id }

    /**
     * For each routine, in the same order, the indices of its write actions that lose to an earlier writer. With
     * [active], only those action indices of each routine take part: the segment that runs now,
     * so a signal written again after a wait does not lose to its own earlier write.
     */
    fun suppressed(routines: List<Routine>, active: List<IntRange>? = null): List<Set<Int>> {
        val claimed = mutableListOf<Pair<Int, SignalId>>()
        return routines.mapIndexed { owner, routine ->
            routine.actions.withIndex().filter { (index, action) ->
                if (action !is Action.Write || (active != null && index !in active[owner])) return@filter false
                val lost = claimed.any { (by, signal) -> if (by == owner) signal == action.signal else related(signal, action.signal) }
                if (!lost) claimed += owner to action.signal
                lost
            }.map { it.index }.toSet()
        }
    }

    private fun coupled(signal: SignalId): Set<SignalId> = catalog[signal]?.profile?.coupledSignals.orEmpty()
}
