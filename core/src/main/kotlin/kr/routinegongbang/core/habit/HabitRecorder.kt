package kr.routinegongbang.core.habit

import kr.routinegongbang.core.model.SafetyClass
import kr.routinegongbang.core.model.SignalCatalog
import kr.routinegongbang.core.model.SignalId
import kr.routinegongbang.core.model.SignalValue
import java.time.Instant
import java.time.ZoneId

/** Tunables for habit recording and detection. */
data class HabitConfig(
    val windowAfterStartMs: Long = 10 * 60_000,
    val contextToleranceMinutes: Int = 60,
    val lookbackTrips: Int = 5,
    val minOccurrences: Int = 3,
    val rejectionQuietMs: Long = 30 * DAY_MS,
    val shownQuietMs: Long = DAY_MS,
    val retentionMs: Long = 30 * DAY_MS,
) {
    init {
        require(minOccurrences in 1..lookbackTrips) { "minOccurrences must be within 1..lookbackTrips" }
        require(windowAfterStartMs > 0 && contextToleranceMinutes in 0..719) { "Invalid habit window" }
    }

    companion object {
        const val DAY_MS: Long = 24 * 60 * 60_000
    }
}

/**
 * Turns engine hooks into habit records. Only user changes (the engine never reports the app's own writes)
 * of writable COMFORT signals, in the first minutes of a trip, rounded to the signal's step.
 */
class HabitRecorder(private val catalog: SignalCatalog, private val zone: ZoneId, private val config: HabitConfig = HabitConfig()) {

    /** Also prunes old records, so retention does not depend on the app remembering to do it. */
    fun onTripStarted(log: HabitLog, tripId: String, at: Instant, cabinCelsius: Double? = null): HabitLog {
        if (log.trips.any { it.tripId == tripId }) return log
        return log.pruned(at.toEpochMilli(), config.retentionMs).withTrip(TripRecord(tripId, at.toEpochMilli(), RideContext.of(at, zone, cabinCelsius)))
    }

    fun onUserChange(log: HabitLog, tripId: String?, signal: SignalId, value: SignalValue, at: Instant): HabitLog {
        val trip = log.trips.lastOrNull { it.tripId == tripId } ?: return log
        val spec = catalog[signal] ?: return log
        if (spec.safetyClass != SafetyClass.COMFORT || !spec.writable || value.type != spec.valueType) return log
        val sinceStart = at.toEpochMilli() - trip.startedAtEpochMs
        if (sinceStart !in 0..config.windowAfterStartMs) return log
        return log.withOperation(OperationRecord(trip.tripId, HabitPattern(signal, value.bucket(spec.rounding)), at.toEpochMilli()))
    }
}
