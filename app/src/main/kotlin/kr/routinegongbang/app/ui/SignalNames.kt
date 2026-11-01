package kr.routinegongbang.app.ui

import kr.routinegongbang.app.R
import kr.routinegongbang.core.model.SignalId
import kr.routinegongbang.core.model.SignalIds
import kr.routinegongbang.core.model.SignalValue

/** Korean names and units of every catalog signal; [SignalNamesTest] keeps it complete as the catalog grows. */
internal object SignalNames {

    val names: Map<SignalId, Int> = mapOf(
        SignalIds.WIPER_FRONT_STATE to R.string.signal_wiper_front_state,
        SignalIds.WIPER_FRONT_SWITCH to R.string.signal_wiper_front_switch,
        SignalIds.DEFROSTER_FRONT to R.string.signal_hvac_defroster_front,
        SignalIds.RECIRCULATION to R.string.signal_hvac_recirculation,
        SignalIds.HVAC_TEMP_DRIVER to R.string.signal_hvac_temperature_driver,
        SignalIds.STEERING_WHEEL_HEAT to R.string.signal_steeringWheel_heat,
        SignalIds.SEAT_HEAT_DRIVER to R.string.signal_seat_heat_driver,
        SignalIds.SEAT_OCCUPIED_DRIVER to R.string.signal_seat_occupied_driver,
        SignalIds.SEAT_OCCUPIED_PASSENGER to R.string.signal_seat_occupied_passenger,
        SignalIds.DOOR_LOCK to R.string.signal_door_lock,
        SignalIds.WINDOW_POSITION_DRIVER to R.string.signal_window_position_driver,
        SignalIds.EV_SOC to R.string.signal_ev_soc,
        SignalIds.EV_CHARGING to R.string.signal_ev_charging,
        SignalIds.GEAR_SELECTION to R.string.signal_gear_selection,
        SignalIds.VEHICLE_SPEED to R.string.signal_vehicle_speed,
        SignalIds.LOW_BEAM to R.string.signal_low_beam,
        SignalIds.HVAC_POWER to R.string.signal_hvac_power,
        SignalIds.HVAC_AC to R.string.signal_hvac_ac,
        SignalIds.HVAC_AUTO to R.string.signal_hvac_auto,
        SignalIds.HVAC_FAN_SPEED to R.string.signal_hvac_fan_speed,
        SignalIds.HVAC_TEMP_PASSENGER to R.string.signal_hvac_temperature_passenger,
        SignalIds.HVAC_AUTO_RECIRCULATION to R.string.signal_hvac_recirculation_auto,
        SignalIds.HVAC_AIR_CLEANER to R.string.signal_hvac_air_cleaner,
        SignalIds.HVAC_MAX_AC to R.string.signal_hvac_max_ac,
        SignalIds.HVAC_MAX_DEFROST to R.string.signal_hvac_max_defrost,
        SignalIds.HVAC_DUAL to R.string.signal_hvac_dual,
        SignalIds.SEAT_HEAT_PASSENGER to R.string.signal_seat_heat_passenger,
        SignalIds.SEAT_HEAT_REAR_LEFT to R.string.signal_seat_heat_rear_left,
        SignalIds.SEAT_HEAT_REAR_RIGHT to R.string.signal_seat_heat_rear_right,
        SignalIds.SEAT_VENT_DRIVER to R.string.signal_seat_vent_driver,
        SignalIds.SEAT_VENT_PASSENGER to R.string.signal_seat_vent_passenger,
        SignalIds.MIRROR_HEAT to R.string.signal_mirror_heat,
        SignalIds.AMBIENT_LIGHT to R.string.signal_ambient_light,
        SignalIds.AMBIENT_BRIGHTNESS to R.string.signal_ambient_brightness,
        SignalIds.SEAT_OCCUPIED_REAR_LEFT to R.string.signal_seat_occupied_rear_left,
        SignalIds.SEAT_OCCUPIED_REAR_RIGHT to R.string.signal_seat_occupied_rear_right,
        SignalIds.SEATBELT_DRIVER to R.string.signal_seatbelt_driver,
        SignalIds.SEATBELT_PASSENGER to R.string.signal_seatbelt_passenger,
        SignalIds.CABIN_TEMPERATURE to R.string.signal_cabin_temperature,
        SignalIds.WASHER_FLUID_LOW to R.string.signal_washer_fluid_low,
        SignalIds.DRIVING_MODE to R.string.signal_driving_mode,
        SignalIds.WINDOW_POSITION_PASSENGER to R.string.signal_window_position_passenger,
        SignalIds.WINDOW_LOCK to R.string.signal_window_lock,
        SignalIds.DOOR_OPEN_DRIVER to R.string.signal_door_open_driver,
        SignalIds.ODOMETER to R.string.signal_odometer,
        SignalIds.TRIP_DISTANCE to R.string.signal_trip_distance,
    )

    private const val LEVEL = "단계"

    val units: Map<SignalId, String> = mapOf(
        SignalIds.HVAC_TEMP_DRIVER to "°C", SignalIds.HVAC_TEMP_PASSENGER to "°C", SignalIds.CABIN_TEMPERATURE to "°C",
        SignalIds.STEERING_WHEEL_HEAT to LEVEL, SignalIds.SEAT_HEAT_DRIVER to LEVEL, SignalIds.SEAT_HEAT_PASSENGER to LEVEL,
        SignalIds.SEAT_HEAT_REAR_LEFT to LEVEL, SignalIds.SEAT_HEAT_REAR_RIGHT to LEVEL, SignalIds.SEAT_VENT_DRIVER to LEVEL,
        SignalIds.SEAT_VENT_PASSENGER to LEVEL, SignalIds.MIRROR_HEAT to LEVEL, SignalIds.HVAC_FAN_SPEED to LEVEL,
        SignalIds.AMBIENT_BRIGHTNESS to LEVEL,
        SignalIds.EV_SOC to "%", SignalIds.WINDOW_POSITION_DRIVER to "%", SignalIds.WINDOW_POSITION_PASSENGER to "%",
        SignalIds.VEHICLE_SPEED to " km/h", SignalIds.ODOMETER to " km", SignalIds.TRIP_DISTANCE to " km",
    )

    /** At most one decimal, so a sensor's 19.100000381469727 reads 19.1. */
    fun number(v: Double): String {
        val tenths = Math.round(v * 10)
        return if (tenths % 10 == 0L) (tenths / 10).toString() else (tenths / 10.0).toString()
    }

    val drivingModes: Map<SignalValue, Int> = mapOf(
        SignalValue.Text("NORMAL") to R.string.value_mode_normal,
        SignalValue.Text("ECO") to R.string.value_mode_eco,
        SignalValue.Text("SPORT") to R.string.value_mode_sport,
    )
}
