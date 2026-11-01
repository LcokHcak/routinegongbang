package kr.routinegongbang.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Storage and sharing format of a routine. Immutable; edit with copy(). */
@Serializable
data class Routine(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val trigger: Trigger,
    val condition: Condition? = null,
    val actions: List<Action>,
    val limits: Limits = Limits(),
    val policy: Policy = Policy(),
    val origin: Origin = Origin.USER,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}

@Serializable
sealed interface Trigger {
    /** Fires only on the transition into [to] (and out of [from] when given). */
    @Serializable
    @SerialName("SignalEdge")
    data class SignalEdge(val signal: SignalId, val from: Set<SignalValue>? = null, val to: Set<SignalValue>, override val holdSeconds: Int = 0) : Trigger, Held

    /**
     * Fires when `signal op value` turns from false to true, e.g. SOC >= 80 while charging (demo D3).
     * Unlike [SignalEdge] it works when the value jumps over the threshold (79 → 81). Ordering operators only.
     */
    @Serializable
    @SerialName("Threshold")
    data class Threshold(val signal: SignalId, val op: CompareOp, val value: SignalValue, override val holdSeconds: Int = 0) : Trigger, Held

    @Serializable
    @SerialName("TimeOfDay")
    data class TimeOfDay(val hour: Int, val minute: Int) : Trigger

    @Serializable
    @SerialName("RideStart")
    data object RideStart : Trigger

    @Serializable
    @SerialName("RideEnd")
    data object RideEnd : Trigger

    @Serializable
    @SerialName("NaviArrived")
    data object NaviArrived : Trigger

    /**
     * Fires when every comparison holds after a change and did not all hold before it — the moment the
     * last one comes true, whatever order they came true in. Time and day restrictions belong in the condition.
     */
    @Serializable
    @SerialName("AllOf")
    data class AllOf(val conditions: List<Condition.Compare>, override val holdSeconds: Int = 0) : Trigger, Held

    /** Once per trip, when it has lasted [minutes]. */
    @Serializable
    @SerialName("RideElapsed")
    data class RideElapsed(val minutes: Int) : Trigger
}

/**
 * A trigger that waits until its state has lasted [holdSeconds] before it runs; 0 runs at the edge.
 * The state going away during the hold cancels it.
 */
sealed interface Held {
    val holdSeconds: Int
}

enum class CompareOp {
    EQ, NE, LT, LE, GT, GE;

    val ordering: Boolean get() = this == LT || this == LE || this == GT || this == GE

    /** Plain numeric comparison (no rounding tolerance). */
    fun holds(a: Double, b: Double): Boolean = when (this) {
        EQ -> a == b
        NE -> a != b
        LT -> a < b
        LE -> a <= b
        GT -> a > b
        GE -> a >= b
    }
}

@Serializable
sealed interface Condition {
    @Serializable
    @SerialName("Compare")
    data class Compare(val signal: SignalId, val op: CompareOp, val value: SignalValue) : Condition

    /** Minutes since local midnight; an end before the start wraps past midnight. */
    @Serializable
    @SerialName("TimeWindow")
    data class TimeWindow(val startMinute: Int, val endMinute: Int) : Condition

    @Serializable
    @SerialName("DayType")
    data class DayType(val weekdays: Boolean, val weekends: Boolean) : Condition

    @Serializable
    @SerialName("And")
    data class And(val conditions: List<Condition>) : Condition

    @Serializable
    @SerialName("Or")
    data class Or(val conditions: List<Condition>) : Condition

    @Serializable
    @SerialName("Not")
    data class Not(val condition: Condition) : Condition
}

@Serializable
sealed interface Action {
    @Serializable
    @SerialName("Write")
    data class Write(val signal: SignalId, val value: SignalValue, val area: AreaKey? = null) : Action

    @Serializable
    @SerialName("Notify")
    data class Notify(val textRes: String) : Action

    @Serializable
    @SerialName("SetDestination")
    data class SetDestination(val placeId: String) : Action

    @Serializable
    @SerialName("Remind")
    data class Remind(val textRes: String) : Action

    /** The rest of the routine runs [seconds] later, after everything is checked again. */
    @Serializable
    @SerialName("Wait")
    data class Wait(val seconds: Int) : Action
}

/** [maxPerDay] null means unlimited. */
@Serializable
data class Limits(
    val cooldownMinutes: Int = 0,
    val maxPerDay: Int? = null,
    val oncePerTrip: Boolean = false,
)

@Serializable
data class Policy(
    val confirmWhileDriving: Boolean = false,
    /** Every action with an effect is confirmed first, parked or driving. */
    val askBeforeRun: Boolean = false,
)

enum class Origin { TEMPLATE, USER, AI_SUGGESTION, SHARED }

/**
 * The actions run in segments separated by waits. [actions] are the indices that run together;
 * [waitAfter] is the index of the wait that ends the segment, null for the last one.
 */
data class Segment(val actions: IntRange, val waitAfter: Int?)

fun Routine.segments(): List<Segment> {
    val waits = actions.withIndex().filter { it.value is Action.Wait }.map { it.index }
    var start = 0
    return waits.map { wait -> Segment(start until wait, wait).also { start = wait + 1 } } + Segment(start until actions.size, null)
}
