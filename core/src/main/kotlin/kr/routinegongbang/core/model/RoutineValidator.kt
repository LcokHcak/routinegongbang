package kr.routinegongbang.core.model

import kr.routinegongbang.core.safety.RoutineLinter
import kr.routinegongbang.core.safety.Severity

sealed interface ValidationIssue {
    data class UnsupportedSchema(val version: Int) : ValidationIssue
    data class UnknownSignal(val signal: SignalId) : ValidationIssue
    data class Unsupported(val signal: SignalId) : ValidationIssue
    /** Excluded action or forbidden value such as unlocking doors. */
    data class Excluded(val signal: SignalId) : ValidationIssue
    data class NotWritable(val signal: SignalId) : ValidationIssue
    data class NotSubscribable(val signal: SignalId) : ValidationIssue
    data class InvalidArea(val signal: SignalId, val area: AreaKey) : ValidationIssue
    data class TypeMismatch(val signal: SignalId, val expected: ValueType, val actual: ValueType) : ValidationIssue
    data class OutOfRange(val signal: SignalId, val value: SignalValue) : ValidationIssue
    data class InvalidOperator(val signal: SignalId, val op: CompareOp) : ValidationIssue
    data object EmptyTarget : ValidationIssue
    data object InvalidTime : ValidationIssue
    data object NeverMatches : ValidationIssue
    data object EmptyGroup : ValidationIssue
    data object TooDeep : ValidationIssue
    data object BlankId : ValidationIssue
    data object BlankName : ValidationIssue
    data object NoActions : ValidationIssue
    data object TooManyActions : ValidationIssue
    /** A hold time, ride time or wait outside its range. */
    data object InvalidDuration : ValidationIssue
    data object TooManyConditions : ValidationIssue
    data object InvalidLimits : ValidationIssue
    /** An L1 error from [RoutineLinter], e.g. "L1-9" for a lock after the driver left. */
    data class Unsafe(val rule: String, val signal: SignalId?) : ValidationIssue
}

/** Static checks run when a routine is saved or imported. */
class RoutineValidator(private val catalog: SignalCatalog) {

    private val linter = RoutineLinter(catalog)

    fun validate(routine: Routine): List<ValidationIssue> = buildList {
        if (routine.schemaVersion != Routine.CURRENT_SCHEMA_VERSION) add(ValidationIssue.UnsupportedSchema(routine.schemaVersion))
        if (routine.id.isBlank()) add(ValidationIssue.BlankId)
        if (routine.name.isBlank()) add(ValidationIssue.BlankName)
        // Waiting alone does nothing.
        if (routine.actions.none { it !is Action.Wait }) add(ValidationIssue.NoActions)
        if (routine.actions.size > MAX_ACTIONS) add(ValidationIssue.TooManyActions)
        if (!routine.limits.isValid()) add(ValidationIssue.InvalidLimits)
        addAll(checkTrigger(routine.trigger))
        routine.condition?.let { addAll(checkCondition(it, depth = 1)) }
        routine.actions.forEach { addAll(checkAction(it)) }
        if (!waitsAreValid(routine.actions.filterIsInstance<Action.Wait>())) add(ValidationIssue.InvalidDuration)
        // Only a structurally sound routine is worth the safety lint.
        if (isEmpty()) linter.lint(routine).filter { it.severity == Severity.ERROR }.forEach { add(ValidationIssue.Unsafe(it.rule, it.signal)) }
    }

    /** The only way to obtain a [ValidatedRoutine]. */
    fun validated(routine: Routine): ValidationOutcome {
        val issues = validate(routine)
        return if (issues.isEmpty()) ValidationOutcome.Valid(ValidatedRoutine(routine)) else ValidationOutcome.Invalid(issues)
    }

    private fun Limits.isValid(): Boolean =
        cooldownMinutes in 0..MAX_COOLDOWN_MINUTES && (maxPerDay == null || maxPerDay in 1..MAX_PER_DAY)

