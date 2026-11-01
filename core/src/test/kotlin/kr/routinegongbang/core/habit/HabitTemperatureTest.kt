package kr.routinegongbang.core.habit

import kr.routinegongbang.core.model.CompareOp
import kr.routinegongbang.core.model.Condition
import kr.routinegongbang.core.model.SignalIds
import kr.routinegongbang.core.model.SignalValue
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A habit learned on cold mornings must not become "weekdays 06:50–08:51 → steering heat", which would heat the wheel
 * on a warm April morning too, so the cabin temperature at the ride start is part of the context.
 */
class HabitTemperatureTest {

    private val recorder = HabitRecorder(habitCatalog, SEOUL_ZONE)
    private val detector = HabitDetector(habitCatalog, SEOUL_ZONE)
    private val heat = SignalIds.STEERING_WHEEL_HEAT

    private fun log(vararg rides: Pair<Instant, Double?>): HabitLog = rides.withIndex().fold(HabitLog()) { acc, (i, ride) ->
        val id = "t${i + 1}"
        val started = recorder.onTripStarted(acc, id, ride.first, cabinCelsius = ride.second)
        recorder.onUserChange(started, id, heat, SignalValue.of(1), ride.first.plusMillis(3 * MINUTE))
    }

    private fun suggest(log: HabitLog) = detector.suggest(log, log.trips.last().tripId, emptyList(), at("2026-10-30", "09:00").toEpochMilli())

    @Test
    fun `the cabin temperature at the start sorts rides into cold, mild and hot`() {
        assertEquals(TempBand.COLD, TempBand.of(3.0))
        assertEquals(TempBand.MILD, TempBand.of(18.0))
        assertEquals(TempBand.HOT, TempBand.of(30.0))
        assertNull(TempBand.of(null))
    }

    @Test
    fun `rides in different bands are not the same situation, an unknown band does not split them`() {
        val cold = RideContext(DayKind.WEEKDAY, 8 * 60, TempBand.COLD)
        assertTrue(!cold.isNear(cold.copy(cabinBand = TempBand.MILD), 60))
        assertTrue(cold.isNear(cold.copy(cabinBand = null), 60))
    }

    @Test
    fun `three cold mornings suggest heating only when the cabin is cold`() {
        val log = log(at("2026-10-26", "08:00") to 2.0, at("2026-10-27", "08:10") to 4.0, at("2026-10-28", "07:50") to 1.0)
        val condition = suggest(log).single().routine.routine.condition as Condition.And
        assertTrue(Condition.Compare(SignalIds.CABIN_TEMPERATURE, CompareOp.LE, SignalValue.Num(TempBand.COLD_MAX_C)) in condition.conditions)
    }

    @Test
    fun `a habit of mild mornings gets no temperature condition`() {
        val log = log(at("2026-10-26", "08:00") to 18.0, at("2026-10-27", "08:10") to 20.0, at("2026-10-28", "07:50") to 17.0)
        val condition = suggest(log).single().routine.routine.condition as Condition.And
        assertTrue(condition.conditions.none { it is Condition.Compare })
    }

    @Test
    fun `cold mornings do not count towards a warm morning`() {
        val log = log(
            at("2026-10-26", "08:00") to 2.0, at("2026-10-27", "08:00") to 3.0, at("2026-10-28", "08:00") to 1.0,
            at("2026-10-29", "08:00") to 20.0,
        )
        assertTrue(suggest(log).isEmpty(), "the warm ride has no similar rides of its own yet")
    }

    @Test
    fun `a ride whose band is unknown does not pool cold, mild and hot rides into a habit without a band`() {
        // If unknown matched every band, such a ride would build the band-free routine this context exists to prevent.
        val log = log(
            at("2026-10-26", "08:00") to 2.0, at("2026-10-27", "08:00") to 20.0, at("2026-10-28", "08:00") to 30.0,
            at("2026-10-29", "08:00") to null,
        )
        assertTrue(suggest(log).isEmpty())
    }

    @Test
    fun `an adopted cold-morning routine keeps covering cold mornings, even switched off, after the quiet period`() {
        // If its cabin condition were evaluated without values, it would never count as covering, and the same
        // suggestion would come back once the 30-day quiet period after the answer was over.
        val log = log(at("2026-10-26", "08:00") to 2.0, at("2026-10-27", "08:10") to 4.0, at("2026-10-28", "07:50") to 1.0)
        val adopted = suggest(log).single().routine.routine.copy(enabled = false)
        val later = at("2026-12-30", "09:00").toEpochMilli()
        assertTrue(detector.suggest(log, log.trips.last().tripId, listOf(adopted), later).isEmpty())
    }

    @Test
    fun `a cold-morning routine does not cover a warm morning's habit`() {
        val cold = log(at("2026-10-26", "08:00") to 2.0, at("2026-10-27", "08:10") to 4.0, at("2026-10-28", "07:50") to 1.0)
        val adopted = suggest(cold).single().routine.routine
        val warm = log(at("2026-10-26", "08:00") to 20.0, at("2026-10-27", "08:10") to 19.0, at("2026-10-28", "07:50") to 18.0)
        assertEquals(1, detector.suggest(warm, warm.trips.last().tripId, listOf(adopted), at("2026-10-30", "09:00").toEpochMilli()).size)
    }

    @Test
    fun `the habit book takes the cabin temperature from the values the engine knows at the start`() {
        val book = HabitBook(recorder, detector, HabitLog())
        book.onTripStarted("t1", at("2026-10-26", "08:00"), mapOf(SignalIds.CABIN_TEMPERATURE to SignalValue.Num(2.5)))
        assertEquals(TempBand.COLD, book.log.trips.single().context.cabinBand)
    }
}
