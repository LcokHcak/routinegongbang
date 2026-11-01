package kr.routinegongbang.app.pleos

import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.MirrorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.MirrorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.DrivingModeType
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.AmbientLightBrightnessListener
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.DrivingModeStateListener
import ai.pleos.playground.vehicle.listener.HvacAcStateListener
import ai.pleos.playground.vehicle.listener.HvacAirCleanerStateListener
import ai.pleos.playground.vehicle.listener.HvacAutoStateListener
import ai.pleos.playground.vehicle.listener.HvacDualStateListener
import ai.pleos.playground.vehicle.listener.HvacFanSpeedListener
import ai.pleos.playground.vehicle.listener.HvacMaxAcStateListener
import ai.pleos.playground.vehicle.listener.HvacMaxDefrostStateListener
import ai.pleos.playground.vehicle.listener.HvacPowerStateListener
import ai.pleos.playground.vehicle.listener.HvacRecirculationStateListener
import ai.pleos.playground.vehicle.listener.HvacSeatTemperatureListener
import ai.pleos.playground.vehicle.listener.HvacSeatVentilationListener
import ai.pleos.playground.vehicle.listener.HvacSideMirrorHeatStateListener
import ai.pleos.playground.vehicle.listener.HvacTemperatureListener
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.pleos.playground.vehicle.listener.LowWasherFluidWaringListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import ai.pleos.playground.vehicle.listener.WindowLockStateListener
import kr.routinegongbang.core.model.SignalId
import kr.routinegongbang.core.model.SignalIds
import kr.routinegongbang.core.model.SignalValue

/** Phase 2: the comfort controls and readings added on top of the first, verified bindings. */
internal object PleosPhase2 {

    private val DRIVER = SeatValue.ROW_1_LEFT
    private val PASSENGER = SeatValue.ROW_1_RIGHT

    private fun bool(value: Boolean?): SignalValue? = value?.let { SignalValue.Bool(it) }
    private fun level(value: Int?): SignalValue? = value?.let { SignalValue.of(it) }
    private fun Emit.maybe(value: SignalValue?) = value?.let(this)

    val bindings: List<SignalBinding> by lazy {
        listOf(
            power(), ac(), auto(), autoRecirculation(), airCleaner(), maxAc(), maxDefrost(), dual(), fanSpeed(), temperaturePassenger(),
            seatHeat(SignalIds.SEAT_HEAT_PASSENGER, PASSENGER), seatHeat(SignalIds.SEAT_HEAT_REAR_LEFT, SeatValue.ROW_2_LEFT),
            seatHeat(SignalIds.SEAT_HEAT_REAR_RIGHT, SeatValue.ROW_2_RIGHT),
            seatVent(SignalIds.SEAT_VENT_DRIVER, DRIVER), seatVent(SignalIds.SEAT_VENT_PASSENGER, PASSENGER),
            mirrorHeat(), ambientLight(), ambientBrightness(),
            PleosBindings.occupancy(SignalIds.SEAT_OCCUPIED_REAR_LEFT, SeatValue.ROW_2_LEFT),
            PleosBindings.occupancy(SignalIds.SEAT_OCCUPIED_REAR_RIGHT, SeatValue.ROW_2_RIGHT),
            seatbelt(SignalIds.SEATBELT_DRIVER, DRIVER), seatbelt(SignalIds.SEATBELT_PASSENGER, PASSENGER),
            cabinTemperature(), washerFluid(), drivingMode(),
            PleosBindings.windowPosition(SignalIds.WINDOW_POSITION_PASSENGER, WindowValue.ROW_1_RIGHT),
            windowLock(), driverDoor(), odometer(), tripDistance(),
        )
    }

    // --- Climate switches: one seat-area list, a Boolean listener, a Boolean setter. ---

