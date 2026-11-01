package kr.routinegongbang.core.engine

import kr.routinegongbang.core.model.Action
import kr.routinegongbang.core.model.SignalIds
import kr.routinegongbang.core.model.SignalValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EngineSnapshotTest {

    private val snapshot = EngineSnapshot(
        trip = TripState(tripId = "trip-1", startedAtEpochMs = 10, userOverrides = setOf(SignalIds.STEERING_WHEEL_HEAT)),
        counters = mapOf("r1" to RoutineCounters(lastFiredAtEpochMs = 5, firedDayEpoch = 20_000, firedToday = 1, lastTripId = "trip-1")),
        reminders = listOf(
            PendingReminder("r2", "remind.umbrella", createdAtEpochMs = 7),
            PendingReminder(
                "r3", NotifyMessage.DOWNGRADED, createdAtEpochMs = 8, reason = Reason.BODY_WHILE_DRIVING,
                action = Action.Write(SignalIds.DOOR_LOCK, SignalValue.Bool(true)),
            ),
        ),
    )

    @Test
    fun `round trips through json`() {
        assertEquals(snapshot, EngineSnapshotJson.decode(EngineSnapshotJson.encode(snapshot)).getOrThrow())
    }

    @Test
    fun `corrupt or oversized data is a failure, never an exception`() {
        assertTrue(EngineSnapshotJson.decode("{not json").isFailure)
        assertTrue(EngineSnapshotJson.decode(" ".repeat(EngineSnapshotJson.MAX_INPUT_LENGTH + 1)).isFailure)
    }

    @Test
    fun `unknown fields from a newer version are ignored`() {
        assertEquals(EngineSnapshot(), EngineSnapshotJson.decode("""{"future":1}""").getOrThrow())
    }

    @Test
    fun `reminders are capped and expired`() {
        val many = (1..30).map { PendingReminder("r$it", "t", createdAtEpochMs = it.toLong()) }
        val kept = EngineSnapshot(reminders = many).withReminders(many, nowEpochMs = 30)
        assertEquals(EngineSnapshot.MAX_REMINDERS, kept.reminders.size)
        assertEquals("r30", kept.reminders.last().routineId)
        val later = kept.withReminders(kept.reminders, nowEpochMs = 30 + EngineSnapshot.REMINDER_TTL_MS)
        assertTrue(later.reminders.isEmpty())
    }
}
