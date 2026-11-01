package kr.routinegongbang.core.engine

import kr.routinegongbang.core.model.AreaKey
import kr.routinegongbang.core.model.AreaKeys
import kr.routinegongbang.core.model.Rounding
import kr.routinegongbang.core.model.SignalCatalog
import kr.routinegongbang.core.model.SignalId
import kr.routinegongbang.core.model.SignalValue
import kr.routinegongbang.core.model.Trigger

/** A change of one signal in one area. [previous] is null when the value was never seen before. */
data class Transition(val signal: SignalId, val area: AreaKey, val previous: SignalValue?, val current: SignalValue)

/**
 * Remembers the last value per signal/area and turns raw events into transitions.
 * Priming values (startup snapshot) are stored but never produce a transition. Changes within the
 * signal's rounding tolerance are ignored and do not move the stored value. Immutable.
 */
class EdgeDetector(
    private val catalog: SignalCatalog,
    private val last: Map<Pair<SignalId, AreaKey>, SignalValue> = emptyMap(),
) {

    fun observe(signal: SignalId, value: SignalValue, priming: Boolean, area: AreaKey? = null): Pair<Transition?, EdgeDetector> {
        val resolvedArea = area ?: catalog[signal]?.area ?: AreaKeys.GLOBAL
        val key = signal to resolvedArea
        val previous = last[key]
        val rounding = catalog[signal]?.rounding ?: Rounding.None
        return when {
            priming -> null to EdgeDetector(catalog, last + (key to value))
            previous != null && previous.sameAs(value, rounding) -> null to this
            else -> Transition(signal, resolvedArea, previous, value) to EdgeDetector(catalog, last + (key to value))
        }
    }
}

/**
 * True when [transition] enters [Trigger.SignalEdge.to] (out of `from` when given), comparing with [rounding].
 * An unknown previous value never fires, so a missed startup snapshot cannot cause a spurious action.
 */
fun Trigger.SignalEdge.firesOn(transition: Transition, rounding: Rounding): Boolean {
    val previous = transition.previous ?: return false
    if (transition.signal != signal) return false
    if (to.none { transition.current.sameAs(it, rounding) }) return false
    val wasInTarget = to.any { previous.sameAs(it, rounding) }
    return from?.any { previous.sameAs(it, rounding) } ?: !wasInTarget
}

/**
 * True when [Trigger.Threshold] turns from false to true, compared with the signal [rounding] like conditions.
 * Unknown or non-numeric values never fire. No hysteresis: a value jittering around the threshold fires on each
 * crossing, so threshold routines should set a cooldown.
 */
fun Trigger.Threshold.firesOn(transition: Transition, rounding: Rounding): Boolean {
    if (transition.signal != signal || !op.ordering) return false
    val before = transition.previous?.satisfies(op, value, rounding) ?: return false
    val now = transition.current.satisfies(op, value, rounding) ?: return false
    return !before && now
}
