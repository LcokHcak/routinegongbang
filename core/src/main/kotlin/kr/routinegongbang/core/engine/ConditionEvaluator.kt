package kr.routinegongbang.core.engine

import kr.routinegongbang.core.model.Condition
import kr.routinegongbang.core.model.Rounding
import kr.routinegongbang.core.model.SignalCatalog
import kr.routinegongbang.core.model.SignalId
import kr.routinegongbang.core.model.SignalValue
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/** [missing] lists signals without a known value; the debug panel shows them as the reason. */
data class ConditionResult(val met: Boolean, val missing: Set<SignalId>)

/**
 * Evaluates routine conditions with three-valued logic: a missing or mistyped value
 * is UNKNOWN, `Not(UNKNOWN)` stays UNKNOWN, and only TRUE runs the routine. So a value we cannot read
 * never makes a routine run, whatever the nesting.
 */
class ConditionEvaluator(private val catalog: SignalCatalog, private val zone: ZoneId) {

    private enum class Truth { TRUE, FALSE, UNKNOWN }

    fun evaluate(condition: Condition?, values: Map<SignalId, SignalValue>, now: Instant): ConditionResult {
        if (condition == null) return ConditionResult(met = true, missing = emptySet())
        val missing = mutableSetOf<SignalId>()
        val truth = eval(condition, values, now, missing)
        return ConditionResult(met = truth == Truth.TRUE, missing = missing)
    }

    private fun eval(condition: Condition, values: Map<SignalId, SignalValue>, now: Instant, missing: MutableSet<SignalId>): Truth =
        when (condition) {
            is Condition.Compare -> compare(condition, values[condition.signal], missing)
            is Condition.TimeWindow -> truth(inWindow(condition, now))
            is Condition.DayType -> truth(isDayType(condition, now))
            is Condition.And -> condition.conditions.map { eval(it, values, now, missing) }.let { all ->
                when {
                    Truth.FALSE in all -> Truth.FALSE
                    Truth.UNKNOWN in all -> Truth.UNKNOWN
                    else -> Truth.TRUE
                }
            }
            is Condition.Or -> condition.conditions.map { eval(it, values, now, missing) }.let { all ->
                when {
                    Truth.TRUE in all -> Truth.TRUE
                    Truth.UNKNOWN in all -> Truth.UNKNOWN
                    else -> Truth.FALSE
                }
            }
            is Condition.Not -> when (eval(condition.condition, values, now, missing)) {
                Truth.TRUE -> Truth.FALSE
                Truth.FALSE -> Truth.TRUE
                Truth.UNKNOWN -> Truth.UNKNOWN
            }
        }

    private fun compare(compare: Condition.Compare, current: SignalValue?, missing: MutableSet<SignalId>): Truth {
        val rounding = catalog[compare.signal]?.rounding ?: Rounding.None
        val result = current?.satisfies(compare.op, compare.value, rounding)
        if (result == null) missing += compare.signal
        return when (result) {
            true -> Truth.TRUE
            false -> Truth.FALSE
            null -> Truth.UNKNOWN
        }
    }

    /** End is exclusive; an end before the start wraps past midnight. An empty window never matches. */
    private fun inWindow(window: Condition.TimeWindow, now: Instant): Boolean {
        val local = now.atZone(zone)
        val minute = local.hour * 60 + local.minute
        val (start, end) = window.startMinute to window.endMinute
        return if (start <= end) minute in start until end else minute >= start || minute < end
    }

    private fun isDayType(dayType: Condition.DayType, now: Instant): Boolean {
        val weekend = now.atZone(zone).dayOfWeek in WEEKEND
        return if (weekend) dayType.weekends else dayType.weekdays
    }

    private fun truth(value: Boolean) = if (value) Truth.TRUE else Truth.FALSE

    private companion object {
        val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
    }
}
