package kr.routinegongbang.app.ui.model

import kr.routinegongbang.core.engine.ActionResult
import kr.routinegongbang.core.engine.Decision
import kr.routinegongbang.core.engine.ExecutionReport
import kr.routinegongbang.core.engine.LimitReason
import kr.routinegongbang.core.engine.Reason
import kr.routinegongbang.core.engine.ResultUpdate
import kr.routinegongbang.core.model.Action
import kr.routinegongbang.core.model.CompareOp
import kr.routinegongbang.core.model.Condition
import kr.routinegongbang.core.model.SignalId
import kr.routinegongbang.core.model.SignalValue
import kr.routinegongbang.core.model.Trigger

/** The latest run of one routine, shown action by action. */
data class LastRun(val results: List<ActionResult>, val atEpochMs: Long, val dryRun: Boolean, val simulated: Boolean, val runId: Long = 0)

/**
 * Latest run per routine. Confirmations arrive later as [ResultUpdate]s; one applies only to the run it names
 * and only to a result still waiting ([ActionResult.Sent]), so a late update never lands on a newer run. Immutable.
 */
class RunHistory(private val runs: Map<String, LastRun> = emptyMap(), private val skips: Map<String, Skip> = emptyMap()) {

    operator fun get(routineId: String): LastRun? = runs[routineId]

    /** Why the routine did not run the last time it was due, if that is newer than its last run. */
    fun skip(routineId: String): Skip? = skips[routineId]

    fun withReport(report: ExecutionReport, atEpochMs: Long): RunHistory =
        RunHistory(runs + (report.routineId to LastRun(report.results, atEpochMs, report.dryRun, report.simulated, report.runId)), skips - report.routineId)

    fun withSkip(routineId: String, skip: Skip): RunHistory = RunHistory(runs, skips + (routineId to skip))

    /** The skip no longer holds: the run it waited for moved on, or the routine was changed. */
    fun withoutSkip(routineId: String): RunHistory = if (routineId in skips) RunHistory(runs, skips - routineId) else this

    /** The routine's actions changed: results by position would describe actions it no longer has. */
    fun forget(routineId: String): RunHistory = RunHistory(runs - routineId, skips - routineId)

    /** Parked again: the notices held during the drive went out in the digest. */
    fun withHeldShown(): RunHistory =
        if (runs.values.none { it.results.anyHeld() }) this else RunHistory(runs.mapValues { (_, run) -> run.copy(results = run.results.heldShown()) }, skips)

    fun withUpdate(update: ResultUpdate): RunHistory {
        val run = runs[update.routineId] ?: return this
        val waiting = run.results.getOrNull(update.actionIndex).let { it is ActionResult.Sent || it == ActionResult.Scheduled }
        if (run.runId != update.runId || !waiting) return this
        val results = run.results.toMutableList().also { it[update.actionIndex] = update.result }
        return RunHistory(runs + (update.routineId to run.copy(results = results)), skips)
    }
}

internal fun List<ActionResult>.anyHeld(): Boolean = any { it is ActionResult.Notified && it.held }

/** The same results with the notices that waited for parking counted as told. */
internal fun List<ActionResult>.heldShown(): List<ActionResult> =
    if (!anyHeld()) this else map { if (it is ActionResult.Notified && it.held) it.copy(held = false) else it }

/** What the driver sees for one action: three execution states plus the non-execution outcomes. */
enum class Status { CONFIRMED, WAITING, UNCONFIRMED, FAILED, NOTIFIED, NOTIFIED_INSTEAD, SKIPPED, CANCELLED, ALREADY_SET, LATER, REQUESTED, SCHEDULED, WAITED }

/**
 * What the driver reads first: three outcomes, plus two states that are not an outcome yet.
 * A write the car never confirmed stays apart from DONE (a successful call is not a completed action).
 * DONE means "the car is as the routine wants, or the request left the app": an already-set value counts (the car
 * reported it), a navigation request counts (the detail line says it was a request).
 */
enum class Outcome(val statuses: Set<Status>) {
    DONE(setOf(Status.CONFIRMED, Status.ALREADY_SET, Status.REQUESTED, Status.WAITED)),
    TOLD(setOf(Status.NOTIFIED, Status.NOTIFIED_INSTEAD)),
    NOT_DONE(setOf(Status.FAILED, Status.SKIPPED, Status.CANCELLED)),
    // A reminder held until parking has not been seen yet: it is still on its way, not told.
    IN_PROGRESS(setOf(Status.WAITING, Status.SCHEDULED, Status.LATER)),
    UNCONFIRMED(setOf(Status.UNCONFIRMED)),
    ;

    companion object {
        fun of(status: Status): Outcome = entries.first { status in it.statuses }
    }
}

/**
 * [navigation]: a failed route request, whose cause is almost always a place not registered in the navigation
 * rather than the car failing, which "차량 응답 오류" would suggest.
 */
data class StatusView(val status: Status, val reason: Reason? = null, val navigation: Boolean = false) {
    val outcome: Outcome get() = Outcome.of(status)

