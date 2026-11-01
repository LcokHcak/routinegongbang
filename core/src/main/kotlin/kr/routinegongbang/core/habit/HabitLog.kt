package kr.routinegongbang.core.habit

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kr.routinegongbang.core.model.SignalId
import kr.routinegongbang.core.model.SignalValue
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

enum class DayKind { WEEKDAY, WEEKEND }

/**
 * The cabin temperature at a ride start, coarsely: a habit of cold mornings is not one of warm
 * mornings. Mild needs no condition of its own.
 */
enum class TempBand {
    COLD, MILD, HOT;

    /** A temperature inside the band, at its edge where it has one, so the band's own condition holds for it. */
    val typicalCelsius: Double
        get() = when (this) {
            COLD -> COLD_MAX_C
            MILD -> (COLD_MAX_C + HOT_MIN_C) / 2
            HOT -> HOT_MIN_C
        }

    companion object {
        const val COLD_MAX_C: Double = 10.0
        const val HOT_MIN_C: Double = 26.0

        fun of(celsius: Double?): TempBand? = when {
            celsius == null -> null
            celsius <= COLD_MAX_C -> COLD
            celsius >= HOT_MIN_C -> HOT
            else -> MILD
        }
    }
}

/**
 * Situation of a ride start: weekday or weekend, local minute of day, and the cabin temperature band
 * when it was known (records saved by older versions have none).
 */
@Serializable
data class RideContext(val dayKind: DayKind, val minuteOfDay: Int, val cabinBand: TempBand? = null) {

    /** Same day kind, start times within [toleranceMinutes] (wrapping past midnight), and the same band if both are known. */
    fun isNear(other: RideContext, toleranceMinutes: Int): Boolean {
        if (dayKind != other.dayKind) return false
        if (cabinBand != null && other.cabinBand != null && cabinBand != other.cabinBand) return false
        val diff = abs(minuteOfDay - other.minuteOfDay)
        return minOf(diff, MINUTES_PER_DAY - diff) <= toleranceMinutes
    }

    companion object {
        const val MINUTES_PER_DAY: Int = 24 * 60

        fun of(at: Instant, zone: ZoneId, cabinCelsius: Double? = null): RideContext {
            val local = at.atZone(zone)
            val kind = if (local.dayOfWeek == DayOfWeek.SATURDAY || local.dayOfWeek == DayOfWeek.SUNDAY) DayKind.WEEKEND else DayKind.WEEKDAY
            return RideContext(kind, local.hour * 60 + local.minute, TempBand.of(cabinCelsius))
        }
    }
}

/** "Set this signal to this value"; [value] is already bucketed by the signal's rounding. */
@Serializable
data class HabitPattern(val signal: SignalId, val value: SignalValue)

@Serializable
data class TripRecord(val tripId: String, val startedAtEpochMs: Long, val context: RideContext)

@Serializable
data class OperationRecord(val tripId: String, val pattern: HabitPattern, val atEpochMs: Long)

enum class FeedbackKind { SHOWN, REJECTED, ACCEPTED }

/** The latest answer for a pattern in one context; [shownCount] counts unanswered showings in a row. */
@Serializable
data class Feedback(
    val pattern: HabitPattern,
    val context: RideContext,
    val kind: FeedbackKind,
    val atEpochMs: Long,
    val shownCount: Int = 0,
)

/**
 * Local-only habit history. Immutable and capped; the app stores it with [HabitLogJson]
 * and calls [pruned] so nothing older than the retention period is kept.
 */
@Serializable
data class HabitLog(
    val schemaVersion: Int = SCHEMA_VERSION,
    val trips: List<TripRecord> = emptyList(),
    val operations: List<OperationRecord> = emptyList(),
    val feedback: List<Feedback> = emptyList(),
) {
    /** Evicting the oldest trip also drops its operations. */
    fun withTrip(trip: TripRecord): HabitLog {
        val kept = (trips + trip).takeLast(MAX_TRIPS)
        val ids = kept.map { it.tripId }.toSet()
        return copy(trips = kept, operations = operations.filter { it.tripId in ids })
    }

    fun withOperation(operation: OperationRecord): HabitLog = copy(operations = (operations + operation).takeLast(MAX_OPERATIONS))

    /**
     * Keeps only the latest answer per pattern and context (contexts within [toleranceMinutes] are the same).
     * Showing again without an answer increments the unanswered count.
     */
    fun withFeedback(
        pattern: HabitPattern,
        context: RideContext,
        kind: FeedbackKind,
        atEpochMs: Long,
        toleranceMinutes: Int = DEFAULT_TOLERANCE_MINUTES,
    ): HabitLog {
        val same = { it: Feedback -> it.pattern == pattern && it.context.isNear(context, toleranceMinutes) }
        val previous = feedback.lastOrNull(same)
        val shown = if (kind == FeedbackKind.SHOWN) (previous?.takeIf { it.kind == FeedbackKind.SHOWN }?.shownCount ?: 0) + 1 else 0
        return copy(feedback = feedback.filterNot(same) + Feedback(pattern, context, kind, atEpochMs, shown))
    }

    /** Deletes everything older than [retentionMs], answers included (personal data). */
    fun pruned(nowEpochMs: Long, retentionMs: Long): HabitLog {
        val cutoff = nowEpochMs - retentionMs
        val keptTrips = trips.filter { it.startedAtEpochMs >= cutoff }
        val tripIds = keptTrips.map { it.tripId }.toSet()
        return copy(
            trips = keptTrips,
            operations = operations.filter { it.tripId in tripIds && it.atEpochMs >= cutoff },
            feedback = feedback.filter { it.atEpochMs >= cutoff },
        )
    }

    companion object {
        const val SCHEMA_VERSION: Int = 1
        const val DEFAULT_TOLERANCE_MINUTES: Int = 60
        const val MAX_TRIPS: Int = 500
        const val MAX_OPERATIONS: Int = 5_000
    }
}

/**
 * Habit data is disposable: when [decode] fails (corrupt file, newer schema), the app starts a fresh log.
 */
object HabitLogJson {
    const val MAX_INPUT_LENGTH: Int = 2 * 1024 * 1024

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(log: HabitLog): String = json.encodeToString(HabitLog.serializer(), log)

    fun decode(text: String): Result<HabitLog> {
        if (text.length > MAX_INPUT_LENGTH) return Result.failure(IllegalArgumentException("Habit log too large: ${text.length}"))
        return try {
            Result.success(json.decodeFromString(HabitLog.serializer(), text))
        } catch (e: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException.
            Result.failure(e)
        }
    }
}