    private fun power() = property(
        SignalIds.HVAC_POWER, CLIMATE, CONTROL_CLIMATE,
        check = seatListCheck(DRIVER, exact = false) { v, ok, err -> v.getHVAC().checkHvacPowerCapabilities(ok, err) },
        area = seatOf(DRIVER),
        listen = { emit, failed, _ ->
            object : HvacPowerStateListener {
                override fun onHvacPowerStateUpdated(state: Boolean?) = emit.maybe(bool(state)) ?: Unit
                override fun onFailed(e: Exception) = failed(e)
            }
        },
        register = { v, a, l -> v.getHVAC().registerHvacPowerState(a, l) },
        unregister = { v, a, l -> v.getHVAC().unregisterHvacPowerState(a, l) },
        current = { v, a, l, f -> v.getHVAC().getHvacPowerState(a, l::onHvacPowerStateUpdated, f) },
        write = boolWrite(SignalIds.HVAC_POWER) { v, a, on, done -> v.getHVAC().setHvacPower(a, on, accepted(done), failed(done)) },
    )

    private fun ac() = property(
        SignalIds.HVAC_AC, CLIMATE, CONTROL_CLIMATE,
        check = seatListCheck(DRIVER, exact = false) { v, ok, err -> v.getHVAC().checkHvacAcCapabilities(ok, err) },
        area = seatOf(DRIVER),
        listen = { emit, failed, _ ->
            object : HvacAcStateListener {
                override fun onHvacAcStateUpdated(state: Boolean?) = emit.maybe(bool(state)) ?: Unit
                override fun onFailed(e: Exception) = failed(e)
            }
        },
        register = { v, a, l -> v.getHVAC().registerHvacAcState(a, l) },
        unregister = { v, a, l -> v.getHVAC().unregisterHvacAcState(a, l) },
        current = { v, a, l, f -> v.getHVAC().getHvacAcState(a, l::onHvacAcStateUpdated, f) },
        write = boolWrite(SignalIds.HVAC_AC) { v, a, on, done -> v.getHVAC().setHvacAc(a, on, accepted(done), failed(done)) },
    )

    private fun auto() = property(
        SignalIds.HVAC_AUTO, CLIMATE, CONTROL_CLIMATE,
        check = seatListCheck(DRIVER, exact = false) { v, ok, err -> v.getHVAC().checkHvacAutoCapabilities(ok, err) },
        area = seatOf(DRIVER),
        listen = { emit, failed, _ ->
            object : HvacAutoStateListener {
                override fun onHvacAutoStateUpdated(state: Boolean?) = emit.maybe(bool(state)) ?: Unit
                override fun onFailed(e: Exception) = failed(e)
            }
        },
        register = { v, a, l -> v.getHVAC().registerHvacAutoState(a, l) },
        unregister = { v, a, l -> v.getHVAC().unregisterHvacAutoState(a, l) },
        current = { v, a, l, f -> v.getHVAC().getHvacAutoState(a, l::onHvacAutoStateUpdated, f) },
        write = boolWrite(SignalIds.HVAC_AUTO) { v, a, on, done -> v.getHVAC().setHvacAuto(a, on, accepted(done), failed(done)) },
    )

    /** The SDK reports automatic recirculation through the recirculation listener's own callback name. */
    private fun autoRecirculation() = property(
        SignalIds.HVAC_AUTO_RECIRCULATION, CLIMATE, CONTROL_CLIMATE,
        check = seatListCheck(DRIVER, exact = false) { v, ok, err -> v.getHVAC().checkHvacAutoRecirculationCapabilities(ok, err) },
        area = seatOf(DRIVER),
        listen = { emit, failed, _ ->
            object : HvacRecirculationStateListener {
                override fun onHvacAutoRecirculationStateUpdated(state: Boolean?) = emit.maybe(bool(state)) ?: Unit
                override fun onFailed(e: Exception) = failed(e)
            }
        },
        register = { v, a, l -> v.getHVAC().registerHvacAutoRecirculationState(a, l) },
        unregister = { v, a, l -> v.getHVAC().unregisterHvacAutoRecirculationState(a, l) },
        current = { v, a, l, f -> v.getHVAC().getHvacAutoRecirculationState(a, l::onHvacAutoRecirculationStateUpdated, f) },
        write = boolWrite(SignalIds.HVAC_AUTO_RECIRCULATION) { v, a, on, done -> v.getHVAC().setHvacAutoRecirculation(a, on, accepted(done), failed(done)) },
    )

