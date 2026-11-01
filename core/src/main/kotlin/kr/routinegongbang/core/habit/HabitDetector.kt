package kr.routinegongbang.core.habit

import kr.routinegongbang.core.engine.ConditionEvaluator
import kr.routinegongbang.core.model.Action
import kr.routinegongbang.core.model.CompareOp
import kr.routinegongbang.core.model.SignalIds
import kr.routinegongbang.core.model.Condition
import kr.routinegongbang.core.model.Limits
import kr.routinegongbang.core.model.Origin
import kr.routinegongbang.core.model.Rounding
import kr.routinegongbang.core.model.Routine
import kr.routinegongbang.core.model.RoutineValidator
import kr.routinegongbang.core.model.SignalCatalog
import kr.routinegongbang.core.model.SignalValue
import kr.routinegongbang.core.model.Trigger
import kr.routinegongbang.core.model.ValidatedRoutine
import kr.routinegongbang.core.model.ValidationOutcome
import java.time.Instant
import java.time.ZoneId

/** A routine proposal; [routine] already passed validation and is what the user accepts as is. */
data class Suggestion(val pattern: HabitPattern, val supportingTrips: Int, val routine: ValidatedRoutine)

/**
 * Among the last [HabitConfig.lookbackTrips] trips whose context is near the given trip,
 * a pattern seen in at least [HabitConfig.minOccurrences] of them becomes a RideStart routine.
 * The app calls [suggest] once the car is parked (the first minutes of the trip are then complete) and records
 * the answer with [HabitLog.withFeedback]; [HabitBook] does both safely.
 * Known limit: day kind follows the calendar, so Friday 23:30 and Saturday 00:20 are different contexts.
 * While Vehicle Control is closed the routine runs as NOTIFY, which is still a complete loop.
 */
