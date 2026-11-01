package kr.routinegongbang.core.engine

import kr.routinegongbang.core.model.Trigger
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimeOfDayTest {

    private val seoul = ZoneId.of("Asia/Seoul")
    private val sixPm = Trigger.TimeOfDay(hour = 18, minute = 0)
    private fun kst(time: String, date: String = "2026-10-27"): Instant = Instant.parse("${date}T$time+09:00")

    @Test
    fun `fires when the tick interval crosses the time`() {
        assertTrue(sixPm.crossed(since = kst("17:59:30"), now = kst("18:00:10"), zone = seoul))
        assertTrue(sixPm.crossed(since = kst("17:59:59"), now = kst("18:00:00"), zone = seoul))
    }

    @Test
    fun `does not fire twice for the same time`() {
        assertFalse(sixPm.crossed(since = kst("18:00:00"), now = kst("18:01:00"), zone = seoul))
    }

    @Test
    fun `does not fire before the time`() {
        assertFalse(sixPm.crossed(since = kst("17:00:00"), now = kst("17:59:59"), zone = seoul))
    }

    @Test
    fun `fires across midnight`() {
        val midnight = Trigger.TimeOfDay(hour = 0, minute = 0)
        assertTrue(midnight.crossed(since = kst("23:59:30"), now = kst("00:00:30", "2026-10-28"), zone = seoul))
    }

    @Test
    fun `a forward clock jump does not fire a time that is long past`() {
        assertFalse(sixPm.crossed(since = kst("12:00:00"), now = kst("12:00:00", "2026-10-30"), zone = seoul))
        // Head unit booted with an unsynced clock, then GPS sync moved it to 15:00.
        val morning = Trigger.TimeOfDay(hour = 7, minute = 30)
        assertFalse(morning.crossed(since = Instant.parse("2000-01-01T00:00:00Z"), now = kst("15:00:00"), zone = seoul))
    }

    @Test
    fun `a late tick within the allowed lateness still fires`() {
        assertTrue(sixPm.crossed(since = kst("17:59:00"), now = kst("18:02:00"), zone = seoul))
        assertFalse(sixPm.crossed(since = kst("17:59:00"), now = kst("18:20:00"), zone = seoul))
    }

    @Test
    fun `a forward jump that lands just after the time fires once (debug fast-forward)`() {
        assertTrue(sixPm.crossed(since = kst("12:00:00"), now = kst("18:00:30", "2026-10-30"), zone = seoul))
    }

    @Test
    fun `clock moving backwards never fires`() {
        assertFalse(sixPm.crossed(since = kst("18:30:00"), now = kst("17:30:00"), zone = seoul))
    }
}
