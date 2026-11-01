package kr.routinegongbang.app.ui.editor

import kr.routinegongbang.app.R
import kr.routinegongbang.core.model.Action
import kr.routinegongbang.core.model.CompareOp
import kr.routinegongbang.core.model.Occupancy
import kr.routinegongbang.core.model.Condition
import kr.routinegongbang.core.model.Routine
import kr.routinegongbang.core.model.RoutineValidator
import kr.routinegongbang.core.model.SignalCatalog
import kr.routinegongbang.core.model.SignalIds
import kr.routinegongbang.core.model.SignalValue
import kr.routinegongbang.core.model.Trigger
import kr.routinegongbang.core.model.ValidationIssue
import kr.routinegongbang.core.safety.RoutineLinter
import kr.routinegongbang.core.safety.Severity

/** One thing the editor tells the driver before saving; [message] is a Korean string resource. */
data class Problem(val level: Level, val message: Int) {
    enum class Level { ERROR, WARNING }
}

data class CheckResult(val problems: List<Problem>) {
    val canSave: Boolean get() = problems.none { it.level == Problem.Level.ERROR }
}

/**
 * Runs the save checks of the core (validator and L1 safety lint) on a routine being edited and says
 * what they found in Korean. Errors block saving; warnings only explain. The routine is checked in its place in the
 * list, because the list order is the priority (L1-5).
 */
class SaveCheck(private val catalog: SignalCatalog) {

    private val validator = RoutineValidator(catalog)
    private val linter = RoutineLinter(catalog)

    fun of(routine: Routine, others: List<Routine>): CheckResult {
        val errors = validator.validate(routine).map { Problem(Problem.Level.ERROR, messageFor(it)) }
        val list = if (others.any { it.id == routine.id }) others.map { if (it.id == routine.id) routine else it } else others + routine
        // List-wide rules (L1-12, at most 50 routines) are errors the per-routine validator never sees.
        val linted = linter.lintAll(list).filter { it.routineId == routine.id }
            .map { Problem(if (it.severity == Severity.ERROR) Problem.Level.ERROR else Problem.Level.WARNING, messageFor(it.rule, it.severity == Severity.ERROR)) }
        val dead = if (neverHolds(RoutineDraft.conditions(routine))) listOf(Problem(Problem.Level.ERROR, R.string.editor_err_condition_never)) else emptyList()
        return CheckResult((errors + linted + dead + userTestChecks(routine, others)).distinct())
    }

    /** What would pass with "문제없어요" although a driver should hear about it. */
    private fun userTestChecks(routine: Routine, others: List<Routine>): List<Problem> = buildList {
        // Two identical routines double every action, e.g. two navigation requests at once.
        // Only a copy that would run alongside counts: a switched-off backup or one with other limits does not.
        if (routine.enabled && others.any { it.id != routine.id && it.enabled && sameWork(it, routine) }) {
            add(Problem(Problem.Level.ERROR, R.string.editor_err_duplicate))
        }
        // The engine never weakens climate for someone left inside, but switching climate or heating on for an empty
        // car runs. Only what switches on and needs nobody in a seat to run.
        if (routine.trigger == Trigger.RideEnd && routine.actions.filterIsInstance<Action.Write>().any { switchesOnUnattended(it) }) {
            add(Problem(Problem.Level.WARNING, R.string.editor_warn_nobody_inside))
        }
        if (routine.actions.any { it is Action.SetDestination }) {
            add(Problem(Problem.Level.WARNING, R.string.editor_warn_navi_place))
            // Getting in or out happens parked; any other trigger may come while driving, where a route is set at once
            // unless the routine asks first (ActionPlanner.planDestination).
            val mayFireWhileDriving = routine.trigger !in setOf(Trigger.RideStart, Trigger.RideEnd, EditorCatalog.trigger(TriggerChoice.PARKED))
            if (mayFireWhileDriving && !routine.policy.askBeforeRun && !routine.policy.confirmWhileDriving) {
                add(Problem(Problem.Level.WARNING, R.string.editor_warn_navi_driving))
            }
        }
    }