    private fun airCleaner() = property(
        SignalIds.HVAC_AIR_CLEANER, CLIMATE, CONTROL_CLIMATE,
        check = seatListCheck(DRIVER, exact = false) { v, ok, err -> v.getHVAC().checkHvacAirCleanerCapabilities(ok, err) },
        area = seatOf(DRIVER),
        listen = { emit, failed, _ ->
            object : HvacAirCleanerStateListener {
                override fun onHvacAirCleanerStateUpdated(state: Boolean?) = emit.maybe(bool(state)) ?: Unit
                override fun onFailed(e: Exception) = failed(e)
            }
        },
        register = { v, a, l -> v.getHVAC().registerHvacAirCleanerState(a, l) },
        unregister = { v, a, l -> v.getHVAC().unregisterHvacAirCleanerState(a, l) },
        current = { v, a, l, f -> v.getHVAC().getHvacAirCleanerState(a, l::onHvacAirCleanerStateUpdated, f) },
        write = boolWrite(SignalIds.HVAC_AIR_CLEANER) { v, a, on, done -> v.getHVAC().setHvacAirCleaner(a, on, accepted(done), failed(done)) },
    )

    private fun maxAc() = property(
        SignalIds.HVAC_MAX_AC, CLIMATE, CONTROL_CLIMATE,
        check = seatListCheck(DRIVER, exact = false) { v, ok, err -> v.getHVAC().checkHvacMaxAcCapabilities(ok, err) },
        area = seatOf(DRIVER),
        listen = { emit, failed, _ ->
            object : HvacMaxAcStateListener {
                override fun onHvacMaxAcStateUpdated(state: Boolean?) = emit.maybe(bool(state)) ?: Unit
                override fun onFailed(e: Exception) = failed(e)
            }
        },
        register = { v, a, l -> v.getHVAC().registerHvacMaxAcState(a, l) },
        unregister = { v, a, l -> v.getHVAC().unregisterHvacMaxAcState(a, l) },
        current = { v, a, l, f -> v.getHVAC().getHvacMaxAcState(a, l::onHvacMaxAcStateUpdated, f) },
        write = boolWrite(SignalIds.HVAC_MAX_AC) { v, a, on, done -> v.getHVAC().setHvacMaxAc(a, on, accepted(done), failed(done)) },
    )

    private fun maxDefrost() = property(
        SignalIds.HVAC_MAX_DEFROST, CLIMATE, CONTROL_CLIMATE,
        check = seatListCheck(DRIVER, exact = false) { v, ok, err -> v.getHVAC().checkHvacMaxDefrostCapabilities(ok, err) },
        area = seatOf(DRIVER),
        listen = { emit, failed, _ ->
            object : HvacMaxDefrostStateListener {
                override fun onHvacMaxDefrostStateUpdated(state: Boolean?) = emit.maybe(bool(state)) ?: Unit
                override fun onFailed(e: Exception) = failed(e)
            }
        },
        register = { v, a, l -> v.getHVAC().registerHvacMaxDefrostState(a, l) },
        unregister = { v, a, l -> v.getHVAC().unregisterHvacMaxDefrostState(a, l) },
        current = { v, a, l, f -> v.getHVAC().getHvacMaxDefrostState(a, l::onHvacMaxDefrostStateUpdated, f) },
        write = boolWrite(SignalIds.HVAC_MAX_DEFROST) { v, a, on, done -> v.getHVAC().setHvacMaxDefrost(a, on, accepted(done), failed(done)) },
    )

    private fun dual() = property(
        SignalIds.HVAC_DUAL, CLIMATE, CONTROL_CLIMATE,
        check = seatListCheck(DRIVER, exact = false) { v, ok, err -> v.getHVAC().checkHvacDualCapabilities(ok, err) },
        area = seatOf(DRIVER),
        listen = { emit, failed, _ ->
            object : HvacDualStateListener {
                override fun onHvacDualStateUpdated(state: Boolean?) = emit.maybe(bool(state)) ?: Unit
                override fun onFailed(e: Exception) = failed(e)
            }
        },
        register = { v, a, l -> v.getHVAC().registerHvacDualState(a, l) },
        unregister = { v, a, l -> v.getHVAC().unregisterHvacDualState(a, l) },
        current = { v, a, l, f -> v.getHVAC().getHvacDualState(a, l::onHvacDualStateUpdated, f) },
        write = boolWrite(SignalIds.HVAC_DUAL) { v, a, on, done -> v.getHVAC().setHvacDual(a, on, accepted(done), failed(done)) },
    )

    // --- Levels and temperatures. ---

