package kr.routinegongbang.app.pleos

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.DoorLockStateListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.HvacDefrosterStateListener
import ai.pleos.playground.vehicle.listener.HvacRecirculationStateListener
import ai.pleos.playground.vehicle.listener.HvacSeatTemperatureListener
import ai.pleos.playground.vehicle.listener.HvacSteeringWheelHeatStateListener
import ai.pleos.playground.vehicle.listener.HvacTemperatureListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.WindowPositionListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import kr.routinegongbang.core.engine.Access
import kr.routinegongbang.core.model.SignalId
import kr.routinegongbang.core.model.SignalIds
import kr.routinegongbang.core.model.SignalValue
import kr.routinegongbang.core.platform.PercentScale

/** Result of a capability check: the access level, the SDK area to use, and an optional unit scale. */
internal data class Probe(val access: Access?, val area: Any? = null, val scale: PercentScale? = null)

internal typealias Emit = (SignalValue) -> Unit
internal typealias Failed = (Exception) -> Unit
internal typealias Done = (Result<Unit>) -> Unit

/**
 * How one catalog signal maps onto the Pleos Vehicle SDK. Values passed to [subscribe] and [write] are raw
 * SDK values; catalog corrections (inverted seat occupancy) are applied by [PleosVehicle] in one place.
 * [check] must call back exactly once. [write] is null when this app never writes the signal yet.
 */
internal class SignalBinding(
    val signal: SignalId,
    val readPermission: String,
    val writePermission: String? = null,
    val check: (Vehicle, (Probe) -> Unit) -> Unit,
    val subscribe: (Vehicle, Probe, Emit, Failed) -> () -> Unit,
    val write: ((Vehicle, Probe, SignalValue, Done) -> Unit)? = null,
    /** Also reads the current value when subscribing ([subscribeWithRead]). */
    val reads: Boolean = false,
)

internal fun AccessType.toAccess(): Access = when (this) {
    AccessType.NONE -> Access.NONE
    AccessType.READ -> Access.READ
    AccessType.WRITE -> Access.WRITE
    AccessType.READ_WRITE -> Access.READ_WRITE
}

/**
 * Some properties report only on a combined area. For whole-car signals (door lock, recirculation,
 * wiper) the reported area containing our position is the right one.
 */
internal fun <A : Any> containingArea(areas: Collection<A>, contains: (A) -> Boolean): A? = areas.firstOrNull(contains) ?: areas.firstOrNull()

/**
 * For per-seat or per-window signals only an area that is exactly our position is usable: a combined area would
 * write to other occupants and stamp their changes as the driver's. Null means "not usable" (access NONE).
 */
internal fun <A : Any> exactArea(areas: Collection<A>, values: (A) -> Array<out Any>, wanted: Any): A? =
    areas.firstOrNull { values(it).size == 1 && values(it)[0] == wanted }

private fun seatArea(probe: Probe) = probe.area as? SeatArea ?: SeatArea(SeatValue.ROW_1_LEFT)
private fun windowArea(probe: Probe, fallback: WindowValue) = probe.area as? WindowArea ?: WindowArea(fallback)

private fun Int.num(): SignalValue = SignalValue.of(this)
private fun SignalValue.asBool(): Boolean? = (this as? SignalValue.Bool)?.value
private fun SignalValue.asInt(): Int? = (this as? SignalValue.Num)?.value?.toInt()
private fun SignalValue.asFloat(): Float? = (this as? SignalValue.Num)?.value?.toFloat()

private fun ok(done: Done): (Any?) -> Unit = { done(Result.success(Unit)) }
private fun fail(done: Done): Failed = { done(Result.failure(it)) }
private fun badValue(signal: SignalId, value: SignalValue, done: Done) =
    done(Result.failure(IllegalArgumentException("${signal.value} cannot take $value")))


/** Bindings for every catalog signal served by the Pleos Vehicle SDK. AAOS-only signals live in AaosVehicleProperties. */
internal object PleosBindings {

    val all: List<SignalBinding> by lazy {
        listOf(wiperState(), defroster(), recirculation(), temperatureDriver(), steeringHeat(), seatHeatDriver()) +
            listOf(occupancy(SignalIds.SEAT_OCCUPIED_DRIVER, SeatValue.ROW_1_LEFT), occupancy(SignalIds.SEAT_OCCUPIED_PASSENGER, SeatValue.ROW_1_RIGHT)) +
            listOf(doorLock(), windowPosition(SignalIds.WINDOW_POSITION_DRIVER, WindowValue.ROW_1_LEFT), soc()) +
            PleosPhase2.bindings
    }