    private fun checkTrigger(trigger: Trigger): List<ValidationIssue> = when (trigger) {
        is Trigger.SignalEdge -> {
            val spec = resolve(trigger.signal).getOrElse { return listOf(it) }
            buildList {
                if (!spec.subscribable) add(ValidationIssue.NotSubscribable(spec.id))
                if (trigger.to.isEmpty()) add(ValidationIssue.EmptyTarget)
                (trigger.to + trigger.from.orEmpty()).forEach { addAll(valueIssues(spec, it)) }
            }
        }
        is Trigger.Threshold -> {
            val spec = resolve(trigger.signal).getOrElse { return listOf(it) }
            buildList {
                if (!spec.subscribable) add(ValidationIssue.NotSubscribable(spec.id))
                if (!trigger.op.ordering || spec.valueType != ValueType.NUMBER) add(ValidationIssue.InvalidOperator(spec.id, trigger.op))
                addAll(valueIssues(spec, trigger.value))
                if (!canCross(spec, trigger)) add(ValidationIssue.NeverMatches)
            }
        }
        is Trigger.TimeOfDay -> if (trigger.hour in 0..23 && trigger.minute in 0..59) emptyList() else listOf(ValidationIssue.InvalidTime)
        is Trigger.AllOf -> when {
            trigger.conditions.isEmpty() -> listOf(ValidationIssue.EmptyGroup)
            trigger.conditions.size > MAX_ALL_OF -> listOf(ValidationIssue.TooManyConditions)
            else -> trigger.conditions.flatMap { checkCompare(it) }.ifEmpty { if (contradicts(trigger.conditions)) listOf(ValidationIssue.NeverMatches) else emptyList() }
        }
        is Trigger.RideElapsed -> if (trigger.minutes in RIDE_MINUTES) emptyList() else listOf(ValidationIssue.InvalidDuration)
        Trigger.RideStart, Trigger.RideEnd, Trigger.NaviArrived -> emptyList()
    } + holdIssues(trigger)

    private fun holdIssues(trigger: Trigger): List<ValidationIssue> =
        if (trigger is Held && trigger.holdSeconds !in HOLD_SECONDS) listOf(ValidationIssue.InvalidDuration) else emptyList()

    private fun waitsAreValid(waits: List<Action.Wait>): Boolean =
        waits.all { it.seconds in WAIT_SECONDS } && waits.sumOf { it.seconds } <= MAX_TOTAL_WAIT_SECONDS

    /**
     * Comparisons on one signal that no value satisfies together: two different required values, or numeric bounds
     * that leave nothing between them (e.g. above 100 and below 80).
     */
    private fun contradicts(compares: List<Condition.Compare>): Boolean = compares.groupBy { it.signal }.values.any { same ->
        val equals = same.filter { it.op == CompareOp.EQ }.map { it.value }.toSet()
        if (equals.size > 1) return@any true
        val numbers = same.mapNotNull { c -> (c.value as? SignalValue.Num)?.value?.let { c.op to it } }
        val lower = numbers.filter { it.first == CompareOp.GT || it.first == CompareOp.GE }.maxByOrNull { it.second }
        val upper = numbers.filter { it.first == CompareOp.LT || it.first == CompareOp.LE }.minByOrNull { it.second }
        val exact = numbers.firstOrNull { it.first == CompareOp.EQ }?.second
        fun allows(v: Double) = (lower == null || lower.first.holds(v, lower.second)) && (upper == null || upper.first.holds(v, upper.second))
        when {
            exact != null -> !allows(exact)
            lower != null && upper != null -> lower.second > upper.second ||
                (lower.second == upper.second && (lower.first == CompareOp.GT || upper.first == CompareOp.LT))
            else -> false
        }
    }

    private fun checkCondition(condition: Condition, depth: Int): List<ValidationIssue> {
        if (depth > MAX_CONDITION_DEPTH) return listOf(ValidationIssue.TooDeep)
        return when (condition) {
            is Condition.Compare -> checkCompare(condition)
            is Condition.TimeWindow -> when {
                condition.startMinute !in MINUTES_OF_DAY || condition.endMinute !in MINUTES_OF_DAY -> listOf(ValidationIssue.InvalidTime)
                condition.startMinute == condition.endMinute -> listOf(ValidationIssue.NeverMatches)
                else -> emptyList()
            }
            is Condition.DayType -> if (condition.weekdays || condition.weekends) emptyList() else listOf(ValidationIssue.NeverMatches)
            is Condition.And -> checkGroup(condition.conditions, depth)
            is Condition.Or -> checkGroup(condition.conditions, depth)
            is Condition.Not -> checkCondition(condition.condition, depth + 1)
        }
    }

