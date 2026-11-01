package kr.routinegongbang.core.platform

import kr.routinegongbang.core.engine.DrivingState
import kr.routinegongbang.core.engine.Gear

/** `android.car.VehicleGear` values used by the AAOS `GEAR_SELECTION` property (public AAOS API). */
object AaosGear {
    const val NEUTRAL: Int = 0x0001
    const val REVERSE: Int = 0x0002
    const val PARK: Int = 0x0004
    const val DRIVE: Int = 0x0008
    const val FIRST: Int = 0x0010
    private const val NINTH: Int = 0x1000

    fun toGear(value: Int): Gear = when (value) {
        PARK -> Gear.PARK
        DRIVE -> Gear.DRIVE
        REVERSE -> Gear.REVERSE
        NEUTRAL -> Gear.NEUTRAL
        in FIRST..NINTH -> if (Integer.bitCount(value) == 1) Gear.DRIVE else Gear.UNKNOWN
        else -> Gear.UNKNOWN
    }
}

/**
 * Latest inputs of the parked check. Gear and UX restrictions are on-change properties;
 * speed is continuous, so a sample older than [maxSpeedAgeMs] means the stream stopped and speed is unknown.
 * Immutable; times are monotonic milliseconds.
 */
data class DrivingInputs(
    val gear: Gear? = null,
    val speedKph: Double? = null,
    val speedAtMs: Long? = null,
    val uxRestricted: Boolean? = null,
    val maxSpeedAgeMs: Long = DEFAULT_MAX_SPEED_AGE_MS,
) {
    fun withGear(gear: Gear?): DrivingInputs = copy(gear = gear)

    fun withSpeed(kph: Double?, atMs: Long): DrivingInputs = copy(speedKph = kph?.takeIf { it.isFinite() }, speedAtMs = atMs)

    fun withSpeedMetresPerSecond(metresPerSecond: Float?, atMs: Long): DrivingInputs =
        withSpeed(metresPerSecond?.let { it * KPH_PER_MPS }, atMs)

    fun withUxRestricted(restricted: Boolean?): DrivingInputs = copy(uxRestricted = restricted)

    fun state(nowMs: Long): DrivingState {
        val fresh = speedAtMs != null && nowMs - speedAtMs <= maxSpeedAgeMs
        return DrivingState(gear = gear, speedKph = if (fresh) speedKph else null, uxRestricted = uxRestricted)
    }

    companion object {
        /** Long enough for a stopped car (AAOS may slow the update rate), short enough to catch a dead stream. */
        const val DEFAULT_MAX_SPEED_AGE_MS: Long = 10_000
        private const val KPH_PER_MPS = 3.6
    }
}