    private fun wiperState() = SignalBinding(
        SignalIds.WIPER_FRONT_STATE, WINDOWS,
        check = { v, done ->
            v.getWiper().checkWiperStateCapabilities(
                { cfg, areas -> done(Probe(cfg.accessType.toAccess(), containingArea(areas.keys) { WindowValue.FRONT_WINDSHIELD in it.values })) },
                { done(Probe(null)) },
            )
        },
        reads = true,
        subscribe = { v, probe, emit, failed ->
            val area = windowArea(probe, WindowValue.FRONT_WINDSHIELD)
            fun listener(out: Emit) = object : WiperStateListener {
                override fun onWiperStateUpdated(state: WiperState?) { state?.let { out(SignalValue.Text(it.name)) } }
                override fun onFailed(e: Exception) = failed(e)
            }
            subscribeWithRead(
                emit, failed, ::listener,
                register = { v.getWiper().registerWiperState(area, it) },
                unregister = { v.getWiper().unregisterWiperState(area, it) },
                read = { v.getWiper().getCurrentWiperState(area, it::onWiperStateUpdated, failed) },
            )
        },
    )

    private fun defroster() = SignalBinding(
        SignalIds.DEFROSTER_FRONT, CLIMATE, CONTROL_CLIMATE,
        check = { v, done ->
            v.getHVAC().checkHvacDefrosterCapabilities(
                { cfg, areas ->
                    val area = exactArea(areas, { it.values }, WindowValue.FRONT_WINDSHIELD)
                    done(Probe(if (area != null) cfg.accessType.toAccess() else Access.NONE, area))
                },
                { done(Probe(null)) },
            )
        },
        reads = true,
        subscribe = { v, probe, emit, failed ->
            val area = windowArea(probe, WindowValue.FRONT_WINDSHIELD)
            fun listener(out: Emit) = object : HvacDefrosterStateListener {
                override fun onHvacDefrosterStateUpdated(state: Boolean?) { state?.let { out(SignalValue.Bool(it)) } }
                override fun onFailed(e: Exception) = failed(e)
            }
            subscribeWithRead(
                emit, failed, ::listener,
                register = { v.getHVAC().registerHvacDefrosterState(area, it) },
                unregister = { v.getHVAC().unregisterHvacDefrosterState(area, it) },
                read = { v.getHVAC().getHvacDefrosterState(area, it::onHvacDefrosterStateUpdated, failed) },
            )
        },
        write = { v, probe, value, done ->
            val on = value.asBool() ?: return@SignalBinding badValue(SignalIds.DEFROSTER_FRONT, value, done)
            v.getHVAC().setHvacDefroster(windowArea(probe, WindowValue.FRONT_WINDSHIELD), on, { _, _ -> done(Result.success(Unit)) }, fail(done))
        },
    )

    private fun recirculation() = SignalBinding(
        SignalIds.RECIRCULATION, CLIMATE, CONTROL_CLIMATE,
        check = { v, done ->
            v.getHVAC().checkHvacRecirculationCapabilities(
                { cfg, areas -> done(Probe(cfg.accessType.toAccess(), containingArea(areas) { SeatValue.ROW_1_LEFT in it.values })) },
                { done(Probe(null)) },
            )
        },
        reads = true,
        subscribe = { v, probe, emit, failed ->
            val area = seatArea(probe)
            fun listener(out: Emit) = object : HvacRecirculationStateListener {
                override fun onHvacAutoRecirculationStateUpdated(state: Boolean?) { state?.let { out(SignalValue.Bool(it)) } }
                override fun onFailed(e: Exception) = failed(e)
            }
            subscribeWithRead(
                emit, failed, ::listener,
                register = { v.getHVAC().registerHvacRecirculationState(area, it) },
                unregister = { v.getHVAC().unregisterHvacRecirculationState(area, it) },
                read = { v.getHVAC().getHvacRecirculationState(area, it::onHvacAutoRecirculationStateUpdated, failed) },
            )
        },
        write = { v, probe, value, done ->
            val on = value.asBool() ?: return@SignalBinding badValue(SignalIds.RECIRCULATION, value, done)
            v.getHVAC().setHvacRecirculation(seatArea(probe), on, { _, _ -> done(Result.success(Unit)) }, fail(done))
        },
    )

