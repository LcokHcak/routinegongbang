package kr.routinegongbang.core.engine

import kotlinx.serialization.Serializable
import kr.routinegongbang.core.model.Limits
import java.time.Instant
import java.time.ZoneId

/** Persisted per routine so a service restart cannot re-run an action. */
@Serializable
data class RoutineCounters(
    val lastFiredAtEpochMs: Long? = null,
    val firedDayEpoch: Long? = null,
    val firedToday: Int = 0,
    val lastTripId: String? = null,
    /** Runs in a row whose every write failed; at [SUSPEND_AFTER_FAILURES] the routine pauses until edited. */
    val failStreak: Int = 0,
    /** Which definition failed; an edited routine (also edited while the service was down) starts afresh. */
    val failedDefinition: Int? = null,
)

/** [STILL_WAITING]: the routine has not finished an earlier run that is waiting. */
enum class LimitReason { COOLDOWN, DAILY_LIMIT, TRIP_LIMIT, SUSPENDED, STILL_WAITING }

const val SUSPEND_AFTER_FAILURES: Int = 3

sealed interface LimitDecision {
    data object Allowed : LimitDecision
    data class Blocked(val reason: LimitReason) : LimitDecision
}

/**
 * Applies cooldown, daily limit and once-per-trip using wall-clock time.
 * Days follow [zone], fixed for the guard's lifetime (vehicle time zone rarely changes; recreate the guard if it does).
 * A clock that moved backwards never blocks: the next [record] resets the reference point.
 */
class LimitGuard(private val zone: ZoneId) {

    fun check(limits: Limits, counters: RoutineCounters, now: Instant, tripId: String?): LimitDecision {
        if (limits.oncePerTrip && tripId != null && counters.lastTripId == tripId) return LimitDecision.Blocked(LimitReason.TRIP_LIMIT)
        if (inCooldown(limits, counters, now)) return LimitDecision.Blocked(LimitReason.COOLDOWN)
        val max = limits.maxPerDay
        if (max != null && (max <= 0 || (counters.firedDayEpoch == dayOf(now) && counters.firedToday >= max))) {
            return LimitDecision.Blocked(LimitReason.DAILY_LIMIT)
        }
        return LimitDecision.Allowed
    }

    /** Call only when the routine actually ran or notified; skipped runs must not count. */
    fun record(counters: RoutineCounters, now: Instant, tripId: String?): RoutineCounters {
        val today = dayOf(now)
        val firedToday = if (counters.firedDayEpoch == today) counters.firedToday + 1 else 1
        return counters.copy(lastFiredAtEpochMs = now.toEpochMilli(), firedDayEpoch = today, firedToday = firedToday, lastTripId = tripId)
    }

    private fun inCooldown(limits: Limits, counters: RoutineCounters, now: Instant): Boolean {
        val cooldownMs = limits.cooldownMinutes * MILLIS_PER_MINUTE
        val last = counters.lastFiredAtEpochMs ?: return false
        val elapsed = now.toEpochMilli() - last
        return cooldownMs > 0 && elapsed in 0 until cooldownMs
    }

    private fun dayOf(instant: Instant): Long = instant.atZone(zone).toLocalDate().toEpochDay()

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000L
    }
}
