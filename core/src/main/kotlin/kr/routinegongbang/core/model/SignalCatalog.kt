package kr.routinegongbang.core.model

import kr.routinegongbang.core.model.SafetyClass.BODY
import kr.routinegongbang.core.model.SafetyClass.COMFORT
import kr.routinegongbang.core.model.SafetyClass.EXCLUDED
import kr.routinegongbang.core.model.SafetyClass.SIGNAL_ONLY
import kr.routinegongbang.core.model.SdkModule.AAOS_CAR_PROPERTY
import kr.routinegongbang.core.model.SdkModule.VEHICLE_CONTROL
import kr.routinegongbang.core.model.SdkModule.VEHICLE_STATE
import kr.routinegongbang.core.model.ValueType.BOOLEAN
import kr.routinegongbang.core.model.ValueType.ENUM
import kr.routinegongbang.core.model.ValueType.NUMBER

object SignalIds {
    val WIPER_FRONT_STATE = SignalId("wiper.front.state")
    val WIPER_FRONT_SWITCH = SignalId("wiper.front.switch")
    val DEFROSTER_FRONT = SignalId("hvac.defroster.front")
    val RECIRCULATION = SignalId("hvac.recirculation")
    val HVAC_TEMP_DRIVER = SignalId("hvac.temperature.driver")
    val STEERING_WHEEL_HEAT = SignalId("steeringWheel.heat")
    val SEAT_HEAT_DRIVER = SignalId("seat.heat.driver")
    val SEAT_OCCUPIED_DRIVER = SignalId("seat.occupied.driver")
    val SEAT_OCCUPIED_PASSENGER = SignalId("seat.occupied.passenger")
    val DOOR_LOCK = SignalId("door.lock")
    val WINDOW_POSITION_DRIVER = SignalId("window.position.driver")
    val EV_SOC = SignalId("ev.soc")
    val EV_CHARGING = SignalId("ev.charging")
    val GEAR_SELECTION = SignalId("gear.selection")
    val VEHICLE_SPEED = SignalId("vehicle.speed")
    val LOW_BEAM = SignalId("light.exterior.lowBeam")
    val ROOF_SUNROOF = SignalId("roof.sunroof")

    // Phase 2: comfort controls.
    val HVAC_POWER = SignalId("hvac.power")
    val HVAC_AC = SignalId("hvac.ac")
    val HVAC_AUTO = SignalId("hvac.auto")
    val HVAC_FAN_SPEED = SignalId("hvac.fan.speed")
    val HVAC_TEMP_PASSENGER = SignalId("hvac.temperature.passenger")
    val HVAC_AUTO_RECIRCULATION = SignalId("hvac.recirculation.auto")
    val HVAC_AIR_CLEANER = SignalId("hvac.airCleaner")
    val HVAC_MAX_AC = SignalId("hvac.maxAc")
    val HVAC_MAX_DEFROST = SignalId("hvac.maxDefrost")
    val HVAC_DUAL = SignalId("hvac.dual")
    val SEAT_HEAT_PASSENGER = SignalId("seat.heat.passenger")
    val SEAT_HEAT_REAR_LEFT = SignalId("seat.heat.rearLeft")
    val SEAT_HEAT_REAR_RIGHT = SignalId("seat.heat.rearRight")
    val SEAT_VENT_DRIVER = SignalId("seat.vent.driver")
    val SEAT_VENT_PASSENGER = SignalId("seat.vent.passenger")
    val MIRROR_HEAT = SignalId("mirror.heat")
    val AMBIENT_LIGHT = SignalId("light.ambient")
    val AMBIENT_BRIGHTNESS = SignalId("light.ambient.brightness")

    // Phase 2: readings for triggers and conditions.
    val SEAT_OCCUPIED_REAR_LEFT = SignalId("seat.occupied.rearLeft")
    val SEAT_OCCUPIED_REAR_RIGHT = SignalId("seat.occupied.rearRight")
    val SEATBELT_DRIVER = SignalId("seatbelt.driver")
    val SEATBELT_PASSENGER = SignalId("seatbelt.passenger")
    val CABIN_TEMPERATURE = SignalId("hvac.temperature.cabin")
    val WASHER_FLUID_LOW = SignalId("washer.fluid.low")
    val DRIVING_MODE = SignalId("driving.mode")
    val WINDOW_POSITION_PASSENGER = SignalId("window.position.passenger")
    val WINDOW_LOCK = SignalId("window.lock")
    val DOOR_OPEN_DRIVER = SignalId("door.open.driver")
    val ODOMETER = SignalId("odometer")
    val TRIP_DISTANCE = SignalId("trip.distance")
}

