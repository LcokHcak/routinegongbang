package kr.routinegongbang.core.engine

import kr.routinegongbang.core.model.Limits
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class LimitGuardTest {

    private val seoul = ZoneId.of("Asia/Seoul")
    private val guard = LimitGuard(seoul)
    private val t0 = Instant.parse("2026-10-27T00:00:00Z") // 09:00 KST
    private fun at(minutes: Long) = t0.plusSeconds(minutes * 60)

    @Test
    fun `first fire is always allowed`() {
        assertEquals(LimitDecision.Allowed, guard.check(Limits(cooldownMinutes = 30, maxPerDay = 1, oncePerTrip = true), RoutineCounters(), t0, "trip-1"))
    }

    @Test
    fun `cooldown blocks until it has fully elapsed`() {
        val limits = Limits(cooldownMinutes = 30)
        val fired = guard.record(RoutineCounters(), t0, "trip-1")
        assertEquals(LimitDecision.Blocked(LimitReason.COOLDOWN), guard.check(limits, fired, at(29), "trip-1"))
        assertEquals(LimitDecision.Allowed, guard.check(limits, fired, at(30), "trip-1"))
    }

    @Test
    fun `zero cooldown never blocks even when the clock moved backwards`() {
        val fired = guard.record(RoutineCounters(), t0, "trip-1")
        assertEquals(LimitDecision.Allowed, guard.check(Limits(), fired, t0.minusSeconds(60), "trip-1"))
    }

    @Test
    fun `clock moving backwards does not lock the routine in cooldown`() {
        val fired = guard.record(RoutineCounters(), t0, "trip-1")
        assertEquals(LimitDecision.Allowed, guard.check(Limits(cooldownMinutes = 30), fired, t0.minusSeconds(3_600), "trip-1"))
    }

    @Test
    fun `daily limit counts fires within the same local day`() {
        val twice = guard.record(guard.record(RoutineCounters(), t0, "a"), at(10), "b")
        assertEquals(LimitDecision.Blocked(LimitReason.DAILY_LIMIT), guard.check(Limits(maxPerDay = 2), twice, at(20), "c"))
    }

    @Test
    fun `non positive daily limit blocks as a defense in depth`() {
        assertEquals(LimitDecision.Blocked(LimitReason.DAILY_LIMIT), guard.check(Limits(maxPerDay = 0), RoutineCounters(), t0, "a"))
    }

    @Test
    fun `daily counter resets after local midnight`() {
        val fired = guard.record(RoutineCounters(), Instant.parse("2026-10-27T14:50:00Z"), "a") // 23:50 KST
        assertEquals(LimitDecision.Allowed, guard.check(Limits(maxPerDay = 1), fired, Instant.parse("2026-10-27T15:10:00Z"), "b"))
    }

    @Test
    fun `daily counter uses local days across a DST change`() {
        val ny = LimitGuard(ZoneId.of("America/New_York"))
        val beforeChange = Instant.parse("2026-03-08T04:30:00Z") // 23:30 EST on Mar 7
        val afterMidnight = Instant.parse("2026-03-08T06:30:00Z") // 01:30 EST on Mar 8
        val fired = ny.record(RoutineCounters(), beforeChange, "a")
        assertEquals(LimitDecision.Allowed, ny.check(Limits(maxPerDay = 1), fired, afterMidnight, "b"))
    }

    @Test
    fun `once per trip blocks a second fire in the same trip only`() {
        val fired = guard.record(RoutineCounters(), t0, "trip-1")
        assertEquals(LimitDecision.Blocked(LimitReason.TRIP_LIMIT), guard.check(Limits(oncePerTrip = true), fired, at(60), "trip-1"))
        assertEquals(LimitDecision.Allowed, guard.check(Limits(oncePerTrip = true), fired, at(60), "trip-2"))
    }

    @Test
    fun `without a trip the once per trip limit does not apply`() {
        val fired = guard.record(RoutineCounters(), t0, tripId = null)
        assertEquals(LimitDecision.Allowed, guard.check(Limits(oncePerTrip = true), fired, at(60), tripId = null))
    }

    @Test
    fun `no limits means always allowed`() {
        val fired = guard.record(RoutineCounters(), t0, "trip-1")
        assertEquals(LimitDecision.Allowed, guard.check(Limits(), fired, t0, "trip-1"))
    }

    @Test
    fun `record returns new counters without mutating the input`() {
        val empty = RoutineCounters()
        guard.record(empty, t0, "trip-1")
        assertEquals(RoutineCounters(), empty)
    }
}
