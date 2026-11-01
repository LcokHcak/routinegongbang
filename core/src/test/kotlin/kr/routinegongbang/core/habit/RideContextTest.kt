package kr.routinegongbang.core.habit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RideContextTest {

    @Test
    fun `context uses the local day kind and minute of day`() {
        assertEquals(RideContext(DayKind.WEEKDAY, 8 * 60 + 5), RideContext.of(at("2026-10-26", "08:05"), SEOUL_ZONE))
        assertEquals(DayKind.WEEKEND, RideContext.of(at("2026-10-31", "08:05"), SEOUL_ZONE).dayKind)
    }

    @Test
    fun `contexts are near within the tolerance on the same day kind`() {
        val eight = RideContext(DayKind.WEEKDAY, 8 * 60)
        assertTrue(eight.isNear(RideContext(DayKind.WEEKDAY, 9 * 60), toleranceMinutes = 60))
        assertFalse(eight.isNear(RideContext(DayKind.WEEKDAY, 9 * 60 + 1), toleranceMinutes = 60))
        assertFalse(eight.isNear(RideContext(DayKind.WEEKEND, 8 * 60), toleranceMinutes = 60))
    }

    @Test
    fun `nearness wraps around midnight`() {
        assertTrue(RideContext(DayKind.WEEKDAY, 23 * 60 + 30).isNear(RideContext(DayKind.WEEKDAY, 20), toleranceMinutes = 60))
    }
}
