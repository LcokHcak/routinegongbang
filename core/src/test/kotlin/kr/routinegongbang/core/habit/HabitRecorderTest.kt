package kr.routinegongbang.core.habit

import kr.routinegongbang.core.model.SignalIds
import kr.routinegongbang.core.model.SignalValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HabitRecorderTest {

    private val recorder = HabitRecorder(habitCatalog, SEOUL_ZONE)
    private val start = at("2026-10-26", "08:00")
    private val withTrip = recorder.onTripStarted(HabitLog(), "t1", start)

    private fun change(signal: kr.routinegongbang.core.model.SignalId, value: SignalValue, minutesAfterStart: Long, tripId: String? = "t1") =
        recorder.onUserChange(withTrip, tripId, signal, value, start.plusMillis(minutesAfterStart * MINUTE))

    @Test
    fun `trip start is recorded once with its context`() {
        assertEquals(listOf(TripRecord("t1", start.toEpochMilli(), RideContext(DayKind.WEEKDAY, 480))), withTrip.trips)
        assertEquals(withTrip, recorder.onTripStarted(withTrip, "t1", start.plusMillis(MINUTE)))
    }

    @Test
    fun `comfort change by the user is recorded with its rounded value`() {
        val log = change(SignalIds.HVAC_TEMP_DRIVER, SignalValue.num(22.2), minutesAfterStart = 2)
        assertEquals(listOf(OperationRecord("t1", HabitPattern(SignalIds.HVAC_TEMP_DRIVER, SignalValue.num(22.0)), start.toEpochMilli() + 2 * MINUTE)), log.operations)
    }

    @Test
    fun `body and read-only signals are not habits`() {
        assertTrue(change(SignalIds.DOOR_LOCK, SignalValue.Bool(true), 1).operations.isEmpty())
        assertTrue(change(SignalIds.EV_SOC, SignalValue.of(50), 1).operations.isEmpty())
        assertTrue(change(SignalIds.LOW_BEAM, SignalValue.Bool(true), 1).operations.isEmpty())
    }

    @Test
    fun `only the first ten minutes of a trip count`() {
        assertEquals(1, change(SignalIds.STEERING_WHEEL_HEAT, SignalValue.of(1), 10).operations.size)
        assertTrue(change(SignalIds.STEERING_WHEEL_HEAT, SignalValue.of(1), 11).operations.isEmpty())
    }

    @Test
    fun `changes outside a known trip or with a wrong type are ignored`() {
        assertTrue(change(SignalIds.STEERING_WHEEL_HEAT, SignalValue.of(1), 1, tripId = null).operations.isEmpty())
        assertTrue(change(SignalIds.STEERING_WHEEL_HEAT, SignalValue.of(1), 1, tripId = "unknown").operations.isEmpty())
        assertTrue(change(SignalIds.STEERING_WHEEL_HEAT, SignalValue.Bool(true), 1).operations.isEmpty())
    }

    @Test
    fun `records older than thirty days are deleted`() {
        val log = change(SignalIds.STEERING_WHEEL_HEAT, SignalValue.of(1), 1)
            .withFeedback(HabitPattern(SignalIds.STEERING_WHEEL_HEAT, SignalValue.of(1)), RideContext(DayKind.WEEKDAY, 480), FeedbackKind.REJECTED, start.toEpochMilli())
        val later = log.pruned(nowEpochMs = start.toEpochMilli() + 30 * DAY + MINUTE, retentionMs = 30 * DAY)
        assertEquals(HabitLog(), later)
        assertEquals(log, log.pruned(nowEpochMs = start.toEpochMilli() + DAY, retentionMs = 30 * DAY))
    }

    @Test
    fun `log is capped so it cannot grow without bound`() {
        val many = (1..HabitLog.MAX_TRIPS + 5).fold(HabitLog()) { log, i -> recorder.onTripStarted(log, "t$i", start.plusMillis(i * MINUTE)) }
        assertEquals(HabitLog.MAX_TRIPS, many.trips.size)
        assertEquals("t${HabitLog.MAX_TRIPS + 5}", many.trips.last().tripId)
    }

    @Test
    fun `log round trips through json and rejects garbage`() {
        val log = change(SignalIds.STEERING_WHEEL_HEAT, SignalValue.of(1), 1)
            .withFeedback(HabitPattern(SignalIds.STEERING_WHEEL_HEAT, SignalValue.of(1)), RideContext(DayKind.WEEKDAY, 480), FeedbackKind.SHOWN, 5)
        assertEquals(log, HabitLogJson.decode(HabitLogJson.encode(log)).getOrThrow())
        assertTrue(HabitLogJson.decode("[").isFailure)
    }
}
