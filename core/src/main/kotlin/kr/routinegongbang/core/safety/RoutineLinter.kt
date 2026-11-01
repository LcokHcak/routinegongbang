package kr.routinegongbang.core.safety

import kr.routinegongbang.core.model.Action
import kr.routinegongbang.core.model.CompareOp
import kr.routinegongbang.core.model.FLIP_WINDOW_MS
import kr.routinegongbang.core.model.Condition
import kr.routinegongbang.core.model.Routine
import kr.routinegongbang.core.model.SafetyClass
import kr.routinegongbang.core.model.SignalCatalog
import kr.routinegongbang.core.model.SignalId
import kr.routinegongbang.core.model.SignalIds
import kr.routinegongbang.core.model.SignalValue
import kr.routinegongbang.core.model.Trigger
import kr.routinegongbang.core.model.segments

enum class Severity { ERROR, WARNING }

/** One L1 finding. [rule] is the rule id, e.g. "L1-9"; [detail] is for logs, not users. */
data class LintFinding(val rule: String, val severity: Severity, val routineId: String, val detail: String, val signal: SignalId? = null)

/**
 * L1 static checks, run when a routine is saved, imported or reordered. Errors block saving
 * ([kr.routinegongbang.core.model.RoutineValidator] includes them); warnings are shown in the editor.
 * The engine checks everything again at run time, so nothing here is the only line of defence.
 */
class RoutineLinter(private val catalog: SignalCatalog) {

    private val arbiter = Arbiter(catalog)

    fun lint(routine: Routine): List<LintFinding> = buildList {
        val writes = routine.actions.filterIsInstance<Action.Write>()
        writes.forEach { addAll(checkWrite(routine, it)) }
        // L1-3 per segment: a wait lets a signal be written again on purpose.
        routine.segments().forEach { segment -> addAll(duplicateWrites(routine, segment.actions.map { routine.actions[it] }.filterIsInstance<Action.Write>())) }
        addAll(waitPlacement(routine))
        addAll(quickRewrites(routine))
        selfTrigger(routine, writes)?.let { add(it) }
        thresholdCooldown(routine)?.let { add(it) }
        routine.condition?.takeIf { neverHolds(it) }?.let { add(finding("L1-7", Severity.ERROR, routine, "the condition can never hold")) }
    }

    /** Per-routine checks plus the ones that need the whole list, in list (priority) order. */
    fun lintAll(routines: List<Routine>): List<LintFinding> = buildList {
        routines.forEach { addAll(lint(it)) }
        addAll(conflicts(routines))
        if (routines.size > MAX_ROUTINES) add(LintFinding("L1-12", Severity.ERROR, routines[MAX_ROUTINES].id, "more than $MAX_ROUTINES routines"))
    }

    private fun checkWrite(routine: Routine, write: Action.Write): List<LintFinding> {
        val spec = catalog[write.signal] ?: return emptyList()
        val profile = spec.profile ?: return emptyList()
        val parkedOnly = spec.safetyClass == SafetyClass.BODY || spec.safetyClass == SafetyClass.ENERGY
        val trigger = routine.trigger
        return buildList {
            val outside = profile.autoRange?.let { range -> (write.value as? SignalValue.Num)?.value?.let { it !in range } } ?: false
            if (outside) add(finding("L1-2", Severity.WARNING, routine, "outside the automatic range; it will ask first", spec.id))
            if (parkedOnly && trigger is Trigger.RideEnd) add(finding("L1-9", Severity.ERROR, routine, "nobody is left to confirm or watch", spec.id))
            if (parkedOnly && mayFireWhileDriving(trigger)) add(finding("L1-8", Severity.WARNING, routine, "while driving this only becomes a reminder", spec.id))
            if (profile.visibility?.staticViolation(write.value) != null) add(finding("L1-10", Severity.ERROR, routine, "this would make the view worse", spec.id))
            // An unknown current value: a level that only must not go down counts as possibly weakening.
            if (profile.lifeSupport?.weakens(write.value, current = null) == true) {
                when (trigger) {
                    Trigger.RideEnd -> add(finding("L1-11", Severity.ERROR, routine, "someone left in the car may need the climate", spec.id))
                    is Trigger.TimeOfDay -> add(finding("L1-11", Severity.WARNING, routine, "skipped whenever there is no trip", spec.id))
                    else -> Unit
                }
            }
        }
    }

    /**
     * L1-3: a second write of the same signal can only undo the first (error). A write of a signal coupled with an
     * earlier one deliberately corrects what the car changed, e.g. recirculation back on after max defrost (note).
     */
    private fun duplicateWrites(routine: Routine, writes: List<Action.Write>): List<LintFinding> {
        val seen = mutableListOf<SignalId>()
        return writes.mapNotNull { write ->
            val finding = when {
                write.signal in seen -> finding("L1-3", Severity.ERROR, routine, "the signal is written twice", write.signal)
                seen.any { arbiter.related(it, write.signal) } -> finding("L1-3", Severity.WARNING, routine, "overrides what the car changed along with an earlier write", write.signal)
                else -> null
            }
            seen += write.signal
            finding
        }
    }

    /** L1-4: a routine's own writes never trigger it, which may surprise the person who wrote it. */
    private fun selfTrigger(routine: Routine, writes: List<Action.Write>): LintFinding? {
        val signal = triggerSignals(routine.trigger).firstOrNull { s -> writes.any { arbiter.related(it.signal, s) } } ?: return null
        return if (writes.any { arbiter.related(it.signal, signal) }) finding("L1-4", Severity.WARNING, routine, "its own writes never trigger it again", signal) else null
    }