    private fun fanSpeed() = property(
        SignalIds.HVAC_FAN_SPEED, CLIMATE, CONTROL_CLIMATE,
        check = seatLevelCheck(DRIVER, exact = false) { v, ok, err -> v.getHVAC().checkHvacFanSpeedCapabilities(ok, err) },
        area = seatOf(DRIVER),
        listen = { emit, failed, _ ->
            object : HvacFanSpeedListener {
                override fun onHvacFanSpeedUpdated(speed: Int?) = emit.maybe(level(speed)) ?: Unit
                override fun onFailed(e: Exception) = failed(e)
            }
        },
        register = { v, a, l -> v.getHVAC().registerHvacFanSpeed(a, l) },
        unregister = { v, a, l -> v.getHVAC().unregisterHvacFanSpeed(a, l) },
        current = { v, a, l, f -> v.getHVAC().getHvacFanSpeed(a, l::onHvacFanSpeedUpdated, f) },
        write = levelWrite(SignalIds.HVAC_FAN_SPEED) { v, a, speed, done -> v.getHVAC().setHvacFanSpeed(a, speed, accepted(done), failed(done)) },
    )

    private fun temperaturePassenger() = property(
        SignalIds.HVAC_TEMP_PASSENGER, CLIMATE, CONTROL_CLIMATE,
        check = seatFloatCheck(PASSENGER) { v, ok, err -> v.getHVAC().checkHvacTemperatureCapabilities(ok, err) },
        area = seatOf(PASSENGER),
        listen = { emit, failed, _ ->
            object : HvacTemperatureListener {
                override fun onHvacTemperatureUpdated(temperature: Float?) = emit.maybe(temperature?.let { SignalValue.num(it.toDouble()) }) ?: Unit
                override fun onFailed(e: Exception) = failed(e)
            }
        },
        register = { v, a, l -> v.getHVAC().registerHvacTemperature(a, l) },
        unregister = { v, a, l -> v.getHVAC().unregisterHvacTemperature(a, l) },
        current = { v, a, l, f -> v.getHVAC().getHvacTemperature(a, TemperatureUnit.CELSIUS, l::onHvacTemperatureUpdated, f) },
        write = { v, a, value, _, done ->
            val celsius = (value as? SignalValue.Num)?.value?.toFloat()
            if (celsius == null) rejected(SignalIds.HVAC_TEMP_PASSENGER, value, done)
            else v.getHVAC().setHvacTemperature(a, celsius, TemperatureUnit.CELSIUS, accepted(done), failed(done))
        },
    )

    private fun seatHeat(signal: SignalId, seat: SeatValue) = property(
        signal, SEATS, CONTROL_SEATS,
        check = seatLevelCheck(seat, exact = true) { v, ok, err -> v.getSeat().checkSeatTemperatureCapabilities(ok, err) },
        area = seatOf(seat),
        listen = { emit, failed, _ ->
            object : HvacSeatTemperatureListener {
                override fun onHvacSeatTemperatureUpdated(temperature: Int?) = emit.maybe(level(temperature)) ?: Unit
                override fun onFailed(e: Exception) = failed(e)
            }
        },
        register = { v, a, l -> v.getSeat().registerHvacSeatTemperature(a, l) },
        unregister = { v, a, l -> v.getSeat().unregisterHvacSeatTemperature(a, l) },
        current = { v, a, l, f -> v.getSeat().getHvacSeatTemperature(a, l::onHvacSeatTemperatureUpdated, f) },
        write = levelWrite(signal) { v, a, heat, done -> v.getSeat().setHvacSeatTemperature(a, heat, accepted(done), failed(done)) },
    )

    private fun seatVent(signal: SignalId, seat: SeatValue) = property(
        signal, SEATS, CONTROL_SEATS,
        check = seatLevelCheck(seat, exact = true) { v, ok, err -> v.getSeat().checkSeatVentilationCapabilities(ok, err) },
        area = seatOf(seat),
        listen = { emit, failed, _ ->
            object : HvacSeatVentilationListener {
                override fun onHvacSeatVentilationUpdated(ventilation: Int?) = emit.maybe(level(ventilation)) ?: Unit
                override fun onFailed(e: Exception) = failed(e)
            }
        },
        register = { v, a, l -> v.getSeat().registerHvacSeatVentilation(a, l) },
        unregister = { v, a, l -> v.getSeat().unregisterHvacSeatVentilation(a, l) },
        current = { v, a, l, f -> v.getSeat().getHvacSeatVentilationState(a, l::onHvacSeatVentilationUpdated, f) },
        write = levelWrite(signal) { v, a, vent, done -> v.getSeat().setHvacSeatVentilation(a, vent, accepted(done), failed(done)) },
    )

