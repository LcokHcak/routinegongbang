package kr.routinegongbang.core.engine

import kr.routinegongbang.core.model.CompareOp
import kr.routinegongbang.core.model.Condition
import kr.routinegongbang.core.model.SignalIds
import kr.routinegongbang.core.model.SignalValue
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConditionEvaluatorTest {

    private val seoul = ZoneId.of("Asia/Seoul")
    private val evaluator = ConditionEvaluator(testCatalog, seoul)

    /** 2026-10-27 is a Tuesday. */
    private fun kst(time: String, date: String = "2026-10-27"): Instant = Instant.parse("${date}T$time:00+09:00")

    private val tuesdayNoon = kst("12:00")
    private val soc = SignalIds.EV_SOC
    private val charging = SignalIds.EV_CHARGING
    private val values = mapOf(soc to SignalValue.of(80), charging to SignalValue.Bool(true))

    private fun eval(condition: Condition?, now: Instant = tuesdayNoon, current: Map<kr.routinegongbang.core.model.SignalId, SignalValue> = values) =
        evaluator.evaluate(condition, current, now)

    @Test
    fun `no condition always matches`() {
        assertEquals(ConditionResult(met = true, missing = emptySet()), eval(null))
    }

    @Test
    fun `numeric comparisons`() {
        assertTrue(eval(Condition.Compare(soc, CompareOp.GE, SignalValue.of(80))).met)
        assertFalse(eval(Condition.Compare(soc, CompareOp.GT, SignalValue.of(80))).met)
        assertTrue(eval(Condition.Compare(soc, CompareOp.LT, SignalValue.of(81))).met)
        assertTrue(eval(Condition.Compare(soc, CompareOp.LE, SignalValue.of(80))).met)
        assertTrue(eval(Condition.Compare(soc, CompareOp.NE, SignalValue.of(79))).met)
    }

    @Test
    fun `equality uses the signal rounding`() {
        val temp = SignalIds.HVAC_TEMP_DRIVER
        val current = mapOf(temp to SignalValue.num(22.2))
        assertTrue(eval(Condition.Compare(temp, CompareOp.EQ, SignalValue.num(22.0)), current = current).met)
        assertFalse(eval(Condition.Compare(temp, CompareOp.EQ, SignalValue.num(22.5)), current = current).met)
    }

    @Test
    fun `boolean equality`() {
        assertTrue(eval(Condition.Compare(charging, CompareOp.EQ, SignalValue.Bool(true))).met)
        assertFalse(eval(Condition.Compare(charging, CompareOp.NE, SignalValue.Bool(true))).met)
    }

    @Test
    fun `missing value never matches and is reported`() {
        val windowOpen = Condition.Compare(SignalIds.WINDOW_POSITION_DRIVER, CompareOp.GT, SignalValue.of(0))
        assertEquals(ConditionResult(met = false, missing = setOf(SignalIds.WINDOW_POSITION_DRIVER)), eval(windowOpen))
    }

    @Test
    fun `negating a missing value still does not match`() {
        val windowOpen = Condition.Compare(SignalIds.WINDOW_POSITION_DRIVER, CompareOp.GT, SignalValue.of(0))
        assertFalse(eval(Condition.Not(windowOpen)).met)
    }

    @Test
    fun `or with one true branch matches even if another value is missing`() {
        val missing = Condition.Compare(SignalIds.WINDOW_POSITION_DRIVER, CompareOp.GT, SignalValue.of(0))
        val result = eval(Condition.Or(listOf(missing, Condition.Compare(charging, CompareOp.EQ, SignalValue.Bool(true)))))
        assertTrue(result.met)
    }

    @Test
    fun `and with a false branch does not match`() {
        val result = eval(Condition.And(listOf(Condition.Compare(soc, CompareOp.LT, SignalValue.of(50)), Condition.Compare(charging, CompareOp.EQ, SignalValue.Bool(true)))))
        assertFalse(result.met)
    }

    @Test
    fun `type mismatch at runtime does not match`() {
        val current = mapOf(soc to SignalValue.Text("oops"))
        assertFalse(eval(Condition.Compare(soc, CompareOp.GT, SignalValue.of(1)), current = current).met)
    }

    @Test
    fun `time window uses local time with an exclusive end`() {
        val evening = Condition.TimeWindow(startMinute = 18 * 60, endMinute = 20 * 60)
        assertTrue(eval(evening, kst("18:00")).met)
        assertTrue(eval(evening, kst("19:59")).met)
        assertFalse(eval(evening, kst("20:00")).met)
        assertFalse(eval(evening, kst("17:59")).met)
    }

    @Test
    fun `time window wraps past midnight`() {
        val night = Condition.TimeWindow(startMinute = 22 * 60, endMinute = 6 * 60)
        assertTrue(eval(night, kst("23:30")).met)
        assertTrue(eval(night, kst("05:00")).met)
        assertFalse(eval(night, kst("12:00")).met)
    }

    @Test
    fun `day type follows the local calendar`() {
        val weekdays = Condition.DayType(weekdays = true, weekends = false)
        assertTrue(eval(weekdays, kst("12:00", "2026-10-30")).met) // Friday
        assertFalse(eval(weekdays, kst("12:00", "2026-10-31")).met) // Saturday
        // 2026-10-31 00:30 KST is still Friday in UTC; the local zone decides.
        assertFalse(eval(weekdays, kst("00:30", "2026-10-31")).met)
        assertTrue(eval(Condition.DayType(weekdays = false, weekends = true), kst("10:00", "2026-11-01")).met)
    }
}