    private fun thresholdCooldown(routine: Routine): LintFinding? =
        if (comparesSizes(routine.trigger) && routine.limits.cooldownMinutes < MIN_THRESHOLD_COOLDOWN_MINUTES) {
            finding("L1-6", Severity.ERROR, routine, "a value hovering at the threshold would fire again and again")
        } else {
            null
        }

    /** L1-7: the obvious contradictions, `A = x and A = y` with x ≠ y, at any depth of an And. */
    private fun neverHolds(condition: Condition): Boolean = when (condition) {
        is Condition.And -> {
            val equalities = condition.conditions.filterIsInstance<Condition.Compare>().filter { it.op == CompareOp.EQ }
            equalities.groupBy { it.signal }.values.any { same -> same.map { it.value }.toSet().size > 1 } ||
                condition.conditions.any { neverHolds(it) }
        }
        is Condition.Or -> condition.conditions.isNotEmpty() && condition.conditions.all { neverHolds(it) }
        else -> false
    }

    /** L1-5: a later routine that can fire with an earlier one and writes the same signal differently loses. */
    private fun conflicts(routines: List<Routine>): List<LintFinding> = buildList {
        routines.forEachIndexed { i, later ->
            routines.take(i).filter { mayCoincide(it.trigger, later.trigger) }.forEach { earlier ->
                val earlierWrites = earlier.actions.filterIsInstance<Action.Write>()
                later.actions.filterIsInstance<Action.Write>().forEach { write ->
                    val clash = earlierWrites.firstOrNull { arbiter.related(it.signal, write.signal) && (it.signal != write.signal || it.value != write.value) }
                    if (clash != null) add(finding("L1-5", Severity.WARNING, later, "loses to ${earlier.id} when both fire", write.signal))
                }
            }
        }
    }

    /** Conservative where unsure; edges towards different values of one signal never coincide. */
    private fun mayCoincide(a: Trigger, b: Trigger): Boolean = when {
        a == b -> true
        a is Trigger.SignalEdge && b is Trigger.SignalEdge -> a.signal == b.signal && a.to.any { it in b.to }
        a is Trigger.RideStart || b is Trigger.RideStart -> edgeTo(if (a is Trigger.RideStart) b else a, SignalIds.SEAT_OCCUPIED_DRIVER)
        a is Trigger.RideEnd || b is Trigger.RideEnd -> edgeTo(if (a is Trigger.RideEnd) b else a, SignalIds.DOOR_LOCK)
        else -> triggerSignals(a).any { it in triggerSignals(b) }
    }

    /** A ride starts when the seat fills and ends when the car locks: only those edges come with them. */
    private fun edgeTo(trigger: Trigger, signal: SignalId): Boolean =
        trigger is Trigger.SignalEdge && trigger.signal == signal && SignalValue.Bool(true) in trigger.to

    /** Every signal whose change can fire [trigger]; an all-met trigger listens to all of its comparisons. */
    private fun triggerSignals(trigger: Trigger): Set<SignalId> = when (trigger) {
        is Trigger.SignalEdge -> setOf(trigger.signal)
        is Trigger.Threshold -> setOf(trigger.signal)
        is Trigger.AllOf -> trigger.conditions.map { it.signal }.toSet()
        else -> emptySet()
    }

    /** L1-6 applies to every trigger that fires on a size comparison: a hold time does not replace the cooldown. */
    private fun comparesSizes(trigger: Trigger): Boolean =
        trigger is Trigger.Threshold || (trigger is Trigger.AllOf && trigger.conditions.any { it.op.ordering })

    /** L1-13: a wait with nothing after it, or right after another wait, does nothing but delay. */
    private fun waitPlacement(routine: Routine): List<LintFinding> {
        val actions = routine.actions
        val last = actions.lastOrNull() is Action.Wait
        val doubled = actions.zipWithNext().any { (a, b) -> a is Action.Wait && b is Action.Wait }
        return if (last || doubled) listOf(finding("L1-13", Severity.ERROR, routine, "a wait must be followed by something to do")) else emptyList()
    }

    /**
     * L1-14: writing a signal again after a wait shorter than the flip-back window may be skipped at run time as
     * flipping back and forth.
     */
    private fun quickRewrites(routine: Routine): List<LintFinding> {
        var waited = 0L
        val lastWrite = mutableMapOf<SignalId, Long>()
        return routine.actions.mapNotNull { action ->
            when (action) {
                is Action.Wait -> null.also { waited += action.seconds * 1_000L }
                is Action.Write -> {
                    val before = lastWrite[action.signal]
                    lastWrite[action.signal] = waited
                    if (before != null && waited > before && waited - before < FLIP_WINDOW_MS) {
                        finding("L1-14", Severity.WARNING, routine, "written again too soon; it may be skipped as flipping back", action.signal)
                    } else {
                        null
                    }
                }
                else -> null
            }
        }
    }

    /** Ride start and end happen parked by definition; anything else may come while moving. */
    private fun mayFireWhileDriving(trigger: Trigger): Boolean = trigger !is Trigger.RideStart && trigger !is Trigger.RideEnd

    private fun finding(rule: String, severity: Severity, routine: Routine, detail: String, signal: SignalId? = null) =
        LintFinding(rule, severity, routine.id, detail, signal)

    companion object {
        const val MAX_ROUTINES: Int = 50
        const val MIN_THRESHOLD_COOLDOWN_MINUTES: Int = 10
    }
}
