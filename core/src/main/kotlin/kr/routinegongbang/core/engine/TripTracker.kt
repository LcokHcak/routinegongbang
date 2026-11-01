package kr.routinegongbang.core.engine

import kotlinx.serialization.Serializable
import kr.routinegongbang.core.model.SignalId

/**
 * Persisted trip. [userOverrides] are the signals the user changed by hand during this trip; routines leave
 * them alone, and they end with the trip. Times are wall-clock epoch millis so they survive a restart.
 */
@Serializable
data class TripState(
    val tripId: String? = null,
    val startedAtEpochMs: Long? = null,
    val endPendingSinceEpochMs: Long? = null,
    val userOverrides: Set<SignalId> = emptySet(),
) {
    val active: Boolean get() = tripId != null
}

sealed interface TripEvent {
    data class Started(val tripId: String) : TripEvent
    /**
     * The driver left and locked the car; fires RideEnd right away (a reminder 5 minutes later would be too late).
     * Lock, unlock, lock within one trip reports it again; routines that must run once use `oncePerTrip`.
     */
    data class EndPending(val tripId: String) : TripEvent
    data class Ended(val tripId: String) : TripEvent
}

/** Trip starts when the driver sits down and ends after the car stays locked for [endDelayMs]. Pure. */
class TripTracker(private val endDelayMs: Long = DEFAULT_END_DELAY_MS) {

    fun onDriverSeat(state: TripState, occupied: Boolean, nowEpochMs: Long, newId: () -> String): Pair<TripState, TripEvent?> = when {
        !occupied -> state to null
        !state.active -> newId().let { id -> TripState(tripId = id, startedAtEpochMs = nowEpochMs) to TripEvent.Started(id) }
        else -> state.copy(endPendingSinceEpochMs = null) to null
    }

    /** A lock while the driver is still seated (speed auto-lock, inside button) is not the end of a trip. */
    fun onLock(state: TripState, locked: Boolean, nowEpochMs: Long, driverSeated: Boolean): Pair<TripState, TripEvent?> {
        val tripId = state.tripId ?: return state to null
        return when {
            !locked -> state.copy(endPendingSinceEpochMs = null) to null
            driverSeated || state.endPendingSinceEpochMs != null -> state to null
            else -> state.copy(endPendingSinceEpochMs = nowEpochMs) to TripEvent.EndPending(tripId)
        }
    }

    /** A clock that moved backwards restarts the delay, so the trip neither ends early nor hangs forever. */
    fun onTick(state: TripState, nowEpochMs: Long): Pair<TripState, TripEvent?> {
        val tripId = state.tripId ?: return state to null
        val since = state.endPendingSinceEpochMs ?: return state to null
        return when {
            nowEpochMs < since -> state.copy(endPendingSinceEpochMs = nowEpochMs) to null
            nowEpochMs - since >= endDelayMs -> TripState() to TripEvent.Ended(tripId)
            else -> state to null
        }
    }

    /** Restored state: finish an end that elapsed while the service was down, and drop trips that are too old. */
    fun onStartup(state: TripState, nowEpochMs: Long): TripState {
        val ticked = onTick(state, nowEpochMs).first
        val started = ticked.startedAtEpochMs ?: return ticked
        return if (nowEpochMs - started >= MAX_TRIP_AGE_MS) TripState() else ticked
    }

    companion object {
        const val DEFAULT_END_DELAY_MS: Long = 5 * 60_000
        const val MAX_TRIP_AGE_MS: Long = 12 * 60 * 60_000
    }
}