    private fun sameWork(a: Routine, b: Routine) =
        a.trigger == b.trigger && a.condition == b.condition && a.actions == b.actions && a.limits == b.limits && a.policy == b.policy

    private fun switchesOnUnattended(write: Action.Write): Boolean {
        if (write.signal !in UNATTENDED_LOADS) return false
        if (catalog[write.signal]?.profile?.occupancy?.let { it !is Occupancy.Any } == true) return false
        return when (val value = write.value) {
            is SignalValue.Bool -> value.value
            is SignalValue.Num -> value.value > 0
            is SignalValue.Text -> false
        }
    }

    /**
     * Conditions the editor can put together that no moment satisfies: weekdays-only and weekends-only, or a
     * number required below one bound and above a higher one (colder than 5 and warmer than 28).
     */
    private fun neverHolds(conditions: List<Condition>): Boolean {
        val days = conditions.filterIsInstance<Condition.DayType>()
        if (days.isNotEmpty() && !days.all { it.weekdays } && !days.all { it.weekends }) return true
        return conditions.filterIsInstance<Condition.Compare>().groupBy { it.signal }.values.any { same ->
            fun bound(vararg ops: CompareOp) = same.filter { it.op in ops }.mapNotNull { (it.value as? SignalValue.Num)?.value }
            val lowest = bound(CompareOp.GE, CompareOp.GT).maxOrNull()
            val highest = bound(CompareOp.LE, CompareOp.LT).minOrNull()
            lowest != null && highest != null && lowest > highest
        }
    }

    companion object {
        /** Climate and heating that run on battery with nobody inside; lights and settings such as temperature are not. */
        private val UNATTENDED_LOADS = setOf(
            SignalIds.HVAC_POWER, SignalIds.HVAC_AC, SignalIds.HVAC_MAX_AC, SignalIds.HVAC_FAN_SPEED,
            SignalIds.STEERING_WHEEL_HEAT, SignalIds.SEAT_HEAT_DRIVER, SignalIds.SEAT_VENT_DRIVER,
        )

        /** Every L1 rule in words a driver understands; [error] picks the wording of its severity. */
        fun messageFor(rule: String, error: Boolean = true): Int = when (rule) {
            "L1-2" -> R.string.editor_warn_will_ask
            "L1-3" -> if (error) R.string.editor_err_written_twice else R.string.editor_warn_coupled
            "L1-4" -> R.string.editor_warn_self
            "L1-5" -> R.string.editor_warn_conflict
            "L1-6" -> R.string.editor_err_cooldown
            "L1-7" -> R.string.editor_err_condition_never
            "L1-8" -> R.string.editor_warn_driving
            "L1-9" -> R.string.editor_err_nobody_left
            "L1-10" -> R.string.editor_err_visibility
            "L1-11" -> if (error) R.string.editor_err_someone_left else R.string.editor_warn_no_trip
            "L1-12" -> R.string.editor_err_too_many_routines
            "L1-13" -> R.string.editor_err_wait_placement
            "L1-14" -> R.string.editor_warn_flip
            else -> R.string.editor_err_generic
        }

        private fun messageFor(issue: ValidationIssue): Int = when (issue) {
            is ValidationIssue.Unsafe -> messageFor(issue.rule)
            ValidationIssue.NoActions -> R.string.editor_err_no_actions
            ValidationIssue.TooManyActions -> R.string.editor_err_too_many
            ValidationIssue.InvalidDuration -> R.string.editor_err_duration
            ValidationIssue.NeverMatches -> R.string.editor_err_condition_never
            ValidationIssue.BlankName -> R.string.editor_err_name
            is ValidationIssue.Excluded -> R.string.editor_err_excluded
            else -> R.string.editor_err_generic
        }
    }
}

/** The one-line summary of a routine: conditions, then when, then what. */
object Sentence {
    fun join(trigger: String, conditions: List<String>, actions: List<String>, nothing: String = ""): String =
        (conditions + trigger).joinToString(" · ") + " → " + actions.ifEmpty { listOf(nothing) }.joinToString(", ")
}