/** Registry of every signal the app knows. Immutable after construction. */
class SignalCatalog(specs: List<SignalSpec>) {
    val all: List<SignalSpec> = specs.toList()
    private val byId: Map<SignalId, SignalSpec> = all.associateBy { it.id }

    init {
        val duplicates = all.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate signal ids in catalog: ${duplicates.joinToString { it.value }}" }
    }

    operator fun get(id: SignalId): SignalSpec? = byId[id]

    fun getOrThrow(id: SignalId): SignalSpec = byId[id] ?: throw IllegalArgumentException("Unknown signal: ${id.value}")

    companion object {
        fun default(): SignalCatalog = SignalCatalog(DEFAULT_SIGNALS + PHASE2_COMFORT + PHASE2_READINGS)

        private fun texts(vararg values: String): Set<SignalValue> = values.map { SignalValue.Text(it) }.toSet()

        private val WIPER_STATES = texts("OFF", "ON", "OTHER", "SERVICE")
        private val WIPER_SWITCHES = texts(
            "OTHER", "OFF", "MIST", "AUTO", "SERVICE",
            "INTERMITTENT_LEVEL_1", "INTERMITTENT_LEVEL_2", "INTERMITTENT_LEVEL_3", "INTERMITTENT_LEVEL_4", "INTERMITTENT_LEVEL_5",
            "CONTINUOUS_LEVEL_1", "CONTINUOUS_LEVEL_2", "CONTINUOUS_LEVEL_3", "CONTINUOUS_LEVEL_4", "CONTINUOUS_LEVEL_5",
        )
        private val GEARS = texts("PARK", "REVERSE", "NEUTRAL", "DRIVE", "UNKNOWN")
        private val PERCENT = 0.0..100.0

        private val ON = SignalValue.Bool(true)

        /** Comfort settings: any value, no seat requirement, nothing coupled known yet (a real car's self-test measures that). */
        private fun comfort(
            evidence: String,
            autoRange: ClosedFloatingPointRange<Double>? = null,
            visibility: WriteDirection? = null,
            lifeSupport: LifeSupport? = null,
            holdLimitMinutes: Int? = null,
            occupancy: Occupancy = Occupancy.Any,
            coupled: Set<SignalId> = emptySet(),
        ) = SafetyProfile(
            direction = WriteDirection.Any, autoRange = autoRange, occupancy = occupancy, visibility = visibility,
            lifeSupport = lifeSupport, coupledSignals = coupled, maxWritesPerMinute = 2, maxWritesPerHour = 20,
            holdLimitMinutes = holdLimitMinutes, confirmRequired = false, evidence = evidence,
        )

        /** Couplings declared from how such modes work in cars; the emulator cannot measure them, a real car's self-test will. */
        private const val ASSUMED =
            "assumed from how the mode works; the emulator does not simulate couplings, so the self-test on a real car decides"
        private const val PHASE2 = "phase 2; access, area and echo confirmed by the emulator self-test"
        private const val AMBIENT_SWITCH =
            "phase 2; the switch write is not confirmable on the emulator: its switch and the light state this app reads are not connected there"

        private val HEAT_LEVELS = 0.0..3.0
        private val TEMPERATURES = 16.0..30.0
        private val CLIMATE_COMFORT = LifeSupport.ComfortBand(18.0..26.0)

        /** Heat for a seat other than the driver's: only for someone known to sit there. */
        private fun seatHeat(id: SignalId, area: AreaKey, seat: SignalId) = SignalSpec(
            id, NUMBER, COMFORT, VEHICLE_STATE, VEHICLE_CONTROL, area = area, range = HEAT_LEVELS,
            profile = comfort(PHASE2, autoRange = 0.0..2.0, holdLimitMinutes = 30, occupancy = Occupancy.Occupied(seat)),
        )

        private fun switch(id: SignalId, profile: SafetyProfile) = SignalSpec(id, BOOLEAN, COMFORT, VEHICLE_STATE, VEHICLE_CONTROL, profile = profile)

        private fun reading(
            id: SignalId,
            type: ValueType,
            area: AreaKey = AreaKeys.GLOBAL,
            range: ClosedFloatingPointRange<Double>? = null,
            rounding: Rounding = Rounding.None,
        ) =
            SignalSpec(id, type, SIGNAL_ONLY, VEHICLE_STATE, area = area, range = range, rounding = rounding)

        /** Body parts: one direction only, always confirmed, rarely. */
        private fun body(direction: WriteDirection, evidence: String) = SafetyProfile(
            direction = direction, autoRange = null, occupancy = Occupancy.Any, visibility = null, lifeSupport = null,
            coupledSignals = emptySet(), maxWritesPerMinute = 2, maxWritesPerHour = 10, holdLimitMinutes = null,
            confirmRequired = true, evidence = evidence,
        )

        private val DEFAULT_SIGNALS: List<SignalSpec> = listOf(
            SignalSpec(SignalIds.WIPER_FRONT_STATE, ENUM, SIGNAL_ONLY, VEHICLE_STATE, area = AreaKeys.FRONT_WINDSHIELD, allowedValues = WIPER_STATES),
            // The switch cannot be subscribed; only polled on demand. Never written: it decides the view.
            SignalSpec(
                SignalIds.WIPER_FRONT_SWITCH, ENUM, EXCLUDED, VEHICLE_STATE, VEHICLE_CONTROL,
                subscribable = false, area = AreaKeys.FRONT_WINDSHIELD, allowedValues = WIPER_SWITCHES,
            ),
            // Turning the defroster off is never automated: the view must not get worse.
            SignalSpec(
                SignalIds.DEFROSTER_FRONT, BOOLEAN, COMFORT, VEHICLE_STATE, VEHICLE_CONTROL, area = AreaKeys.FRONT_WINDSHIELD,
                profile = comfort("written and echoed on the emulator", visibility = WriteDirection.Only(setOf(ON))),
            ),
            SignalSpec(
                SignalIds.RECIRCULATION, BOOLEAN, COMFORT, VEHICLE_STATE, VEHICLE_CONTROL,
                profile = comfort("written and echoed on the emulator", holdLimitMinutes = 30, coupled = setOf(SignalIds.HVAC_AUTO_RECIRCULATION)),
            ),
            // People or pets left in the car may depend on it; extremes need a confirmation.
            SignalSpec(
                SignalIds.HVAC_TEMP_DRIVER, NUMBER, COMFORT, VEHICLE_STATE, VEHICLE_CONTROL,
                rounding = Rounding.Step(0.5), ledgerTimeoutMs = 5_000, range = 16.0..30.0,
                profile = comfort(
                    "automatic range 17–28 °C; unattended only 18–26 °C",
                    autoRange = 17.0..28.0, lifeSupport = LifeSupport.ComfortBand(18.0..26.0),
                    // With dual off the passenger side follows the driver (assumed, see the self-test).
                    coupled = setOf(SignalIds.HVAC_TEMP_PASSENGER),
                ),
            ),
            // The driver's own heaters may preheat before anyone sits down; level 3 needs a confirmation.
            SignalSpec(
                SignalIds.STEERING_WHEEL_HEAT, NUMBER, COMFORT, VEHICLE_STATE, VEHICLE_CONTROL, range = 0.0..3.0,
                profile = comfort("level 1 echoed within 1 s on the emulator", autoRange = 0.0..2.0, holdLimitMinutes = 30),
            ),
            SignalSpec(
                SignalIds.SEAT_HEAT_DRIVER, NUMBER, COMFORT, VEHICLE_STATE, VEHICLE_CONTROL, area = AreaKeys.DRIVER_SEAT, range = 0.0..3.0,
                profile = comfort("written and echoed on the emulator", autoRange = 0.0..2.0, holdLimitMinutes = 30),
            ),
            // SDK reports the inverse of the raw AAOS value.
            SignalSpec(SignalIds.SEAT_OCCUPIED_DRIVER, BOOLEAN, SIGNAL_ONLY, VEHICLE_STATE, area = AreaKeys.DRIVER_SEAT, correction = ValueCorrection.INVERT_BOOLEAN),
            SignalSpec(SignalIds.SEAT_OCCUPIED_PASSENGER, BOOLEAN, SIGNAL_ONLY, VEHICLE_STATE, area = AreaKeys.PASSENGER_SEAT, correction = ValueCorrection.INVERT_BOOLEAN),
            // Lock state is reported only on the combined four-door area; unlocking is never automated.
            SignalSpec(
                SignalIds.DOOR_LOCK, BOOLEAN, BODY, VEHICLE_STATE, VEHICLE_CONTROL, area = AreaKeys.ALL_PASSENGER_DOORS,
                profile = body(WriteDirection.Only(setOf(ON)), "lock is READ_WRITE on the combined area (emulator)"),
            ),
            // Percent in the core; the adapter converts using capability bounds. Only closing is automated.
            SignalSpec(
                SignalIds.WINDOW_POSITION_DRIVER, NUMBER, BODY, VEHICLE_STATE, VEHICLE_CONTROL, area = AreaKeys.DRIVER_WINDOW, range = PERCENT,
                profile = body(WriteDirection.Decrease, "window position is READ_WRITE; no write adapter yet"),
            ),
            SignalSpec(SignalIds.EV_SOC, NUMBER, SIGNAL_ONLY, VEHICLE_STATE, range = PERCENT),
            // Served by AAOS EV_CHARGE_STATE, not by the Pleos SDK.
            SignalSpec(SignalIds.EV_CHARGING, BOOLEAN, SIGNAL_ONLY, AAOS_CAR_PROPERTY),
            SignalSpec(SignalIds.GEAR_SELECTION, ENUM, SIGNAL_ONLY, AAOS_CAR_PROPERTY, allowedValues = GEARS),
            SignalSpec(SignalIds.VEHICLE_SPEED, NUMBER, SIGNAL_ONLY, AAOS_CAR_PROPERTY, range = 0.0..400.0),
            SignalSpec(SignalIds.LOW_BEAM, BOOLEAN, EXCLUDED, VEHICLE_STATE, VEHICLE_CONTROL),
            // Not available in the Pleos Connect Vehicle SDK; ROOF_TOP areas are absent from capabilities.
            SignalSpec(SignalIds.ROOF_SUNROOF, NUMBER, BODY, readSdk = null, unsupported = true),
        )

        /** Phase 2: every comfort control of the Pleos SDK this app writes. */
        private val PHASE2_COMFORT: List<SignalSpec> = listOf(
            // Turning the climate off, or the fan down, is what someone left inside could suffer from.
            switch(SignalIds.HVAC_POWER, comfort(PHASE2, lifeSupport = LifeSupport.KeepOn(ON))),
            switch(SignalIds.HVAC_AC, comfort(PHASE2, lifeSupport = LifeSupport.KeepOn(ON))),
            switch(
                SignalIds.HVAC_AUTO,
                comfort(ASSUMED, coupled = setOf(SignalIds.HVAC_FAN_SPEED, SignalIds.HVAC_AC, SignalIds.RECIRCULATION)),
            ),
            SignalSpec(
                SignalIds.HVAC_FAN_SPEED, NUMBER, COMFORT, VEHICLE_STATE, VEHICLE_CONTROL, range = 0.0..10.0,
                // A manual fan speed ends AUTO (assumed).
                profile = comfort(PHASE2, lifeSupport = LifeSupport.NoDecrease, coupled = setOf(SignalIds.HVAC_AUTO)),
            ),
            SignalSpec(
                SignalIds.HVAC_TEMP_PASSENGER, NUMBER, COMFORT, VEHICLE_STATE, VEHICLE_CONTROL, area = AreaKeys.PASSENGER_SEAT,
                rounding = Rounding.Step(0.5), ledgerTimeoutMs = 5_000, range = TEMPERATURES,
                // Setting the passenger side switches dual on (assumed).
                profile = comfort(PHASE2, autoRange = 17.0..28.0, lifeSupport = CLIMATE_COMFORT, coupled = setOf(SignalIds.HVAC_DUAL)),
            ),
            switch(SignalIds.HVAC_AUTO_RECIRCULATION, comfort(PHASE2)),
            switch(SignalIds.HVAC_AIR_CLEANER, comfort(PHASE2)),
            switch(
                SignalIds.HVAC_MAX_AC,
                comfort(ASSUMED, coupled = setOf(SignalIds.HVAC_FAN_SPEED, SignalIds.HVAC_AC, SignalIds.RECIRCULATION)),
            ),
            // Only switching it on is automated: switching it off could leave the windscreen fogged.
            switch(
                SignalIds.HVAC_MAX_DEFROST,
                comfort(
                    ASSUMED, visibility = WriteDirection.Only(setOf(ON)),
                    coupled = setOf(SignalIds.DEFROSTER_FRONT, SignalIds.HVAC_FAN_SPEED, SignalIds.HVAC_AC, SignalIds.RECIRCULATION),
                ),
            ),
            switch(SignalIds.HVAC_DUAL, comfort(ASSUMED, coupled = setOf(SignalIds.HVAC_TEMP_PASSENGER))),
            seatHeat(SignalIds.SEAT_HEAT_PASSENGER, AreaKeys.PASSENGER_SEAT, SignalIds.SEAT_OCCUPIED_PASSENGER),
            seatHeat(SignalIds.SEAT_HEAT_REAR_LEFT, AreaKeys.REAR_LEFT_SEAT, SignalIds.SEAT_OCCUPIED_REAR_LEFT),
            seatHeat(SignalIds.SEAT_HEAT_REAR_RIGHT, AreaKeys.REAR_RIGHT_SEAT, SignalIds.SEAT_OCCUPIED_REAR_RIGHT),
            SignalSpec(
                SignalIds.SEAT_VENT_DRIVER, NUMBER, COMFORT, VEHICLE_STATE, VEHICLE_CONTROL, area = AreaKeys.DRIVER_SEAT, range = HEAT_LEVELS,
                profile = comfort(PHASE2),
            ),
            SignalSpec(
                SignalIds.SEAT_VENT_PASSENGER, NUMBER, COMFORT, VEHICLE_STATE, VEHICLE_CONTROL, area = AreaKeys.PASSENGER_SEAT, range = HEAT_LEVELS,
                profile = comfort(PHASE2, occupancy = Occupancy.Occupied(SignalIds.SEAT_OCCUPIED_PASSENGER)),
            ),
            SignalSpec(
                SignalIds.MIRROR_HEAT, NUMBER, COMFORT, VEHICLE_STATE, VEHICLE_CONTROL, range = 0.0..1.0,
                // On or off; switching it off could leave the mirrors fogged or iced, so only on is automated.
                profile = comfort(PHASE2, holdLimitMinutes = 30, visibility = WriteDirection.Only(setOf(SignalValue.of(1)))),
            ),
            // Light changes are capped at 2 a minute by the comfort profile, so a routine cannot make the cabin flash.
            switch(SignalIds.AMBIENT_LIGHT, comfort(AMBIENT_SWITCH)),
            // The car's own brightness steps, read and written exactly: a percent conversion would round our echo
            // into a different value, and coarse rounding would hide a small change the user made (random test seed 77).
            SignalSpec(
                SignalIds.AMBIENT_BRIGHTNESS, NUMBER, COMFORT, VEHICLE_STATE, VEHICLE_CONTROL, range = 0.0..255.0,
                profile = comfort(PHASE2),
            ),
        )

        /** Phase 2: the readings worth a trigger or a condition. */
        private val PHASE2_READINGS: List<SignalSpec> = listOf(
            // SDK reports the inverse of the raw AAOS value for every seat; the self-test checks the rear.
            SignalSpec(SignalIds.SEAT_OCCUPIED_REAR_LEFT, BOOLEAN, SIGNAL_ONLY, VEHICLE_STATE, area = AreaKeys.REAR_LEFT_SEAT, correction = ValueCorrection.INVERT_BOOLEAN),
            SignalSpec(SignalIds.SEAT_OCCUPIED_REAR_RIGHT, BOOLEAN, SIGNAL_ONLY, VEHICLE_STATE, area = AreaKeys.REAR_RIGHT_SEAT, correction = ValueCorrection.INVERT_BOOLEAN),
            reading(SignalIds.SEATBELT_DRIVER, BOOLEAN, AreaKeys.DRIVER_SEAT),
            reading(SignalIds.SEATBELT_PASSENGER, BOOLEAN, AreaKeys.PASSENGER_SEAT),
            // The sensor reports 19.100000381469727; a tenth of a degree is all that means anything.
            SignalSpec(SignalIds.CABIN_TEMPERATURE, NUMBER, SIGNAL_ONLY, VEHICLE_STATE, rounding = Rounding.Step(0.1), range = -40.0..85.0),
            reading(SignalIds.WASHER_FLUID_LOW, BOOLEAN),
            SignalSpec(SignalIds.DRIVING_MODE, ENUM, SIGNAL_ONLY, VEHICLE_STATE, allowedValues = texts("NORMAL", "ECO", "SPORT")),
            reading(SignalIds.WINDOW_POSITION_PASSENGER, NUMBER, AreaKeys.PASSENGER_WINDOW, PERCENT),
            reading(SignalIds.WINDOW_LOCK, BOOLEAN),
            reading(SignalIds.DOOR_OPEN_DRIVER, BOOLEAN, AreaKeys.DRIVER_DOOR),
            // A tenth of a kilometre, so a moving car does not raise an event every few metres.
            reading(SignalIds.ODOMETER, NUMBER, range = 0.0..2_000_000.0, rounding = Rounding.Step(0.1)),
            reading(SignalIds.TRIP_DISTANCE, NUMBER, range = 0.0..5_000.0, rounding = Rounding.Step(0.1)),
        )
    }
}