    /** Both outside mirrors heat together, through the area that covers both. */
    private fun mirrorHeat() = property(
        SignalIds.MIRROR_HEAT, MIRRORS, CONTROL_MIRRORS,
        check = { v, done ->
            v.getSideMirror().checkHvacSideMirrorHeatCapabilities(
                { cfg, areas: Map<MirrorArea, AreaBounds<Int>> -> done(probe(cfg.accessType.toAccess(), bothMirrors(areas.keys), required = true)) },
                { done(Probe(null)) },
            )
        },
        area = { p -> p.area as? MirrorArea ?: MirrorArea(MirrorValue.DRIVER_LEFT, MirrorValue.DRIVER_RIGHT) },
        listen = { emit, failed, _ ->
            object : HvacSideMirrorHeatStateListener {
                override fun onHvacSideMirrorHeatStateUpdated(state: Int?) = emit.maybe(level(state)) ?: Unit
                override fun onFailed(e: Exception) = failed(e)
            }
        },
        register = { v, a, l -> v.getSideMirror().registerHvacSideMirrorHeatState(a, l) },
        unregister = { v, a, l -> v.getSideMirror().unregisterHvacSideMirrorHeatState(a, l) },
        current = { v, a, l, f -> v.getSideMirror().getHvacSideMirrorHeatState(a, l::onHvacSideMirrorHeatStateUpdated, f) },
        write = levelWrite(SignalIds.MIRROR_HEAT) { v, a, heat, done -> v.getSideMirror().setHvacSideMirrorHeat(a, heat, accepted(done), failed(done)) },
    )

    // --- Ambient light: on/off through LightSwitch, brightness in the car's own steps. ---

    private fun ambientLight() = property(
        SignalIds.AMBIENT_LIGHT, LIGHTS, CONTROL_LIGHTS,
        check = { v, done ->
            v.getLight().checkAmbientLightSwitchCapabilities(
                { cfg, areas: Map<SeatArea, List<LightSwitch>> -> done(probe(cfg.accessType.toAccess(), ambientArea(areas, DRIVER), required = true)) },
                { done(Probe(null)) },
            )
        },
        area = seatOf(DRIVER),
        listen = { emit, failed, _ ->
            object : LightStateListener {
                override fun onLightStateUpdated(state: LightState?) = emit.maybe(lightOn(state)) ?: Unit
                override fun onFailed(e: Exception) = failed(e)
            }
        },
        register = { v, a, l -> v.getLight().registerAmbientLightStateListener(a, l) },
        unregister = { v, a, l -> v.getLight().unregisterAmbientLightStateListener(a, l) },
        current = { v, a, l, f -> v.getLight().getAmbientLightState(a, l::onLightStateUpdated, f) },
        write = boolWrite(SignalIds.AMBIENT_LIGHT) { v, a, on, done ->
            v.getLight().getAmbientLightSwitch(a, { now ->
                if (switchable(now)) v.getLight().setAmbientLightSwitch(a, if (on) LightSwitch.ON else LightSwitch.OFF, accepted(done), failed(done))
                else done(Result.failure(IllegalStateException("ambient light is $now: left to the driver")))
            }, failed(done))
        },
    )

    private fun ambientBrightness() = property(
        SignalIds.AMBIENT_BRIGHTNESS, LIGHTS, CONTROL_LIGHTS,
        check = seatLevelCheck(DRIVER, exact = false) { v, ok, err -> v.getLight().checkAmbientLightBrightnessCapabilities(ok, err) },
        area = seatOf(DRIVER),
        listen = { emit, failed, _ ->
            object : AmbientLightBrightnessListener {
                override fun onBrightnessUpdated(brightness: Int?) = emit.maybe(level(brightness)) ?: Unit
                override fun onFailed(e: Exception) = failed(e)
            }
        },
        register = { v, a, l -> v.getLight().registerAmbientLightBrightnessListener(a, l) },
        unregister = { v, a, l -> v.getLight().unregisterAmbientLightBrightnessListener(a, l) },
        current = { v, a, l, f -> v.getLight().getAmbientLightBrightness(a, l::onBrightnessUpdated, f) },
        write = levelWrite(SignalIds.AMBIENT_BRIGHTNESS) { v, a, raw, done -> v.getLight().setAmbientLightBrightness(a, raw, accepted(done), failed(done)) },
    )

