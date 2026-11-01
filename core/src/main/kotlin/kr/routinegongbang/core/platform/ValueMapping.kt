package kr.routinegongbang.core.platform

import kr.routinegongbang.core.model.SignalValue
import kr.routinegongbang.core.model.ValueCorrection
import kotlin.math.roundToInt

/** SDK value → engine value. The only place where catalog corrections are applied. */
fun ValueCorrection.fromPlatform(value: SignalValue): SignalValue = when (this) {
    ValueCorrection.NONE -> value
    ValueCorrection.INVERT_BOOLEAN -> if (value is SignalValue.Bool) SignalValue.Bool(!value.value) else value
}

/** Engine value → SDK value; every correction is its own inverse today. */
fun ValueCorrection.toPlatform(value: SignalValue): SignalValue = fromPlatform(value)

/**
 * Raw SDK range ↔ percent, e.g. window position whose capability reports 0..10 (about 20 % shows as 2).
 * Values outside the range are clamped so a bad reading can never leave the catalog range.
 */
data class PercentScale(val min: Int, val max: Int) {
    init {
        require(max > min) { "Scale needs max > min, was $min..$max" }
    }

    fun toPercent(raw: Int): SignalValue = SignalValue.num((raw.coerceIn(min, max) - min) * 100.0 / (max - min))

    fun toRaw(percent: Double): Int = (min + percent.coerceIn(0.0, 100.0) / 100.0 * (max - min)).roundToInt()
}