class HabitDetector(
    private val catalog: SignalCatalog,
    /** The zone the [HabitRecorder] used; existing routines' conditions are evaluated in it. */
    zone: ZoneId,
    private val config: HabitConfig = HabitConfig(),
    private val namer: (HabitPattern) -> String = { "${it.signal.value} → ${it.value.label()}" },
) {
    private val validator = RoutineValidator(catalog)
    private val conditions = ConditionEvaluator(catalog, zone)

    fun suggest(log: HabitLog, tripId: String, existing: List<Routine>, nowEpochMs: Long): List<Suggestion> {
        val current = log.trips.lastOrNull { it.tripId == tripId } ?: return emptyList()
        // A ride whose band is unknown pools only rides of unknown band: matching every band, it would build a
        // band-free routine that runs whatever the temperature. A known band still takes older versions' band-less records.
        val similar = log.trips
            .filter { it.context.isNear(current.context, config.contextToleranceMinutes) && (current.context.cabinBand != null || it.context.cabinBand == null) }
            .sortedByDescending { it.startedAtEpochMs }
            .take(config.lookbackTrips)
        val finalPatterns = finalPatternsByTrip(log.operations, similar.map { it.tripId }.toSet())
        return similar.flatMap { finalPatterns[it.tripId].orEmpty() }
            .groupingBy { it }
            .eachCount()
            .filter { (pattern, count) ->
                count >= config.minOccurrences && !isQuiet(log, pattern, current.context, nowEpochMs) && !isCovered(existing, pattern, current)
            }
            .mapNotNull { (pattern, count) -> build(pattern, count, current.context) }
            .sortedWith(compareByDescending<Suggestion> { it.supportingTrips }.thenBy { it.pattern.signal.value })
    }

    /** Per trip, the last value set for each signal; turning a knob 1 → 2 counts once, as 2. */
    private fun finalPatternsByTrip(operations: List<OperationRecord>, tripIds: Set<String>): Map<String, Set<HabitPattern>> =
        operations.filter { it.tripId in tripIds }
            .groupBy { it.tripId }
            .mapValues { (_, ops) ->
                ops.groupBy { it.pattern.signal }.values.map { perSignal -> perSignal.sortedBy { it.atEpochMs }.last().pattern }.toSet()
            }

    /**
     * Answers apply to their own context only. Accepted and rejected stay quiet for [HabitConfig.rejectionQuietMs]
     * (after that an existing routine still suppresses it); [MAX_UNANSWERED] ignored showings count as a rejection.
     */
    private fun isQuiet(log: HabitLog, pattern: HabitPattern, context: RideContext, nowEpochMs: Long): Boolean {
        val answer = log.feedback.lastOrNull { it.pattern == pattern && it.context.isNear(context, config.contextToleranceMinutes) } ?: return false
        val elapsed = nowEpochMs - answer.atEpochMs
        val quietFor = when {
            answer.kind != FeedbackKind.SHOWN -> config.rejectionQuietMs
            answer.shownCount >= MAX_UNANSWERED -> config.rejectionQuietMs
            else -> config.shownQuietMs
        }
        return elapsed < quietFor
    }

    /**
     * A ride-start routine that already writes this value and whose condition holds at this ride's start, even a
     * disabled one (the user turned it off on purpose). A weekday-morning routine does not cover an evening habit.
     * The ride's cabin band stands in for the cabin temperature, so an adopted cold-morning routine covers cold mornings
     * (without it, it never does, and the suggestion comes back). Other car values are unknown here, so
     * conditions on them never count as covering (the habit is shown).
     */
    private fun isCovered(existing: List<Routine>, pattern: HabitPattern, ride: TripRecord): Boolean {
        val rounding = catalog[pattern.signal]?.rounding ?: Rounding.None
        val values = ride.context.cabinBand?.let { mapOf(SignalIds.CABIN_TEMPERATURE to SignalValue.Num(it.typicalCelsius)) }.orEmpty()
        return existing.any { routine ->
            routine.trigger == Trigger.RideStart &&
                routine.actions.any { action -> action is Action.Write && action.signal == pattern.signal && action.value.sameAs(pattern.value, rounding) } &&
                conditions.evaluate(routine.condition, values, Instant.ofEpochMilli(ride.startedAtEpochMs)).met
        }
    }

    private fun build(pattern: HabitPattern, count: Int, context: RideContext): Suggestion? {
        val tolerance = config.contextToleranceMinutes
        val day = RideContext.MINUTES_PER_DAY
        val condition = Condition.And(
            listOfNotNull(
                Condition.DayType(weekdays = context.dayKind == DayKind.WEEKDAY, weekends = context.dayKind == DayKind.WEEKEND),
                // TimeWindow's end is exclusive; +1 keeps a trip exactly at the tolerance inside, like isNear.
                Condition.TimeWindow(
                    startMinute = Math.floorMod(context.minuteOfDay - tolerance, day),
                    endMinute = Math.floorMod(context.minuteOfDay + tolerance + 1, day),
                ),
                // Learned on cold (hot) mornings, it runs on cold (hot) mornings only; mild needs no condition.
                when (context.cabinBand) {
                    TempBand.COLD -> Condition.Compare(SignalIds.CABIN_TEMPERATURE, CompareOp.LE, SignalValue.Num(TempBand.COLD_MAX_C))
                    TempBand.HOT -> Condition.Compare(SignalIds.CABIN_TEMPERATURE, CompareOp.GE, SignalValue.Num(TempBand.HOT_MIN_C))
                    TempBand.MILD, null -> null
                },
            ),
        )
        val routine = Routine(
            id = "habit-${pattern.signal.value}-${pattern.value.label()}-${context.dayKind.name.lowercase()}-${"%04d".format(context.minuteOfDay / 60 * 100 + context.minuteOfDay % 60)}" +
                (context.cabinBand?.takeIf { it != TempBand.MILD }?.let { "-${it.name.lowercase()}" } ?: ""),
            name = namer(pattern),
            trigger = Trigger.RideStart,
            condition = condition,
            actions = listOf(Action.Write(pattern.signal, pattern.value)),
            limits = Limits(oncePerTrip = true),
            origin = Origin.AI_SUGGESTION,
        )
        return when (val outcome = validator.validated(routine)) {
            is ValidationOutcome.Valid -> Suggestion(pattern, count, outcome.routine)
            is ValidationOutcome.Invalid -> null
        }
    }

    companion object {
        const val MAX_UNANSWERED: Int = 3
    }
}

private fun SignalValue.label(): String = when (this) {
    is SignalValue.Bool -> value.toString()
    is SignalValue.Num -> if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
    is SignalValue.Text -> value
}
