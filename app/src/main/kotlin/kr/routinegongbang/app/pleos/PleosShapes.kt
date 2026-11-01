package kr.routinegongbang.app.pleos

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.MirrorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.MirrorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import kr.routinegongbang.core.engine.Access
import kr.routinegongbang.core.model.SignalId
import kr.routinegongbang.core.model.SignalValue
import kr.routinegongbang.core.platform.PercentScale

// The shapes Pleos SDK properties come in. Each property still names its own listener, because
// every SDK listener is a two-method interface; everything around it — areas, capability callbacks, value
// conversion, write callbacks — is shared here.

internal const val CLIMATE = "pleos.car.permission.CAR_CLIMATE"
internal const val CONTROL_CLIMATE = "pleos.car.permission.CONTROL_CAR_CLIMATE"
internal const val SEATS = "pleos.car.permission.CAR_SEATS"
internal const val CONTROL_SEATS = "pleos.car.permission.CONTROL_CAR_SEATS"
internal const val WINDOWS = "pleos.car.permission.CAR_WINDOWS"
internal const val DOORS = "pleos.car.permission.CAR_DOORS"
internal const val ENERGY = "pleos.car.permission.CAR_ENERGY"
internal const val LIGHTS = "pleos.car.permission.CAR_LIGHTS"
internal const val CONTROL_LIGHTS = "pleos.car.permission.CONTROL_CAR_LIGHTS"
internal const val MIRRORS = "pleos.car.permission.CAR_MIRRORS"
internal const val CONTROL_MIRRORS = "pleos.car.permission.CONTROL_CAR_MIRRORS"
internal const val SAFETY = "pleos.car.permission.CAR_SAFETY"
internal const val DRIVE = "pleos.car.permission.CAR_DRIVE"

internal typealias Check = (Vehicle, (Probe) -> Unit) -> Unit

/** Exactly our seat for per-seat settings; for whole-car settings any area containing it, never another seat's. */
internal fun seatArea(areas: Collection<SeatArea>, seat: SeatValue, exact: Boolean): SeatArea? =
    if (exact) exactArea(areas, { it.values }, seat) else areas.firstOrNull { seat in it.values }

/** Both outside mirrors heat together: the area covering both, else the only one the car has. */
internal fun bothMirrors(areas: Collection<MirrorArea>): MirrorArea? =
    areas.firstOrNull { MirrorValue.DRIVER_LEFT in it.values && MirrorValue.DRIVER_RIGHT in it.values } ?: areas.firstOrNull()

/** The SDK reports the current trip in metres, the odometer in kilometres. */
internal fun kilometres(metres: Float?): SignalValue? = metres?.let { SignalValue.num(Math.round(it.toDouble()) / 1000.0) }

/** The ambient light area this app may switch: one that offers both ON and OFF. */
internal fun ambientArea(areas: Map<SeatArea, List<LightSwitch>>, seat: SeatValue): SeatArea? =
    seatArea(areas.filterValues { LightSwitch.ON in it && LightSwitch.OFF in it }.keys, seat, exact = false)

/** A switch the driver left on AUTOMATIC (or daytime running) is theirs; only plain on/off is switched. */
internal fun switchable(now: LightSwitch?): Boolean = now == LightSwitch.ON || now == LightSwitch.OFF

/** Bounds that span nothing give no safe percent conversion. */
internal fun percentScale(bounds: AreaBounds<Int>?): PercentScale? =
    bounds?.takeIf { it.maxValue > it.minValue }?.let { PercentScale(it.minValue, it.maxValue) }

/** A [required] area that was not found means the signal is unusable, whatever the access says. */
internal fun probe(access: Access, area: Any?, required: Boolean, scale: PercentScale? = null): Probe =
    Probe(if (required && area == null) Access.NONE else access, area, scale)

internal fun lightOn(state: LightState?): SignalValue? = state?.let { SignalValue.Bool(it == LightState.ON) }

/** A door counts as open as soon as it leaves its closed end. */
internal fun doorOpen(position: Int?, bounds: AreaBounds<Int>?): SignalValue? =
    position?.let { SignalValue.Bool(it > (bounds?.minValue ?: 0)) }

/** Capability with a list of seat areas (most climate switches). */
internal fun seatListCheck(
    seat: SeatValue,
    exact: Boolean,
    call: (Vehicle, (CapabilityConfig, List<SeatArea>) -> Unit, (Exception) -> Unit) -> Unit,
): Check = { v, done -> call(v, { cfg, areas -> done(probe(cfg.accessType.toAccess(), seatArea(areas, seat, exact), exact)) }, { done(Probe(null)) }) }