    // --- Readings. ---

    /** Belts are per seat: like occupancy, subscribe on our own seat even if the capability lists a combined area. */
    private fun seatbelt(signal: SignalId, seat: SeatValue) = property(
        signal, SEATS,
        check = seatListCheck(seat, exact = false) { v, ok, err -> v.getSeat().checkSeatBeltBuckledCapabilities(ok, err) },
        area = { p -> (p.area as? SeatArea)?.takeIf { it.values.size == 1 && seat in it.values } ?: SeatArea(seat) },
        listen = { emit, failed, _ ->
            object : SeatBeltBuckledStateListener {
                override fun onSeatBeltBuckledStateUpdated(state: Boolean?) = emit.maybe(bool(state)) ?: Unit
                override fun onFailed(e: Exception) = failed(e)
            }
        },
        register = { v, a, l -> v.getSeat().registerSeatBeltBuckledState(a, l) },
        unregister = { v, a, l -> v.getSeat().unregisterSeatBeltBuckledState(a, l) },
        current = { v, a, l, f -> v.getSeat().getCurrentSeatBeltBuckledState(a, l::onSeatBeltBuckledStateUpdated, f) },
    )

    private fun cabinTemperature() = property(
        SignalIds.CABIN_TEMPERATURE, CLIMATE,
        check = seatListCheck(DRIVER, exact = false) { v, ok, err -> v.getHVAC().checkHvacTemperatureCurrentCapabilities(ok, err) },
        area = seatOf(DRIVER),
        listen = { emit, failed, _ ->
            object : HvacTemperatureListener {
                override fun onHvacTemperatureUpdated(temperature: Float?) = emit.maybe(temperature?.let { SignalValue.num(it.toDouble()) }) ?: Unit
                override fun onFailed(e: Exception) = failed(e)
            }
        },
        register = { v, a, l -> v.getHVAC().registerHvacTemperatureCurrent(a, l) },
        unregister = { v, a, l -> v.getHVAC().unregisterHvacTemperatureCurrent(a, l) },
        current = { v, a, l, f -> v.getHVAC().getHvacTemperatureCurrent(a, l::onHvacTemperatureUpdated, f) },
    )

    private fun washerFluid() = property(
        SignalIds.WASHER_FLUID_LOW, SAFETY,
        check = carCheck { v, ok, err -> v.getSafety().checkLowWasherFluidWarningStateCapabilities(ok, err) },
        area = { },
        listen = { emit, failed, _ ->
            object : LowWasherFluidWaringListener {
                override fun onLowWasherFluidWaringUpdated(state: Boolean?) = emit.maybe(bool(state)) ?: Unit
                override fun onFailed(e: Exception) = failed(e)
            }
        },
        register = { v, _, l -> v.getSafety().registerLowWasherFluidWaringState(l) },
        unregister = { v, _, l -> v.getSafety().unregisterLowWasherFluidWaringState(l) },
        current = { v, _, l, f -> v.getSafety().hasLowWasherFluidWarning(l::onLowWasherFluidWaringUpdated, f) },
    )

    private fun drivingMode() = property(
        SignalIds.DRIVING_MODE, DRIVE,
        check = carCheck { v, ok, err -> v.getDrivingMode().checkDrivingModeStateCapabilities(ok, err) },
        area = { },
        listen = { emit, failed, _ ->
            object : DrivingModeStateListener {
                override fun onDrivingModeStateUpdated(state: DrivingModeType?) = emit.maybe(state?.let { SignalValue.Text(it.name) }) ?: Unit
                override fun onFailed(e: Exception) = failed(e)
            }
        },
        register = { v, _, l -> v.getDrivingMode().registerDrivingModeState(l) },
        unregister = { v, _, l -> v.getDrivingMode().unregisterDrivingModeState(l) },
        current = { v, _, l, f -> v.getDrivingMode().getCurrentDrivingModeState(l::onDrivingModeStateUpdated, f) },
    )

