package kr.routinegongbang.core.engine

import kr.routinegongbang.core.model.SignalId
import kr.routinegongbang.core.model.SignalValue
import kotlin.math.abs

enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

/**
 * Inputs for the parked check: AAOS GEAR_SELECTION, PERF_VEHICLE_SPEED and
 * CarUxRestrictions. Any unknown input means "not parked", so body actions fail safe.
 */
data class DrivingState(val gear: Gear?, val speedKph: Double?, val uxRestricted: Boolean?) {
    val isParked: Boolean
        get() = gear == Gear.PARK && speedKph != null && abs(speedKph) <= STOP_THRESHOLD_KPH && uxRestricted == false

    private companion object {
        const val STOP_THRESHOLD_KPH = 0.5
    }
}

/** Everything the planner needs to decide one action. Built fresh right before each decision. */
data class ExecutionContext(
    val driving: DrivingState,
    val capability: CapabilitySnapshot,
    val values: Map<SignalId, SignalValue>,
    /** Signals the user changed by hand during this trip; routines do not override them. */
    val userOverrides: Set<SignalId>,
    /** The OEM navigation is currently guiding, so a new destination needs consent. */
    val navigating: Boolean,
    /** An OEM convenience mode (rest, pet, utility) is running. Not detectable yet; adapters pass false. */
    val oemModeActive: Boolean = false,
    /** A Gleo voice command ran in the last few seconds. */
    val gleoRecentlyUsed: Boolean = false,
    /** Someone is driving or about to; false before the first seat event and after the trip ended. */
    val tripActive: Boolean = false,
    /** The driver left and locked; the trip is only waiting to end. */
    val tripEnding: Boolean = false,
) {
    /** Proven by the seat sensor; unknown is not proof. */
    fun seat(signal: SignalId): Boolean? = (values[signal] as? SignalValue.Bool)?.value
}
