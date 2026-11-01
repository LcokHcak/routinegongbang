package kr.routinegongbang.core.model

/**
 * Which values a routine may write on its own. [Decrease] and [Increase] compare with the
 * current value at run time; an unknown current value never proves the direction, so the write is refused.
 */
sealed interface WriteDirection {
    data object Any : WriteDirection
    data class Only(val values: Set<SignalValue>) : WriteDirection
    data object Decrease : WriteDirection
    data object Increase : WriteDirection

    fun allows(target: SignalValue, current: SignalValue?): Boolean = when (this) {
        Any -> true
        is Only -> target in values
        Decrease -> compare(target, current)?.let { it <= 0 } ?: false
        Increase -> compare(target, current)?.let { it >= 0 } ?: false
    }

    /** What a saved routine already gets wrong, before any current value is known; null when nothing yet. */
    fun staticViolation(target: SignalValue): String? =
        if (this is Only && target !in values) "only ${values.joinToString()} may be written automatically" else null

    private fun compare(target: SignalValue, current: SignalValue?): Int? {
        val a = (target as? SignalValue.Num)?.value ?: return null
        val b = (current as? SignalValue.Num)?.value ?: return null
        return a.compareTo(b)
    }
}

/**
 * Whether a seat-bound write needs someone in [seat]. Acts only on proof: an unknown seat
 * satisfies neither requirement, so nothing heats a seat nobody is known to sit in, and nothing moves next to
 * someone the sensor may have missed.
 */
sealed interface Occupancy {
    data object Any : Occupancy
    data class Occupied(val seat: SignalId) : Occupancy
    data class Empty(val seat: SignalId) : Occupancy
}

/**
 * What "weakening" means for a climate setting someone left in the car may depend on.
 * A warm-up or cool-down to a comfortable setting is allowed without a driver; turning it off or to an extreme is not.
 */
sealed interface LifeSupport {
    fun weakens(target: SignalValue, current: SignalValue?): Boolean

    /** On/off: anything but [on] weakens (A/C power, climate power). */
    data class KeepOn(val on: SignalValue) : LifeSupport {
        override fun weakens(target: SignalValue, current: SignalValue?) = target != on
    }

    /** Levels: going down weakens (fan speed); an unknown current level cannot prove it does not. */
    data object NoDecrease : LifeSupport {
        override fun weakens(target: SignalValue, current: SignalValue?) = !WriteDirection.Increase.allows(target, current)
    }

    /** Temperatures: anything outside [band] weakens. */
    data class ComfortBand(val band: ClosedFloatingPointRange<Double>) : LifeSupport {
        override fun weakens(target: SignalValue, current: SignalValue?) = (target as? SignalValue.Num)?.value?.let { it !in band } ?: true
    }
}

/**
 * The safety answers every writable signal must give. No parameter has a default, so adding a
 * signal forces each question to be answered; [evidence] says where the answer comes from.
 */
data class SafetyProfile(
    /** Values a routine may write on its own; others are refused at save and at run time. */
    val direction: WriteDirection,
    /** Automatic range inside the SDK range; values outside it need a confirmation. Null: the whole range. */
    val autoRange: ClosedFloatingPointRange<Double>?,
    val occupancy: Occupancy,
    /** Non-null for signals that affect the view out of the car: the only favourable direction. */
    val visibility: WriteDirection?,
    /** Someone left in the car may depend on it: never weakened without an active trip. Null: no such role. */
    val lifeSupport: LifeSupport?,
    /** Signals the car changes by itself when this one is written; their changes are the app's. */
    val coupledSignals: Set<SignalId>,
    val maxWritesPerMinute: Int,
    val maxWritesPerHour: Int,
    /** Long use worth a reminder (low-temperature burns, stale cabin air). Null: no limit. */
    val holdLimitMinutes: Int?,
    val confirmRequired: Boolean,
    val evidence: String,
) {
    init {
        require(maxWritesPerMinute > 0 && maxWritesPerHour >= maxWritesPerMinute) { "rate limits must be positive and consistent" }
        require(evidence.isNotBlank()) { "a safety profile needs its evidence" }
    }
}

/** Writing a signal back to a value it had within this window counts as flipping back and forth. */
const val FLIP_WINDOW_MS: Long = 2 * 60_000
