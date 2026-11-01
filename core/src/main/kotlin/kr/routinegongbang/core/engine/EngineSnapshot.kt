package kr.routinegongbang.core.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kr.routinegongbang.core.model.Action
import java.time.Instant

/** A notification waiting until the car is parked: a Remind action, or a body action skipped while driving. */
@Serializable
data class PendingReminder(
    val routineId: String,
    val textRes: String,
    val createdAtEpochMs: Long,
    val reason: Reason? = null,
    val action: Action? = null,
)

/**
 * The rest of a run waiting behind a wait. [segment] is the next segment to run; [definition] the
 * routine as it was when it started, so an edit cancels it. Saved so a restart does not strand a step down.
 * [writeAttempts] and [writeFailures] carry the failure streak across the segments, counted once when the run ends.
 */
@Serializable
data class PendingContinuation(
    val routineId: String,
    val definition: Int,
    val runId: Long,
    val segment: Int,
    /** The wait in front of [segment], and how many actions the routine had: enough to close the run if it is gone. */
    val waitIndex: Int,
    val actionCount: Int,
    val dueEpochMs: Long,
    val tripId: String?,
    val writeAttempts: Int = 0,
    val writeFailures: Int = 0,
)

/** Everything the engine must keep across a service restart. */
@Serializable
data class EngineSnapshot(
    val trip: TripState = TripState(),
    val counters: Map<String, RoutineCounters> = emptyMap(),
    val reminders: List<PendingReminder> = emptyList(),
    val continuations: List<PendingContinuation> = emptyList(),
) {
    /** Keeps the newest [MAX_REMINDERS] that are younger than [REMINDER_TTL_MS]. */
    fun withReminders(reminders: List<PendingReminder>, nowEpochMs: Long): EngineSnapshot =
        copy(reminders = reminders.filter { nowEpochMs - it.createdAtEpochMs < REMINDER_TTL_MS }.takeLast(MAX_REMINDERS))

    companion object {
        const val MAX_REMINDERS: Int = 20
        const val REMINDER_TTL_MS: Long = 24 * 60 * 60_000
    }
}

/** Storage port; the app implements it with DataStore using [EngineSnapshotJson]. */
interface EngineStore {
    suspend fun load(): EngineSnapshot
    suspend fun save(snapshot: EngineSnapshot)
}

class InMemoryEngineStore(initial: EngineSnapshot? = null) : EngineStore {
    var saved: EngineSnapshot? = initial
        private set

    override suspend fun load(): EngineSnapshot = saved ?: EngineSnapshot()

    override suspend fun save(snapshot: EngineSnapshot) {
        saved = snapshot
    }
}

object EngineSnapshotJson {
    const val MAX_INPUT_LENGTH: Int = 256 * 1024

    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }

    fun encode(snapshot: EngineSnapshot): String = json.encodeToString(EngineSnapshot.serializer(), snapshot)

    fun decode(text: String): Result<EngineSnapshot> {
        if (text.length > MAX_INPUT_LENGTH) return Result.failure(IllegalArgumentException("Snapshot too large: ${text.length}"))
        return try {
            Result.success(json.decodeFromString(EngineSnapshot.serializer(), text))
        } catch (e: SerializationException) {
            Result.failure(e)
        } catch (e: IllegalArgumentException) {
            Result.failure(e)
        }
    }
}

/** Wall clock for calendars and persisted times; monotonic clock for short deadlines. */
interface EngineClock {
    fun now(): Instant
    fun monotonicMs(): Long

    companion object {
        val SYSTEM: EngineClock = object : EngineClock {
            override fun now(): Instant = Instant.now()
            override fun monotonicMs(): Long = System.nanoTime() / 1_000_000
        }
    }
}
