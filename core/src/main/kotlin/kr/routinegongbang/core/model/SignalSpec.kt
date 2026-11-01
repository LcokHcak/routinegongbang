package kr.routinegongbang.core.model

import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class SignalId(val value: String)

/** Vehicle area the SDK expects. Some properties only report on a combined area. */
@Serializable
@JvmInline
value class AreaKey(val value: String)

object AreaKeys {
    val GLOBAL = AreaKey("GLOBAL")
    val ALL_PASSENGER_DOORS = AreaKey("ROW_1_LEFT+ROW_1_RIGHT+ROW_2_LEFT+ROW_2_RIGHT")
    val DRIVER_SEAT = AreaKey("SEAT_ROW_1_LEFT")
    val PASSENGER_SEAT = AreaKey("SEAT_ROW_1_RIGHT")
    val DRIVER_WINDOW = AreaKey("WINDOW_ROW_1_LEFT")
    val FRONT_WINDSHIELD = AreaKey("FRONT_WINDSHIELD")
    val REAR_LEFT_SEAT = AreaKey("SEAT_ROW_2_LEFT")
    val REAR_RIGHT_SEAT = AreaKey("SEAT_ROW_2_RIGHT")
    val PASSENGER_WINDOW = AreaKey("WINDOW_ROW_1_RIGHT")
    val DRIVER_DOOR = AreaKey("DOOR_ROW_1_LEFT")
}

/**
 * Required for every signal so a write can never skip the safety classification.
 * COMFORT runs on its own; BODY and ENERGY only parked, with the driver confirming; POSTURE (seat motors) is
 * read-only in V1; SIGNAL_ONLY is for triggers and conditions; EXCLUDED is never written.
 */
enum class SafetyClass { COMFORT, BODY, ENERGY, POSTURE, EXCLUDED, SIGNAL_ONLY;

    /** Classes that move parts or change charging: never while driving, always with a confirmation. */
    val parkedOnly: Boolean get() = this == BODY || this == ENERGY || this == POSTURE
}

/** Which platform module serves the signal; checked against SDK availability. */
enum class SdkModule { VEHICLE_STATE, VEHICLE_CONTROL, NAVI, TTS, STT, LOCATION, AAOS_CAR_PROPERTY }

/** Adapter-level fix for SDK values that do not match their documented meaning. */
enum class ValueCorrection { NONE, INVERT_BOOLEAN }

data class SignalSpec(
    val id: SignalId,
    val valueType: ValueType,
    val safetyClass: SafetyClass,
    val readSdk: SdkModule?,
    val writeSdk: SdkModule? = null,
    val subscribable: Boolean = true,
    val area: AreaKey = AreaKeys.GLOBAL,
    val rounding: Rounding = Rounding.None,
    val ledgerTimeoutMs: Long = DEFAULT_LEDGER_TIMEOUT_MS,
    val correction: ValueCorrection = ValueCorrection.NONE,
    /** Inclusive numeric range; only for NUMBER signals. */
    val range: ClosedFloatingPointRange<Double>? = null,
    /** Closed value set; mainly for ENUM signals. */
    val allowedValues: Set<SignalValue>? = null,
    val unsupported: Boolean = false,
    /** Required exactly when the signal is [writable]. */
    val profile: SafetyProfile? = null,
) {
    init {
        require(safetyClass != SafetyClass.SIGNAL_ONLY || writeSdk == null) { "${id.value}: SIGNAL_ONLY must not declare a write SDK" }
        require(allowedValues?.all { it.type == valueType } ?: true) { "${id.value}: allowed values must be $valueType" }
        require(range == null || valueType == ValueType.NUMBER) { "${id.value}: range is only for NUMBER signals" }
        require(ledgerTimeoutMs > 0) { "${id.value}: ledger timeout must be positive" }
        require(writable == (profile != null)) { "${id.value}: a writable signal needs a safety profile, and only a writable one" }
        profile?.let { p ->
            val onlyValues = (p.direction as? WriteDirection.Only)?.values.orEmpty()
            require(onlyValues.all { it.type == valueType }) { "${id.value}: direction values must be $valueType" }
            require(p.autoRange == null || (range != null && p.autoRange.start >= range.start && p.autoRange.endInclusive <= range.endInclusive)) {
                "${id.value}: the automatic range must lie inside the signal range"
            }
        }
    }

    /** POSTURE stays read-only in V1. */
    val writable: Boolean
        get() = !unsupported && writeSdk != null &&
            (safetyClass == SafetyClass.COMFORT || safetyClass == SafetyClass.BODY || safetyClass == SafetyClass.ENERGY)

    /** True when [value] is inside the declared range and value set (type is checked separately). */
    fun accepts(value: SignalValue): Boolean {
        val inRange = range?.let { r -> value !is SignalValue.Num || value.value in r } ?: true
        val inSet = allowedValues?.contains(value) ?: true
        return inRange && inSet
    }

    companion object {
        const val DEFAULT_LEDGER_TIMEOUT_MS: Long = 3_000
    }
}