    private fun temperatureDriver() = SignalBinding(
        SignalIds.HVAC_TEMP_DRIVER, CLIMATE, CONTROL_CLIMATE,
        check = { v, done ->
            v.getHVAC().checkHvacTemperatureCapabilities(
                { cfg: ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig, areas: Map<SeatArea, ai.pleos.playground.vehicle.api.model.capability.AreaBounds<Float>> ->
                    val area = exactArea(areas.keys, { it.values }, SeatValue.ROW_1_LEFT)
                    done(Probe(if (area != null) cfg.accessType.toAccess() else Access.NONE, area))
                },
                { done(Probe(null)) },
            )
        },
        reads = true,
        subscribe = { v, probe, emit, failed ->
            val area = seatArea(probe)
            fun listener(out: Emit) = object : HvacTemperatureListener {
                override fun onHvacTemperatureUpdated(temperature: Float?) { temperature?.let { out(SignalValue.num(it.toDouble())) } }
                override fun onFailed(e: Exception) = failed(e)
            }
            subscribeWithRead(
                emit, failed, ::listener,
                register = { v.getHVAC().registerHvacTemperature(area, it) },
                unregister = { v.getHVAC().unregisterHvacTemperature(area, it) },
                read = { v.getHVAC().getHvacTemperature(area, TemperatureUnit.CELSIUS, it::onHvacTemperatureUpdated, failed) },
            )
        },
        write = { v, probe, value, done ->
            val celsius = value.asFloat() ?: return@SignalBinding badValue(SignalIds.HVAC_TEMP_DRIVER, value, done)
            v.getHVAC().setHvacTemperature(seatArea(probe), celsius, TemperatureUnit.CELSIUS, { _, _ -> done(Result.success(Unit)) }, fail(done))
        },
    )

    private fun steeringHeat() = SignalBinding(
        SignalIds.STEERING_WHEEL_HEAT, CLIMATE, CONTROL_CLIMATE,
        check = { v, done ->
            v.getSteeringWheel().checkHvacSteeringWheelHeatCapabilities({ cfg, _ -> done(Probe(cfg.accessType.toAccess())) }, { done(Probe(null)) })
        },
        reads = true,
        subscribe = { v, _, emit, failed ->
            fun listener(out: Emit) = object : HvacSteeringWheelHeatStateListener {
                override fun onHvacSteeringWheelHeatStateUpdated(state: Int?) { state?.let { out(it.num()) } }
                override fun onFailed(e: Exception) = failed(e)
            }
            subscribeWithRead(
                emit, failed, ::listener,
                register = { v.getSteeringWheel().registerHvacSteeringWheelHeatState(it) },
                unregister = { v.getSteeringWheel().unregisterHvacSteeringWheelHeatState(it) },
                read = { v.getSteeringWheel().getHvacSteeringWheelHeatState(it::onHvacSteeringWheelHeatStateUpdated, failed) },
            )
        },
        write = { v, _, value, done ->
            val level = value.asInt() ?: return@SignalBinding badValue(SignalIds.STEERING_WHEEL_HEAT, value, done)
            v.getSteeringWheel().setHvacSteeringWheelHeat(level, ok(done), fail(done))
        },
    )

    private fun seatHeatDriver() = SignalBinding(
        SignalIds.SEAT_HEAT_DRIVER, SEATS, CONTROL_SEATS,
        check = { v, done ->
            v.getSeat().checkSeatTemperatureCapabilities(
                { cfg, areas ->
                    val area = exactArea(areas.keys, { it.values }, SeatValue.ROW_1_LEFT)
                    done(Probe(if (area != null) cfg.accessType.toAccess() else Access.NONE, area))
                },
                { done(Probe(null)) },
            )
        },
        reads = true,
        subscribe = { v, probe, emit, failed ->
            val area = seatArea(probe)
            fun listener(out: Emit) = object : HvacSeatTemperatureListener {
                override fun onHvacSeatTemperatureUpdated(temperature: Int?) { temperature?.let { out(it.num()) } }
                override fun onFailed(e: Exception) = failed(e)
            }
            subscribeWithRead(
                emit, failed, ::listener,
                register = { v.getSeat().registerHvacSeatTemperature(area, it) },
                unregister = { v.getSeat().unregisterHvacSeatTemperature(area, it) },
                read = { v.getSeat().getHvacSeatTemperature(area, it::onHvacSeatTemperatureUpdated, failed) },
            )
        },
        write = { v, probe, value, done ->
            val level = value.asInt() ?: return@SignalBinding badValue(SignalIds.SEAT_HEAT_DRIVER, value, done)
            v.getSeat().setHvacSeatTemperature(seatArea(probe), level, { _, _ -> done(Result.success(Unit)) }, fail(done))
        },
    )

