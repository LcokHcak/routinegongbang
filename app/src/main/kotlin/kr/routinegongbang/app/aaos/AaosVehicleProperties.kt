package kr.routinegongbang.app.aaos

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.SystemClock
import kr.routinegongbang.core.engine.Access
import kr.routinegongbang.core.engine.DrivingState
import kr.routinegongbang.core.engine.EngineEvent
import kr.routinegongbang.core.model.SignalId
import kr.routinegongbang.core.model.SignalIds
import kr.routinegongbang.core.model.SignalValue
import kr.routinegongbang.core.platform.AaosGear
import kr.routinegongbang.core.platform.DrivingInputs
import kotlin.math.roundToInt

/**
 * AAOS properties: GEAR_SELECTION (the Pleos gear API is deprecated), PERF_VEHICLE_SPEED and
 * CarUxRestrictions decide "parked"; EV_CHARGE_STATE serves `ev.charging`, which the Pleos Vehicle SDK does
 * not expose. Any property that is missing or denied
 * stays unknown, which the engine treats as "not parked". Never throws to the caller.
 */
class AaosVehicleProperties(
    private val context: Context,
    private val log: (String) -> Unit,
) {
    private val lock = Any()
    @Volatile private var car: Car? = null
    @Volatile private var properties: CarPropertyManager? = null
    @Volatile private var ux: CarUxRestrictionsManager? = null
    @Volatile private var stopped = false
    private var inputs = DrivingInputs()
    private var lastSpeedKph: Int? = null
    private var lastDriving: DrivingState? = null
    @Volatile private var post: (EngineEvent) -> Unit = {}
    private val access = mutableMapOf<SignalId, Access>()

    /** Signals this adapter serves, with the access found at [start]. */
    fun capabilities(): Map<SignalId, Access> = synchronized(lock) { access.toMap() }

    fun current(): DrivingState = synchronized(lock) { inputs.state(SystemClock.elapsedRealtime()) }

    /** Connects and subscribes; every change is passed to [sink] as engine events. */
    fun start(sink: (EngineEvent) -> Unit) {
        post = sink
        val connected = try {
            Car.createCar(context)
        } catch (e: RuntimeException) {
            null.also { log("aaos car service unavailable: ${e.message}") }
        } ?: return
        if (stopped) return run { connected.disconnect() }
        car = connected
        val props = connected.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
        properties = props
        subscribe(props, VehiclePropertyIds.GEAR_SELECTION, SignalIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
        subscribe(props, VehiclePropertyIds.PERF_VEHICLE_SPEED, SignalIds.VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)
        subscribe(props, VehiclePropertyIds.EV_CHARGE_STATE, SignalIds.EV_CHARGING, CarPropertyManager.SENSOR_RATE_ONCHANGE)
        startUxRestrictions(connected)
        readNow(props)
    }

    /**
     * Called on every engine tick. Continuous properties may not send events while the value is unchanged,
     * so speed is read actively; a dead stream then still shows up as staleness (not parked).
     */
    fun refresh() {
        readNow(properties)
        publishDriving()
    }

    fun stop() {
        stopped = true
        runCatching { properties?.unregisterCallback(callback) }
        runCatching { ux?.unregisterListener() }
        runCatching { car?.disconnect() }
        car = null
        properties = null
        ux = null
    }

    private fun subscribe(props: CarPropertyManager?, propertyId: Int, signal: SignalId, rate: Float) {
        val ok = try {
            props?.getCarPropertyConfig(propertyId) != null && props.registerCallback(callback, propertyId, rate)
        } catch (e: SecurityException) {
            log("aaos ${signal.value}: permission denied (${e.message})")
            false
        } catch (e: IllegalArgumentException) {
            log("aaos ${signal.value}: not supported (${e.message})")
            false
        }
        synchronized(lock) { access[signal] = if (ok) Access.READ else Access.NONE }
        log("aaos ${signal.value}: ${if (ok) "subscribed" else "unavailable"}")
    }

    /** Reads gear and speed directly; failures leave the last known (or unknown) value. */
    private fun readNow(props: CarPropertyManager?) {
        props ?: return
        for (id in listOf(VehiclePropertyIds.GEAR_SELECTION, VehiclePropertyIds.PERF_VEHICLE_SPEED)) {
            try {
                // Just read, so current now, whatever the sample time of an unchanged value says.
                props.getProperty<Any>(id, GLOBAL_AREA)?.let { onProperty(it, readAtMs = SystemClock.elapsedRealtime()) }
            } catch (e: RuntimeException) {
                // Not supported, denied or temporarily unavailable: keep the subscription value.
            }
        }
    }

    private fun startUxRestrictions(connected: Car) {
        try {
            val manager = connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager ?: return
            ux = manager
            manager.registerListener { onUx(it) }
            onUx(manager.currentCarUxRestrictions)
        } catch (e: RuntimeException) {
            log("aaos ux restrictions unavailable: ${e.message}")
        }
    }

    private fun onUx(restrictions: CarUxRestrictions?) {
        synchronized(lock) { inputs = inputs.withUxRestricted(restrictions?.isRequiresDistractionOptimization) }
        publishDriving()
    }

    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) = onProperty(value, readAtMs = null)
        override fun onErrorEvent(propertyId: Int, areaId: Int) = log("aaos property $propertyId error")
    }

    private fun onProperty(value: CarPropertyValue<*>, readAtMs: Long? = null) {
        if (stopped || value.status != CarPropertyValue.STATUS_AVAILABLE) return
        when (value.propertyId) {
            VehiclePropertyIds.GEAR_SELECTION -> (value.value as? Int)?.let { raw ->
                val gear = AaosGear.toGear(raw)
                synchronized(lock) { inputs = inputs.withGear(gear) }
                post(EngineEvent.SignalChanged(SignalIds.GEAR_SELECTION, SignalValue.Text(gear.name)))
            }
            // The event timestamp is elapsedRealtimeNanos of the sample, not of delivery.
            VehiclePropertyIds.PERF_VEHICLE_SPEED -> (value.value as? Float)?.let { onSpeed(it, readAtMs ?: (value.timestamp / NANOS_PER_MILLI)) }
            VehiclePropertyIds.EV_CHARGE_STATE -> (value.value as? Int)?.let {
                post(EngineEvent.SignalChanged(SignalIds.EV_CHARGING, SignalValue.Bool(it == EV_CHARGE_STATE_CHARGING)))
            }
        }
        publishDriving()
    }

    /** Speed events are frequent; the engine only hears about whole km/h changes. */
    private fun onSpeed(metresPerSecond: Float, sampledAtMs: Long) {
        val atMs = sampledAtMs.takeIf { it > 0 } ?: SystemClock.elapsedRealtime()
        val kph = synchronized(lock) {
            inputs = inputs.withSpeedMetresPerSecond(metresPerSecond, atMs)
            inputs.speedKph?.roundToInt()
        } ?: return
        val changed = synchronized(lock) { (kph != lastSpeedKph).also { lastSpeedKph = kph } }
        if (changed) post(EngineEvent.SignalChanged(SignalIds.VEHICLE_SPEED, SignalValue.of(kph.coerceIn(0, MAX_KPH))))
    }

    /** Posts only when something the parked check depends on changes, not on every speed sample. */
    private fun publishDriving() {
        val state = current()
        val changed = synchronized(lock) {
            val previous = lastDriving
            val same = previous != null && previous.gear == state.gear && previous.uxRestricted == state.uxRestricted &&
                previous.isParked == state.isParked && (previous.speedKph == null) == (state.speedKph == null)
            if (!same) lastDriving = state
            !same
        }
        if (changed) {
            log("driving gear=${state.gear} speed=${state.speedKph} uxRestricted=${state.uxRestricted} parked=${state.isParked}")
            post(EngineEvent.DrivingChanged(state))
        }
    }

    private companion object {
        /** `android.car.hardware.property.EvChargeState.STATE_CHARGING`. */
        const val EV_CHARGE_STATE_CHARGING = 1
        const val MAX_KPH = 400
        const val GLOBAL_AREA = 0
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
