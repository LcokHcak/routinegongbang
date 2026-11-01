package kr.routinegongbang.app.ui.editor

import kr.routinegongbang.core.model.Action
import kr.routinegongbang.core.model.Condition
import kr.routinegongbang.core.model.Limits
import kr.routinegongbang.core.model.Origin
import kr.routinegongbang.core.model.Rounding
import kr.routinegongbang.core.model.Routine
import kr.routinegongbang.core.model.SignalCatalog
import kr.routinegongbang.core.model.SignalId
import kr.routinegongbang.core.model.SignalIds
import kr.routinegongbang.core.model.SignalValue
import kr.routinegongbang.core.model.Trigger
import kr.routinegongbang.core.safety.RoutineLinter

/** Immutable edits of a routine being edited; every function returns a new routine. */
object RoutineDraft {

    private val catalog = SignalCatalog.default()
    private const val MINUTES_PER_DAY = 24 * 60
    private const val TIME_STEP_MINUTES = 15
    private const val WINDOW_STEP_MINUTES = 30
    private const val RIDE_STEP_MINUTES = 5
    private const val RIDE_MAX_MINUTES = 600
    private const val BATTERY_STEP = 5
    private const val WAIT_STEP_SECONDS = 60
    private const val WAIT_MAX_SECONDS = 600

    /** Runs once per drive unless the driver says otherwise ("운전 중 한 번만" on by default). */
    fun blank(id: String, name: String) =
        Routine(id = id, name = name, trigger = Trigger.RideStart, actions = emptyList(), limits = Limits(oncePerTrip = true), origin = Origin.USER)

    /** L1-6: a trigger compared by size needs a cooldown; the editor sets it instead of explaining it. */
    fun withTrigger(routine: Routine, trigger: Trigger): Routine {
        val needs = trigger is Trigger.Threshold || (trigger is Trigger.AllOf && trigger.conditions.any { it.op.ordering })
        val minimum = RoutineLinter.MIN_THRESHOLD_COOLDOWN_MINUTES
        val limits = if (needs && routine.limits.cooldownMinutes < minimum) routine.limits.copy(cooldownMinutes = minimum) else routine.limits
        return routine.copy(trigger = trigger, limits = limits)
    }

    /** The driver sees one list of conditions; the engine gets them as an And. */
    fun conditions(routine: Routine): List<Condition> = when (val c = routine.condition) {
        null -> emptyList()
        is Condition.And -> c.conditions
        else -> listOf(c)
    }

    fun withConditions(routine: Routine, conditions: List<Condition>): Routine =
        routine.copy(condition = conditions.singleOrNull() ?: conditions.takeIf { it.isNotEmpty() }?.let { Condition.And(it) })

    fun addAction(routine: Routine, action: Action) = routine.copy(actions = routine.actions + action)

    fun replaceAction(routine: Routine, index: Int, action: Action) =
        routine.copy(actions = routine.actions.mapIndexed { i, a -> if (i == index) action else a })

    fun removeAction(routine: Routine, index: Int) = routine.copy(actions = routine.actions.filterIndexed { i, _ -> i != index })

    /** The list order is the order of execution. */
    fun moveAction(routine: Routine, index: Int, up: Boolean): Routine {
        val target = if (up) index - 1 else index + 1
        if (index !in routine.actions.indices || target !in routine.actions.indices) return routine
        val list = routine.actions.toMutableList()
        list[index] = list[target].also { list[target] = list[index] }
        return routine.copy(actions = list)
    }

    fun withOncePerTrip(routine: Routine, once: Boolean) = routine.copy(limits = routine.limits.copy(oncePerTrip = once))

    fun withAskBeforeRun(routine: Routine, ask: Boolean) = routine.copy(policy = routine.policy.copy(askBeforeRun = ask))

    fun withName(routine: Routine, name: String) = routine.copy(name = name)

    /** One step of the action's value: a switch flips, a level or temperature moves within its range. */
    fun stepAction(action: Action, up: Boolean): Action = when (action) {
        is Action.Write -> action.copy(value = step(action.signal, action.value, up))
        is Action.Wait -> Action.Wait((action.seconds + if (up) WAIT_STEP_SECONDS else -WAIT_STEP_SECONDS).coerceIn(WAIT_STEP_SECONDS, WAIT_MAX_SECONDS))
        else -> action
    }

    fun stepTrigger(trigger: Trigger, up: Boolean): Trigger = when (trigger) {
        is Trigger.TimeOfDay -> clock(trigger.hour * 60 + trigger.minute + if (up) TIME_STEP_MINUTES else -TIME_STEP_MINUTES)
            .let { Trigger.TimeOfDay(it / 60, it % 60) }
        is Trigger.RideElapsed -> Trigger.RideElapsed((trigger.minutes + if (up) RIDE_STEP_MINUTES else -RIDE_STEP_MINUTES).coerceIn(RIDE_STEP_MINUTES, RIDE_MAX_MINUTES))
        // Only the battery threshold the editor offers; another one (imported) keeps its value.
        is Trigger.Threshold -> (trigger.value as? SignalValue.Num)?.takeIf { EditorCatalog.choiceOf(trigger) == TriggerChoice.BATTERY_LOW }?.let { v ->
            trigger.copy(value = SignalValue.num((v.value + if (up) BATTERY_STEP else -BATTERY_STEP).coerceIn(BATTERY_STEP.toDouble(), 100.0 - BATTERY_STEP)))
        } ?: trigger
        else -> trigger
    }

    /** [end]: which side of a time window moves. */
    fun stepCondition(condition: Condition, up: Boolean, end: Boolean = false): Condition = when (condition) {
        is Condition.TimeWindow -> {
            val delta = if (up) WINDOW_STEP_MINUTES else -WINDOW_STEP_MINUTES
            // Stepping onto the other end would make an empty window, which never matches: jump over it.
            fun moved(minute: Int, other: Int) = clock(minute + delta).let { if (it == other) clock(it + delta) else it }
            if (end) condition.copy(endMinute = moved(condition.endMinute, condition.startMinute))
            else condition.copy(startMinute = moved(condition.startMinute, condition.endMinute))
        }
        is Condition.Compare -> condition.copy(value = step(condition.signal, condition.value, up))
        else -> condition
    }

    private fun clock(minute: Int) = ((minute % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY

    private fun step(signal: SignalId, value: SignalValue, up: Boolean): SignalValue {
        val spec = catalog[signal] ?: return value
        return when (value) {
            is SignalValue.Bool -> SignalValue.Bool(!value.value)
            is SignalValue.Num -> {
                // The cabin sensor resolves tenths, but a driver thinks in whole degrees.
                val size = if (signal == SignalIds.CABIN_TEMPERATURE) 1.0 else (spec.rounding as? Rounding.Step)?.step ?: 1.0
                val next = value.value + if (up) size else -size
                SignalValue.num(spec.range?.let { next.coerceIn(it.start, it.endInclusive) } ?: next)
            }
            is SignalValue.Text -> spec.allowedValues?.toList()?.let { all ->
                val i = all.indexOf(value)
                all[((i + if (up) 1 else -1) % all.size + all.size) % all.size]
            } ?: value
        }
    }
}