    companion object {
        fun of(result: ActionResult, action: Action?): StatusView =
            of(result).let { if (result is ActionResult.Failed && action is Action.SetDestination) it.copy(navigation = true) else it }

        fun of(result: ActionResult): StatusView = when (result) {
            is ActionResult.Confirmed -> StatusView(Status.CONFIRMED)
            is ActionResult.Sent -> StatusView(Status.WAITING)
            is ActionResult.SentUnconfirmed -> StatusView(Status.UNCONFIRMED)
            is ActionResult.Failed -> StatusView(Status.FAILED, result.reason)
            // Kept until parking, it has not been seen yet.
            is ActionResult.Notified -> when {
                result.held -> StatusView(Status.LATER)
                else -> result.reason?.let { StatusView(Status.NOTIFIED_INSTEAD, it) } ?: StatusView(Status.NOTIFIED)
            }
            is ActionResult.Skipped -> StatusView(Status.SKIPPED, result.reason)
            is ActionResult.Cancelled -> StatusView(Status.CANCELLED, result.reason)
            ActionResult.NoChange -> StatusView(Status.ALREADY_SET)
            ActionResult.Deferred -> StatusView(Status.LATER)
            ActionResult.Requested -> StatusView(Status.REQUESTED)
            ActionResult.Scheduled -> StatusView(Status.SCHEDULED)
            ActionResult.Waited -> StatusView(Status.WAITED)
        }
    }
}

/**
 * Why a routine did not run when its trigger came: the engine decides it. A card showing only runs would make a
 * skipped routine look broken or still show its previous "했어요".
 */
data class Skip(val kind: Kind, val atEpochMs: Long) {
    enum class Kind { CONDITION, NOT_READ_YET, COOLDOWN, DAILY_LIMIT, TRIP_LIMIT, SUSPENDED, STILL_WAITING }

    companion object {
        fun of(decision: Decision, atEpochMs: Long): Pair<String, Skip>? = when (decision) {
            is Decision.ConditionNotMet ->
                decision.routineId to Skip(if (decision.missing.isEmpty()) Kind.CONDITION else Kind.NOT_READ_YET, atEpochMs)
            is Decision.LimitBlocked -> decision.routineId to Skip(
                when (decision.reason) {
                    LimitReason.COOLDOWN -> Kind.COOLDOWN
                    LimitReason.DAILY_LIMIT -> Kind.DAILY_LIMIT
                    LimitReason.TRIP_LIMIT -> Kind.TRIP_LIMIT
                    LimitReason.SUSPENDED -> Kind.SUSPENDED
                    LimitReason.STILL_WAITING -> Kind.STILL_WAITING
                },
                atEpochMs,
            )
            else -> null
        }
    }
}

/** A trigger reduced to what the card says; the screen turns it into text. */
sealed interface TriggerSummary {
    data class Edge(val signal: SignalId, val to: List<SignalValue>, val holdSeconds: Int = 0) : TriggerSummary
    data class Threshold(val signal: SignalId, val op: CompareOp, val value: SignalValue, val holdSeconds: Int = 0) : TriggerSummary
    data class AllOf(val compares: List<ConditionSummary.Compare>, val holdSeconds: Int = 0) : TriggerSummary
    data class RideElapsed(val minutes: Int) : TriggerSummary
    data class At(val hour: Int, val minute: Int) : TriggerSummary
    data object RideStart : TriggerSummary
    data object RideEnd : TriggerSummary
    data object Arrived : TriggerSummary

    companion object {
        fun of(trigger: Trigger): TriggerSummary = when (trigger) {
            is Trigger.SignalEdge -> Edge(trigger.signal, trigger.to.toList(), trigger.holdSeconds)
            is Trigger.Threshold -> Threshold(trigger.signal, trigger.op, trigger.value, trigger.holdSeconds)
            is Trigger.AllOf -> AllOf(trigger.conditions.map { ConditionSummary.Compare(it.signal, it.op, it.value) }, trigger.holdSeconds)
            is Trigger.RideElapsed -> RideElapsed(trigger.minutes)
            is Trigger.TimeOfDay -> At(trigger.hour, trigger.minute)
            Trigger.RideStart -> RideStart
            Trigger.RideEnd -> RideEnd
            Trigger.NaviArrived -> Arrived
        }
    }
}

/** Conditions shown as short chips: day type, time windows and comparisons; Or / Not show as "other condition". */
sealed interface ConditionSummary {
    data object Weekdays : ConditionSummary
    data object Weekends : ConditionSummary
    data class Window(val from: String, val until: String) : ConditionSummary
    data class Compare(val signal: SignalId, val op: CompareOp, val value: SignalValue) : ConditionSummary
    data object Other : ConditionSummary

    companion object {
        fun of(condition: Condition?): List<ConditionSummary> = when (condition) {
            null -> emptyList()
            is Condition.And -> condition.conditions.flatMap { of(it) }.distinct()
            is Condition.DayType -> when {
                condition.weekdays && !condition.weekends -> listOf(Weekdays)
                condition.weekends && !condition.weekdays -> listOf(Weekends)
                else -> emptyList()
            }
            is Condition.TimeWindow -> listOf(Window(clock(condition.startMinute), clock(condition.endMinute)))
            is Condition.Compare -> listOf(Compare(condition.signal, condition.op, condition.value))
            is Condition.Or, is Condition.Not -> listOf(Other)
        }

        private fun clock(minuteOfDay: Int) = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
    }
}

sealed interface ActionSummary {
    data class Set(val signal: SignalId, val value: SignalValue) : ActionSummary
    data class Navigate(val placeId: String) : ActionSummary
    data class Message(val textRes: String) : ActionSummary
    data class Reminder(val textRes: String) : ActionSummary
    data class Wait(val seconds: Int) : ActionSummary

    companion object {
        fun of(action: Action): ActionSummary = when (action) {
            is Action.Write -> Set(action.signal, action.value)
            is Action.SetDestination -> Navigate(action.placeId)
            is Action.Notify -> Message(action.textRes)
            is Action.Remind -> Reminder(action.textRes)
            is Action.Wait -> Wait(action.seconds)
        }
    }
}