    internal fun occupancy(signal: SignalId, seat: SeatValue) = SignalBinding(
        signal, SEATS,
        check = { v, done ->
            v.getSeat().checkSeatOccupancyCapabilities(
                // A car without this seat's sensor reports nothing for it, never another seat's.
                { cfg, areas -> done(probe(cfg.accessType.toAccess(), seatArea(areas, seat, exact = false), required = true)) },
                { done(Probe(null)) },
            )
        },
        reads = true,
        subscribe = { v, probe, emit, failed ->
            // Subscribe on our own seat even if the capability lists a combined area: occupancy is per seat.
            val area = (probe.area as? SeatArea)?.takeIf { it.values.size == 1 && seat in it.values } ?: SeatArea(seat)
            fun listener(out: Emit) = object : SeatOccupancyListener {
                override fun onSeatOccupancyUpdated(occupancy: Boolean?) { occupancy?.let { out(SignalValue.Bool(it)) } }
                override fun onFailed(e: Exception) = failed(e)
            }
            subscribeWithRead(
                emit, failed, ::listener,
                register = { v.getSeat().registerSeatOccupancy(area, it) },
                unregister = { v.getSeat().unregisterSeatOccupancy(area, it) },
                read = { v.getSeat().getCurrentSeatOccupancy(area, it::onSeatOccupancyUpdated, failed) },
            )
        },
    )

    private fun doorLock() = SignalBinding(
        SignalIds.DOOR_LOCK, DOORS,
        check = { v, done ->
            v.getDoor().checkDoorLockCapabilities(
                { cfg, areas -> done(Probe(cfg.accessType.toAccess(), areas.firstOrNull { DoorValue.ROW_1_LEFT in it.values })) },
                { done(Probe(null)) },
            )
        },
        reads = true,
        subscribe = { v, probe, emit, failed ->
            // Only the combined four-door area reports lock events.
            val area = probe.area as? DoorArea ?: DoorArea(DoorValue.ROW_1_LEFT, DoorValue.ROW_1_RIGHT, DoorValue.ROW_2_LEFT, DoorValue.ROW_2_RIGHT)
            fun listener(out: Emit) = object : DoorLockStateListener {
                override fun onDoorLockStateUpdated(state: Boolean?) { state?.let { out(SignalValue.Bool(it)) } }
                override fun onFailed(e: Exception) = failed(e)
            }
            subscribeWithRead(
                emit, failed, ::listener,
                register = { v.getDoor().registerDoorLockState(area, it) },
                unregister = { v.getDoor().unregisterDoorLockState(area, it) },
                read = { v.getDoor().getDoorLockState(area, it::onDoorLockStateUpdated, failed) },
            )
        },
    )

    internal fun windowPosition(signal: SignalId, window: WindowValue) = SignalBinding(
        signal, WINDOWS,
        check = { v, done ->
            v.getWindow().checkWindowPositionCapabilities(
                { cfg, areas ->
                    val area = exactArea(areas.keys, { it.values }, window)
                    val bounds = area?.let { areas[it] }
                    val scale = bounds?.takeIf { it.maxValue > it.minValue }?.let { PercentScale(it.minValue, it.maxValue) }
                    // Without bounds there is no safe percent conversion: treat as unreadable.
                    done(Probe(if (area != null && scale != null) cfg.accessType.toAccess() else Access.NONE, area, scale))
                },
                { done(Probe(null)) },
            )
        },
        reads = true,
        subscribe = { v, probe, emit, failed ->
            val area = windowArea(probe, window)
            val scale = probe.scale
            fun listener(out: Emit) = object : WindowPositionListener {
                override fun onWindowPositionUpdated(position: Int?) {
                    // Without capability bounds there is no safe percent conversion, so the value is withheld.
                    if (position != null && scale != null) out(scale.toPercent(position))
                }
                override fun onFailed(e: Exception) = failed(e)
            }
            subscribeWithRead(
                emit, failed, ::listener,
                register = { v.getWindow().registerWindowPosition(area, it) },
                unregister = { v.getWindow().unregisterWindowPosition(area, it) },
                read = { v.getWindow().getCurrentWindowPosition(area, it::onWindowPositionUpdated, failed) },
            )
        },
    )

    private fun soc() = SignalBinding(
        SignalIds.EV_SOC, ENERGY,
        check = { v, done ->
            v.getEvBattery().checkEvBatteryStateOfChargeCapabilities({ cfg -> done(Probe(cfg.accessType.toAccess())) }, { done(Probe(null)) })
        },
        reads = true,
        subscribe = { v, _, emit, failed ->
            fun listener(out: Emit) = object : EvBatteryStateOfChargeListener {
                override fun onEvBatteryStateOfChargeUpdated(value: Int?) { value?.let { out(it.num()) } }
                override fun onFailed(e: Exception) = failed(e)
            }
            subscribeWithRead(
                emit, failed, ::listener,
                register = { v.getEvBattery().registerEvBatteryStateOfCharge(it) },
                unregister = { v.getEvBattery().unregisterEvBatteryStateOfCharge(it) },
                read = { v.getEvBattery().getEvBatteryStateOfCharge(it::onEvBatteryStateOfChargeUpdated, failed) },
            )
        },
    )
}
