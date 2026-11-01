package kr.routinegongbang.core.engine

import kotlinx.coroutines.CompletableDeferred
import kr.routinegongbang.core.model.AreaKey
import kr.routinegongbang.core.model.SignalId
import kr.routinegongbang.core.model.SignalValue
import kr.routinegongbang.core.model.ValidatedRoutine
import java.time.Instant

/** Every input of the engine. Adapters post them; one coroutine handles them in order. */
sealed interface EngineEvent {
    /** [area] null means the signal's catalog area. Values must already be corrected by the adapter. */
    data class SignalChanged(val signal: SignalId, val value: SignalValue, val area: AreaKey? = null) : EngineEvent
    data class DrivingChanged(val state: DrivingState) : EngineEvent
    data class NavigationChanged(val navigating: Boolean) : EngineEvent
    data class CapabilityChanged(val snapshot: CapabilitySnapshot) : EngineEvent
    data class RoutinesChanged(val routines: List<ValidatedRoutine>) : EngineEvent
    data object NaviArrived : EngineEvent
    /** Sent about once a minute (and by the debug clock fast-forward): time triggers, trip end, ledger expiry. */
    data object Tick : EngineEvent
    /** "지금 실행해보기" (try it now): skips trigger, condition and limits, still applies safety and tiers. */
    data class RunNow(val routineId: String) : EngineEvent

    /** Completed once every event posted before it has been handled; see [RoutineEngine.postAndAwait]. */
    class Sync(internal val done: CompletableDeferred<Unit>) : EngineEvent

    /** Engine-internal: a plan dropped during its question, planned again after the change. */
    class Replan internal constructor(val routineIds: List<String>, val attempt: Int) : EngineEvent
}

/** What the platform reported when the service started. Readings only prime the edge detector. */
data class EngineStartup(
    val capability: CapabilitySnapshot,
    val routines: List<ValidatedRoutine>,
    val readings: Map<SignalId, SignalValue>,
    val driving: DrivingState,
    val navigating: Boolean = false,
)

/** Decision log entries for the debug panel and the per-routine history. */
sealed interface Decision {
    data class LimitBlocked(val routineId: String, val reason: LimitReason) : Decision
    data class ConditionNotMet(val routineId: String, val missing: Set<SignalId>) : Decision
    data class Ran(val report: ExecutionReport) : Decision
    data class ReminderShown(val routineId: String, val textRes: String) : Decision
    /** Persistence failed; the engine keeps running on its in-memory state and retries on the next change. */
    data class StoreFailed(val detail: String?) : Decision
    /** Handling one event threw; the loop logs it and continues with the next event. */
    data class EventFailed(val event: String?, val detail: String?) : Decision
    /** A hold ended with its state intact, or a wait ended; what runs next is its own plan. */
    data class TimerFired(val routineId: String, val kind: TimerKind) : Decision
}

enum class TimerKind { HOLD, WAIT }

/** Called on the engine coroutine; implementations must not block. */
interface EngineObserver {
    fun onDecision(decision: Decision) {}
    fun onResult(update: ResultUpdate) {}

    /** Habit detection hook: a trip started, including one started silently at service startup. */
    fun onTripStarted(tripId: String, at: Instant) {}

    /** The same moment with the values known then (habit context); by default only the plain hook. */
    fun onTripStarted(tripId: String, at: Instant, values: Map<SignalId, SignalValue>) = onTripStarted(tripId, at)

    /** Habit detection hook: a change the ledger attributed to the user (never the app's own writes). */
    fun onUserChange(tripId: String?, signal: SignalId, value: SignalValue, at: Instant) {}

    companion object {
        val NONE: EngineObserver = object : EngineObserver {}
    }
}