/** Capability with integer bounds per seat area (levels in the car's own steps). */
internal fun seatLevelCheck(
    seat: SeatValue,
    exact: Boolean,
    call: (Vehicle, (CapabilityConfig, Map<SeatArea, AreaBounds<Int>>) -> Unit, (Exception) -> Unit) -> Unit,
): Check = { v, done -> call(v, { cfg, areas -> done(probe(cfg.accessType.toAccess(), seatArea(areas.keys, seat, exact), exact)) }, { done(Probe(null)) }) }

/** Capability with float bounds per seat area (temperatures). */
internal fun seatFloatCheck(
    seat: SeatValue,
    call: (Vehicle, (CapabilityConfig, Map<SeatArea, AreaBounds<Float>>) -> Unit, (Exception) -> Unit) -> Unit,
): Check = { v, done -> call(v, { cfg, areas -> done(probe(cfg.accessType.toAccess(), seatArea(areas.keys, seat, exact = true), required = true)) }, { done(Probe(null)) }) }

/** Capability of a whole-car property without areas. */
internal fun carCheck(call: (Vehicle, (CapabilityConfig) -> Unit, (Exception) -> Unit) -> Unit): Check =
    { v, done -> call(v, { cfg -> done(Probe(cfg.accessType.toAccess())) }, { done(Probe(null)) }) }

/** The area found by the check, or our own seat. */
internal fun seatOf(seat: SeatValue): (Probe) -> SeatArea = { it.area as? SeatArea ?: SeatArea(seat) }

/**
 * The Pleos service does not always send a new subscriber the current value, so after registering
 * the listener the value is also read once, through a second listener so the conversion is the same. The read is
 * only a first value: once the subscription has spoken, or after unsubscribing, its answer is stale and dropped —
 * otherwise an old value would arrive after a newer one and look like the user changing it back.
 */
internal fun <L : Any> subscribeWithRead(
    emit: Emit,
    failed: Failed,
    listen: (Emit) -> L,
    register: (L) -> Unit,
    unregister: (L) -> Unit,
    read: ((L) -> Unit)?,
): () -> Unit {
    val lock = Any()
    var heard = false
    var closed = false
    // Both paths emit under the lock, so a read answer can never slip in between a live event and its emit; after
    // unsubscribing, a callback already on its way reaches nothing.
    val live = listen { value ->
        synchronized(lock) {
            heard = true
            if (!closed) emit(value)
        }
    }
    register(live)
    try {
        read?.invoke(listen { value -> synchronized(lock) { if (!heard && !closed) emit(value) } })
    } catch (e: RuntimeException) {
        // A throwing getter must not lose the unsubscriber of the listener registered just before.
        failed(e)
    }
    return {
        synchronized(lock) { closed = true }
        unregister(live)
    }
}

/**
 * One property: the check, how to pick its area, its listener, how to read its current value, and optionally how
 * to write it. The listener is built per subscription so unsubscribing removes exactly what was registered.
 */
internal fun <A : Any, L : Any> property(
    signal: SignalId,
    read: String,
    control: String? = null,
    check: Check,
    area: (Probe) -> A,
    listen: (Emit, Failed, Probe) -> L,
    register: (Vehicle, A, L) -> Unit,
    unregister: (Vehicle, A, L) -> Unit,
    current: (Vehicle, A, L, Failed) -> Unit,
    write: ((Vehicle, A, SignalValue, Probe, Done) -> Unit)? = null,
): SignalBinding = SignalBinding(
    signal, read, control, check,
    subscribe = { v, p, emit, failed ->
        val a = area(p)
        subscribeWithRead(
            emit, failed,
            listen = { e -> listen(e, failed, p) },
            register = { l -> register(v, a, l) },
            unregister = { l -> unregister(v, a, l) },
            read = { l -> current(v, a, l, failed) },
        )
    },
    write = write?.let { w -> { v, p, value, done -> w(v, area(p), value, p, done) } },
    reads = true,
)

/** Write callbacks of the SDK report (area, value); either way the write was accepted. */
internal fun <X, Y> accepted(done: Done): (X, Y) -> Unit = { _, _ -> done(Result.success(Unit)) }
internal fun failed(done: Done): Failed = { done(Result.failure(it)) }

internal fun <A> boolWrite(signal: SignalId, set: (Vehicle, A, Boolean, Done) -> Unit): (Vehicle, A, SignalValue, Probe, Done) -> Unit =
    { v, a, value, _, done -> (value as? SignalValue.Bool)?.value?.let { set(v, a, it, done) } ?: rejected(signal, value, done) }

internal fun <A> levelWrite(signal: SignalId, set: (Vehicle, A, Int, Done) -> Unit): (Vehicle, A, SignalValue, Probe, Done) -> Unit =
    { v, a, value, _, done -> (value as? SignalValue.Num)?.value?.toInt()?.let { set(v, a, it, done) } ?: rejected(signal, value, done) }

internal fun rejected(signal: SignalId, value: SignalValue, done: Done) =
    done(Result.failure(IllegalArgumentException("${signal.value} cannot take $value")))