    private fun windowLock() = property(
        SignalIds.WINDOW_LOCK, WINDOWS,
        check = { v, done ->
            v.getWindow().checkWindowLockCapabilities(
                { cfg, areas: List<WindowArea> -> done(probe(cfg.accessType.toAccess(), containingArea(areas) { WindowValue.ROW_1_LEFT in it.values }, required = false)) },
                { done(Probe(null)) },
            )
        },
        area = { p -> p.area as? WindowArea ?: WindowArea(WindowValue.ROW_1_LEFT) },
        listen = { emit, failed, _ ->
            object : WindowLockStateListener {
                override fun onWindowLockStateUpdated(state: Boolean?) = emit.maybe(bool(state)) ?: Unit
                override fun onFailed(e: Exception) = failed(e)
            }
        },
        register = { v, a, l -> v.getWindow().registerWindowLockState(a, l) },
        unregister = { v, a, l -> v.getWindow().unregisterWindowLockState(a, l) },
        current = { v, a, l, f -> v.getWindow().getCurrentWindowLockState(a, l::onWindowLockStateUpdated, f) },
    )

    /** Open or closed, from the door position and the closed end its capability reports. */
    private fun driverDoor() = property(
        SignalIds.DOOR_OPEN_DRIVER, DOORS,
        check = { v, done ->
            v.getDoor().checkDoorPositionCapabilities(
                { cfg, areas: Map<DoorArea, AreaBounds<Int>> ->
                    val area = exactArea(areas.keys, { it.values }, DoorValue.ROW_1_LEFT)
                    done(probe(cfg.accessType.toAccess(), area, required = true, scale = percentScale(area?.let { areas[it] })))
                },
                { done(Probe(null)) },
            )
        },
        area = { p -> p.area as? DoorArea ?: DoorArea(DoorValue.ROW_1_LEFT) },
        listen = { emit, failed, probe ->
            object : DoorPositionListener {
                override fun onPositionUpdated(pos: Int?) = emit.maybe(doorOpen(pos, probe.scale?.let { AreaBounds(it.min, it.max) })) ?: Unit
                override fun onFailed(e: Exception) = failed(e)
            }
        },
        register = { v, a, l -> v.getDoor().registerDoorPosition(a, l) },
        unregister = { v, a, l -> v.getDoor().unregisterDoorPosition(a, l) },
        current = { v, a, l, f -> v.getDoor().getCurrentDoorPosition(a, l::onPositionUpdated, f) },
    )

    private fun odometer() = property(
        SignalIds.ODOMETER, DRIVE,
        check = carCheck { v, ok, err -> v.getOdometer().checkCurrentDistanceDrivenCapabilities(ok, err) },
        area = { },
        listen = { emit, failed, _ -> distance(emit, failed) },
        register = { v, _, l -> v.getOdometer().registerDistanceDriven(l) },
        unregister = { v, _, l -> v.getOdometer().unregisterDistanceDriven(l) },
        current = { v, _, l, f -> v.getOdometer().getCurrentDistanceDriven(l::onDistanceDrivenUpdated, f) },
    )

    private fun tripDistance() = property(
        SignalIds.TRIP_DISTANCE, DRIVE,
        check = carCheck { v, ok, err -> v.getOdometer().checkTripCurrentDrivingDistanceCapabilities(ok, err) },
        area = { },
        listen = { emit, failed, _ -> distance(emit, failed, ::kilometres) },
        register = { v, _, l -> v.getOdometer().registerTripCurrentDrivingDistance(l) },
        unregister = { v, _, l -> v.getOdometer().unregisterTripCurrentDrivingDistance(l) },
        current = { v, _, l, f -> v.getOdometer().getTripCurrentDrivingDistance(l::onDistanceDrivenUpdated, f) },
    )

    private fun distance(emit: Emit, failed: Failed, convert: (Float?) -> SignalValue? = { it?.let { km -> SignalValue.num(km.toDouble()) } }) =
        object : DistanceDrivenListener {
            override fun onDistanceDrivenUpdated(value: Float?) = emit.maybe(convert(value)) ?: Unit
            override fun onFailed(e: Exception) = failed(e)
        }
}
