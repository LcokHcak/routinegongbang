package kr.routinegongbang.app.probe

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.listener.DoorLockStateListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.HvacDefrosterStateListener
import ai.pleos.playground.vehicle.listener.HvacSteeringWheelHeatStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.WindowPositionListener
import ai.pleos.playground.vehicle.listener.WiperStateListener

/**
 * Emulator checks: capability levels, subscriptions and test writes.
 * Each callback writes one line to the sink so results can be compared with Virtual Console actions.
 */
internal class VehicleProbe(private val vehicle: Vehicle, private val sink: ProbeSink) {

    private val front = WindowArea(WindowValue.FRONT_WINDSHIELD)
    private val driverWindow = WindowArea(WindowValue.ROW_1_LEFT)
    private val roof = WindowArea(WindowValue.ROOF_TOP_1)

    fun checkCapabilities() {
        vehicle.getWindow().checkWindowPositionCapabilities(
            onComplete = { cfg, areas -> sink.log("CAP window.position ${cfg.accessType} areas=${areas.keys.map { it.values.toList() }}") },
            onFailed = { sink.log("CAP window.position FAILED ${describe(it)}") },
        )
        vehicle.getSteeringWheel().checkHvacSteeringWheelHeatCapabilities(
            onComplete = { cfg, bounds -> sink.log("CAP steeringWheel.heat ${cfg.accessType} range=${bounds.minValue}..${bounds.maxValue}") },
            onFailed = { sink.log("CAP steeringWheel.heat FAILED ${describe(it)}") },
        )
        vehicle.getHVAC().checkHvacDefrosterCapabilities(
            onComplete = { cfg, areas -> sink.log("CAP hvac.defroster ${cfg.accessType} areas=${areas.map { it.values.toList() }}") },
            onFailed = { sink.log("CAP hvac.defroster FAILED ${describe(it)}") },
        )
        vehicle.getDoor().checkDoorLockCapabilities(
            onComplete = { cfg, areas -> sink.log("CAP door.lock ${cfg.accessType} areas=${areas.map { it.values.toList() }}") },
            onFailed = { sink.log("CAP door.lock FAILED ${describe(it)}") },
        )
    }

    fun subscribe() {
        vehicle.getWindow().registerWindowPosition(driverWindow, object : WindowPositionListener {
            override fun onWindowPositionUpdated(position: Int?) = sink.log("EVT window.ROW_1_LEFT.position=$position")
            override fun onFailed(e: Exception) = sink.log("EVT window.position FAILED ${describe(e)}")
        })
        vehicle.getDoor().registerDoorLockState(DoorArea(DoorValue.ROW_1_LEFT), object : DoorLockStateListener {
            override fun onDoorLockStateUpdated(state: Boolean?) = sink.log("EVT door.ROW_1_LEFT.locked=$state")
            override fun onFailed(e: Exception) = sink.log("EVT door.lock FAILED ${describe(e)}")
        })
        listOf(SeatValue.ROW_1_LEFT, SeatValue.ROW_1_RIGHT).forEach { seat ->
            vehicle.getSeat().registerSeatOccupancy(SeatArea(seat), object : SeatOccupancyListener {
                override fun onSeatOccupancyUpdated(occupancy: Boolean?) = sink.log("EVT seat.$seat.occupied=$occupancy")
                override fun onFailed(e: Exception) = sink.log("EVT seat.occupancy FAILED ${describe(e)}")
            })
        }
        vehicle.getEvBattery().registerEvBatteryStateOfCharge(object : EvBatteryStateOfChargeListener {
            override fun onEvBatteryStateOfChargeUpdated(value: Int?) = sink.log("EVT ev.soc=$value")
            override fun onFailed(e: Exception) = sink.log("EVT ev.soc FAILED ${describe(e)}")
        })
        vehicle.getSteeringWheel().registerHvacSteeringWheelHeatState(object : HvacSteeringWheelHeatStateListener {
            override fun onHvacSteeringWheelHeatStateUpdated(state: Int?) = sink.log("EVT steeringWheel.heat=$state")
            override fun onFailed(e: Exception) = sink.log("EVT steeringWheel.heat FAILED ${describe(e)}")
        })
        subscribeWiperDefrosterGear()
    }

    private fun subscribeWiperDefrosterGear() {
        vehicle.getWiper().registerWiperState(front, object : WiperStateListener {
            override fun onWiperStateUpdated(state: WiperState?) = sink.log("EVT wiper.front.state=$state")
            override fun onFailed(e: Exception) = sink.log("EVT wiper.state FAILED ${describe(e)}")
        })
        vehicle.getHVAC().registerHvacDefrosterState(front, object : HvacDefrosterStateListener {
            override fun onHvacDefrosterStateUpdated(state: Boolean?) = sink.log("EVT hvac.defroster.front=$state")
            override fun onFailed(e: Exception) = sink.log("EVT hvac.defroster FAILED ${describe(e)}")
        })
        vehicle.getGear().registerGearSelection(object : GearSelectionListener {
            override fun onSelected(gear: VehicleGear?) = sink.log("EVT gear=$gear")
            override fun onFailed(e: Exception) = sink.log("EVT gear FAILED ${describe(e)}")
        })
    }

    /** The wiper switch cannot be subscribed, so it is read on demand and compared with the state. */
    fun readWiper() {
        vehicle.getWiper().getCurrentWiperSwitch(front, { sink.log("GET wiper.front.switch=$it") }, { sink.log("GET wiper.switch FAILED ${describe(it)}") })
        vehicle.getWiper().getCurrentWiperState(front, { sink.log("GET wiper.front.state=$it") }, { sink.log("GET wiper.state FAILED ${describe(it)}") })
    }

    /** Does the emulator accept writes from a third-party app? */
    fun writeSteeringHeat(level: Int) {
        vehicle.getSteeringWheel().setHvacSteeringWheelHeat(
            value = level,
            onComplete = { sink.log("SET steeringWheel.heat=$level -> onComplete($it)") },
            onFailed = { sink.log("SET steeringWheel.heat=$level FAILED ${describe(it)}") },
        )
    }

    fun writeDefroster(on: Boolean) {
        vehicle.getHVAC().setHvacDefroster(
            area = front,
            value = on,
            onComplete = { _, v -> sink.log("SET hvac.defroster.front=$on -> onComplete($v)") },
            onFailed = { sink.log("SET hvac.defroster.front=$on FAILED ${describe(it)}") },
        )
    }

    fun checkRoof() {
        vehicle.getWindow().getCurrentWindowPosition(roof, { sink.log("GET window.ROOF_TOP_1.position=$it") }, { sink.log("GET window.ROOF_TOP_1 FAILED ${describe(it)}") })
    }
}