    private fun checkGroup(conditions: List<Condition>, depth: Int): List<ValidationIssue> =
        if (conditions.isEmpty()) listOf(ValidationIssue.EmptyGroup) else conditions.flatMap { checkCondition(it, depth + 1) }

    private fun checkCompare(compare: Condition.Compare): List<ValidationIssue> {
        val spec = resolve(compare.signal).getOrElse { return listOf(it) }
        val ordering = compare.op.ordering
        return buildList {
            if (ordering && spec.valueType != ValueType.NUMBER) add(ValidationIssue.InvalidOperator(spec.id, compare.op))
            addAll(valueIssues(spec, compare.value))
        }
    }

    private fun checkAction(action: Action): List<ValidationIssue> {
        if (action !is Action.Write) return emptyList()
        val spec = resolve(action.signal).getOrElse { return listOf(it) }
        return when {
            spec.safetyClass == SafetyClass.EXCLUDED -> listOf(ValidationIssue.Excluded(spec.id))
            !spec.writable -> listOf(ValidationIssue.NotWritable(spec.id))
            spec.profile?.direction?.staticViolation(action.value) != null -> listOf(ValidationIssue.Excluded(spec.id))
            else -> buildList {
                if (action.area != null && action.area != spec.area) add(ValidationIssue.InvalidArea(spec.id, action.area))
                addAll(valueIssues(spec, action.value))
            }
        }
    }

    /** A threshold at or beyond the edge of the signal's range is always or never met, so it never crosses. */
    private fun canCross(spec: SignalSpec, trigger: Trigger.Threshold): Boolean {
        val range = spec.range ?: return true
        val v = (trigger.value as? SignalValue.Num)?.value ?: return true
        return when (trigger.op) {
            CompareOp.GT, CompareOp.LE -> v < range.endInclusive
            CompareOp.GE, CompareOp.LT -> v > range.start
            CompareOp.EQ, CompareOp.NE -> true
        }
    }

    private fun valueIssues(spec: SignalSpec, value: SignalValue): List<ValidationIssue> = when {
        value.type != spec.valueType -> listOf(ValidationIssue.TypeMismatch(spec.id, spec.valueType, value.type))
        !spec.accepts(value) -> listOf(ValidationIssue.OutOfRange(spec.id, value))
        else -> emptyList()
    }

    /** Resolves a signal or returns the issue that makes it unusable. */
    private fun resolve(id: SignalId): Lookup {
        val spec = catalog[id] ?: return Lookup.Missing(ValidationIssue.UnknownSignal(id))
        return if (spec.unsupported) Lookup.Missing(ValidationIssue.Unsupported(id)) else Lookup.Found(spec)
    }

    private sealed interface Lookup {
        data class Found(val spec: SignalSpec) : Lookup
        data class Missing(val issue: ValidationIssue) : Lookup
    }

    private inline fun Lookup.getOrElse(onMissing: (ValidationIssue) -> Nothing): SignalSpec = when (this) {
        is Lookup.Found -> spec
        is Lookup.Missing -> onMissing(issue)
    }

    companion object {
        const val MAX_CONDITION_DEPTH: Int = 8
        const val MAX_ACTIONS: Int = 20
        const val MAX_COOLDOWN_MINUTES: Int = 24 * 60
        const val MAX_PER_DAY: Int = 100
        const val MAX_ALL_OF: Int = 8
        val HOLD_SECONDS = 0..3600
        val RIDE_MINUTES = 1..600
        /** Shorter waits only make things flicker on and off. */
        val WAIT_SECONDS = 10..600
        const val MAX_TOTAL_WAIT_SECONDS: Int = 1800
        private val MINUTES_OF_DAY = 0..1439
    }
}
