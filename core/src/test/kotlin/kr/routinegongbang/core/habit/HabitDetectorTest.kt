package kr.routinegongbang.core.habit

import kr.routinegongbang.core.model.Action
import kr.routinegongbang.core.model.Condition
import kr.routinegongbang.core.model.Origin
import kr.routinegongbang.core.model.Routine
import kr.routinegongbang.core.model.SignalId
import kr.routinegongbang.core.model.SignalIds
import kr.routinegongbang.core.model.SignalValue
import kr.routinegongbang.core.model.Trigger
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HabitDetectorTest {

    private val recorder = HabitRecorder(habitCatalog, SEOUL_ZONE)
    private val detector = HabitDetector(habitCatalog, SEOUL_ZONE)
    private val heat = SignalIds.STEERING_WHEEL_HEAT
    private val heatOn = HabitPattern(heat, SignalValue.of(1))
    private val morning = RideContext(DayKind.WEEKDAY, 8 * 60)

    /** One trip per entry: (start, operations as signal/value/minutes after start). */
    private data class Ride(val start: Instant, val ops: List<Triple<SignalId, SignalValue, Long>> = emptyList())

    private fun log(vararg rides: Ride): HabitLog = rides.withIndex().fold(HabitLog()) { acc, (i, ride) ->
        val id = "t${i + 1}"
        ride.ops.fold(recorder.onTripStarted(acc, id, ride.start)) { log, (signal, value, minutes) ->
            recorder.onUserChange(log, id, signal, value, ride.start.plusMillis(minutes * MINUTE))
        }
    }

    private fun heatRide(date: String, time: String = "08:00", value: Int = 1) = Ride(at(date, time), listOf(Triple(heat, SignalValue.of(value), 3L)))
    private fun plainRide(date: String, time: String = "08:00") = Ride(at(date, time))

    private fun suggest(log: HabitLog, tripId: String = log.trips.last().tripId, existing: List<Routine> = emptyList(), now: Long = at("2026-10-30", "09:00").toEpochMilli()) =
        detector.suggest(log, tripId, existing, now)

    @Test
    fun `D4 three weekday mornings with steering heat suggest a ride start routine`() {
        val log = log(heatRide("2026-10-26"), heatRide("2026-10-27", "08:20"), heatRide("2026-10-28", "07:40"))
        val suggestion = suggest(log).single()
        assertEquals(heatOn, suggestion.pattern)
        assertEquals(3, suggestion.supportingTrips)
        val routine = suggestion.routine.routine
        assertEquals(Trigger.RideStart, routine.trigger)
        assertEquals(Origin.AI_SUGGESTION, routine.origin)
        assertEquals(listOf<Action>(Action.Write(heat, SignalValue.of(1))), routine.actions)
        assertTrue(routine.limits.oncePerTrip)
        assertEquals(
            Condition.And(listOf(Condition.DayType(weekdays = true, weekends = false), Condition.TimeWindow(startMinute = 6 * 60 + 40, endMinute = 8 * 60 + 41))),
            routine.condition,
        )
    }

    @Test
    fun `two of the last five similar trips are not a habit`() {
        assertTrue(suggest(log(heatRide("2026-10-26"), plainRide("2026-10-27"), heatRide("2026-10-28"), plainRide("2026-10-29"))).isEmpty())
    }

    @Test
    fun `only the last five similar trips count`() {
        val log = log(
            heatRide("2026-10-19"), heatRide("2026-10-20"), heatRide("2026-10-21"),
            plainRide("2026-10-22"), plainRide("2026-10-23"), plainRide("2026-10-26"), plainRide("2026-10-27"),
        )
        assertTrue(suggest(log).isEmpty())
    }

    @Test
    fun `trips in another context do not count`() {
        // Saturday and an evening trip are different contexts from the weekday morning.
        val log = log(heatRide("2026-10-24"), heatRide("2026-10-26", "18:00"), heatRide("2026-10-27"), heatRide("2026-10-28"))
        assertTrue(suggest(log).isEmpty())
    }

    @Test
    fun `the final value of a signal in a trip is the operation`() {
        val knob = { date: String -> Ride(at(date, "08:00"), listOf(Triple(heat, SignalValue.of(1), 1L), Triple(heat, SignalValue.of(2), 2L))) }
        val log = log(knob("2026-10-26"), knob("2026-10-27"), knob("2026-10-28"))
        assertEquals(listOf(HabitPattern(heat, SignalValue.of(2))), suggest(log).map { it.pattern })
    }

    @Test
    fun `rejected suggestions stay quiet for thirty days`() {
        val rejectedAt = at("2026-10-28", "09:00").toEpochMilli()
        val log = log(heatRide("2026-10-26"), heatRide("2026-10-27"), heatRide("2026-10-28")).withFeedback(heatOn, morning, FeedbackKind.REJECTED, rejectedAt)
        assertTrue(suggest(log, now = rejectedAt + 29 * DAY).isEmpty())
        assertEquals(1, suggest(log, now = rejectedAt + 30 * DAY).size)
    }

    @Test
    fun `a suggestion shown without an answer is not repeated the same day`() {
        val shownAt = at("2026-10-28", "09:00").toEpochMilli()
        val log = log(heatRide("2026-10-26"), heatRide("2026-10-27"), heatRide("2026-10-28")).withFeedback(heatOn, morning, FeedbackKind.SHOWN, shownAt)
        assertTrue(suggest(log, now = shownAt + DAY - 1).isEmpty())
        assertEquals(1, suggest(log, now = shownAt + DAY).size)
    }

    @Test
    fun `accepted suggestions are not repeated`() {
        val acceptedAt = at("2026-10-28", "09:00").toEpochMilli()
        val log = log(heatRide("2026-10-26"), heatRide("2026-10-27"), heatRide("2026-10-28")).withFeedback(heatOn, morning, FeedbackKind.ACCEPTED, acceptedAt)
        assertTrue(suggest(log, now = acceptedAt + DAY).isEmpty())
    }

    @Test
    fun `an existing ride start routine that already does it suppresses the suggestion`() {
        val existing = Routine(id = "mine", name = "내 루틴", trigger = Trigger.RideStart, actions = listOf(Action.Write(heat, SignalValue.of(1))), enabled = false)
        val log = log(heatRide("2026-10-26"), heatRide("2026-10-27"), heatRide("2026-10-28"))
        assertTrue(suggest(log, existing = listOf(existing)).isEmpty())
    }

    @Test
    fun `an existing routine only covers the habit when its condition holds at that ride`() {
        val eveningOnly = Routine(
            id = "evening", name = "저녁", trigger = Trigger.RideStart,
            condition = Condition.TimeWindow(startMinute = 18 * 60, endMinute = 20 * 60),
            actions = listOf(Action.Write(heat, SignalValue.of(1))),
        )
        val mornings = log(heatRide("2026-10-26"), heatRide("2026-10-27"), heatRide("2026-10-28"))
        assertEquals(1, suggest(mornings, existing = listOf(eveningOnly)).size, "an evening routine does not cover a morning habit")
        val morningOnly = eveningOnly.copy(id = "morning", condition = Condition.TimeWindow(startMinute = 7 * 60, endMinute = 9 * 60))
        assertTrue(suggest(mornings, existing = listOf(morningOnly)).isEmpty())
    }

    @Test
    fun `unknown trip gives no suggestion`() {
        assertTrue(suggest(log(heatRide("2026-10-26")), tripId = "nope").isEmpty())
    }

    @Test
    fun `window around midnight wraps`() {
        val log = log(heatRide("2026-10-26", "23:50"), heatRide("2026-10-27", "23:40"), heatRide("2026-10-28", "00:10"))
        val condition = suggest(log).single().routine.routine.condition as Condition.And
        assertEquals(Condition.TimeWindow(startMinute = 23 * 60 + 10, endMinute = 60 + 11), condition.conditions[1])
    }
}
