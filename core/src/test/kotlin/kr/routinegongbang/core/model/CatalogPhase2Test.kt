package kr.routinegongbang.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Catalog phase 2: the comfort controls and the readings worth a routine, each with its safety answers. */
class CatalogPhase2Test {

    private val catalog = SignalCatalog.default()
    private fun profile(id: SignalId) = assertNotNull(catalog.getOrThrow(id).profile, id.value)

    @Test
    fun `every comfort control of phase 2 is writable and comfort-class`() {
        val comfort = listOf(
            SignalIds.HVAC_POWER, SignalIds.HVAC_AC, SignalIds.HVAC_AUTO, SignalIds.HVAC_FAN_SPEED, SignalIds.HVAC_TEMP_PASSENGER,
            SignalIds.HVAC_AUTO_RECIRCULATION, SignalIds.HVAC_AIR_CLEANER, SignalIds.HVAC_MAX_AC, SignalIds.HVAC_MAX_DEFROST, SignalIds.HVAC_DUAL,
            SignalIds.SEAT_HEAT_PASSENGER, SignalIds.SEAT_HEAT_REAR_LEFT, SignalIds.SEAT_HEAT_REAR_RIGHT,
            SignalIds.SEAT_VENT_DRIVER, SignalIds.SEAT_VENT_PASSENGER, SignalIds.MIRROR_HEAT,
            SignalIds.AMBIENT_LIGHT, SignalIds.AMBIENT_BRIGHTNESS,
        )
        comfort.forEach {
            val spec = catalog.getOrThrow(it)
            assertTrue(spec.writable, it.value)
            assertEquals(SafetyClass.COMFORT, spec.safetyClass, it.value)
        }
    }

    @Test
    fun `the readings of phase 2 are read-only`() {
        val readings = listOf(
            SignalIds.SEAT_OCCUPIED_REAR_LEFT, SignalIds.SEAT_OCCUPIED_REAR_RIGHT, SignalIds.SEATBELT_DRIVER, SignalIds.SEATBELT_PASSENGER,
            SignalIds.CABIN_TEMPERATURE, SignalIds.WASHER_FLUID_LOW, SignalIds.DRIVING_MODE,
            SignalIds.WINDOW_POSITION_PASSENGER, SignalIds.WINDOW_LOCK, SignalIds.DOOR_OPEN_DRIVER, SignalIds.ODOMETER, SignalIds.TRIP_DISTANCE,
        )
        readings.forEach {
            val spec = catalog.getOrThrow(it)
            assertFalse(spec.writable, it.value)
            assertEquals(SafetyClass.SIGNAL_ONLY, spec.safetyClass, it.value)
        }
        assertTrue(catalog.all.count { it.readSdk != null && !it.unsupported } >= 40)
    }

    @Test
    fun `H14 heating or ventilating another seat needs someone known to sit there`() {
        mapOf(
            SignalIds.SEAT_HEAT_PASSENGER to SignalIds.SEAT_OCCUPIED_PASSENGER,
            SignalIds.SEAT_HEAT_REAR_LEFT to SignalIds.SEAT_OCCUPIED_REAR_LEFT,
            SignalIds.SEAT_HEAT_REAR_RIGHT to SignalIds.SEAT_OCCUPIED_REAR_RIGHT,
            SignalIds.SEAT_VENT_PASSENGER to SignalIds.SEAT_OCCUPIED_PASSENGER,
        ).forEach { (control, seat) -> assertEquals(Occupancy.Occupied(seat), profile(control).occupancy, control.value) }
        assertEquals(Occupancy.Any, profile(SignalIds.SEAT_VENT_DRIVER).occupancy, "the driver may pre-cool")
        listOf(SignalIds.SEAT_HEAT_PASSENGER, SignalIds.SEAT_HEAT_REAR_LEFT, SignalIds.SEAT_HEAT_REAR_RIGHT).forEach {
            assertEquals(0.0..2.0, profile(it).autoRange, it.value)
        }
    }

    @Test
    fun `H09 max defrost may only be switched on, and it moves the rest of the climate`() {
        val max = profile(SignalIds.HVAC_MAX_DEFROST)
        assertEquals(WriteDirection.Only(setOf(SignalValue.Bool(true))), max.visibility)
        assertTrue(SignalIds.DEFROSTER_FRONT in max.coupledSignals)
        assertTrue(SignalIds.HVAC_FAN_SPEED in max.coupledSignals)
    }

    @Test
    fun `H29 the climate someone left inside may need is never weakened unattended`() {
        assertEquals(LifeSupport.KeepOn(SignalValue.Bool(true)), profile(SignalIds.HVAC_POWER).lifeSupport)
        assertEquals(LifeSupport.KeepOn(SignalValue.Bool(true)), profile(SignalIds.HVAC_AC).lifeSupport)
        assertEquals(LifeSupport.NoDecrease, profile(SignalIds.HVAC_FAN_SPEED).lifeSupport)
        assertEquals(LifeSupport.ComfortBand(18.0..26.0), profile(SignalIds.HVAC_TEMP_PASSENGER).lifeSupport)
    }

    @Test
    fun `H28 couplings are declared for the modes that move other settings`() {
        assertTrue(profile(SignalIds.HVAC_AUTO).coupledSignals.containsAll(setOf(SignalIds.HVAC_FAN_SPEED, SignalIds.HVAC_AC, SignalIds.RECIRCULATION)))
        assertTrue(profile(SignalIds.HVAC_MAX_AC).coupledSignals.containsAll(setOf(SignalIds.HVAC_FAN_SPEED, SignalIds.HVAC_AC, SignalIds.RECIRCULATION)))
        assertEquals(setOf(SignalIds.HVAC_TEMP_PASSENGER), profile(SignalIds.HVAC_DUAL).coupledSignals)
        listOf(SignalIds.HVAC_AUTO, SignalIds.HVAC_MAX_AC, SignalIds.HVAC_MAX_DEFROST, SignalIds.HVAC_DUAL).forEach {
            assertTrue("self-test" in profile(it).evidence, "${it.value}: assumed couplings must say they await measurement")
        }
    }

    @Test
    fun `evidence says what the emulator self-test measured and what it could not`() {
        // Second self-test, with the Virtual Console open: every write echoed except the ambient light switch, and the
        // emulator moved nothing along with any write, so the declared couplings stay assumptions.
        assertTrue("echo confirmed" in profile(SignalIds.SEAT_HEAT_PASSENGER).evidence)
        listOf(SignalIds.HVAC_AUTO, SignalIds.HVAC_MAX_AC, SignalIds.HVAC_MAX_DEFROST, SignalIds.HVAC_DUAL).forEach {
            assertTrue("does not simulate couplings" in profile(it).evidence, it.value)
        }
        assertTrue("not confirmable on the emulator" in profile(SignalIds.AMBIENT_LIGHT).evidence)
    }

    @Test
    fun `every seat and window signal is bound to the position its name says`() {
        // A wrong area would heat or read someone else's seat and stamp their changes as the driver's.
        val seats = mapOf("driver" to AreaKeys.DRIVER_SEAT, "passenger" to AreaKeys.PASSENGER_SEAT, "rearLeft" to AreaKeys.REAR_LEFT_SEAT, "rearRight" to AreaKeys.REAR_RIGHT_SEAT)
        val windows = mapOf("driver" to AreaKeys.DRIVER_WINDOW, "passenger" to AreaKeys.PASSENGER_WINDOW)
        catalog.all.forEach { spec ->
            val position = spec.id.value.substringAfterLast('.')
            val expected = when {
                spec.id.value.startsWith("seat.") -> seats[position]
                spec.id.value.startsWith("window.position.") -> windows[position]
                else -> null
            }
            if (expected != null) assertEquals(expected, spec.area, spec.id.value)
        }
    }

    @Test
    fun `ambient light changes are rate limited so they cannot distract`() {
        listOf(SignalIds.AMBIENT_LIGHT, SignalIds.AMBIENT_BRIGHTNESS).forEach { assertTrue(profile(it).maxWritesPerMinute <= 2, it.value) }
    }

    @Test
    fun `H28 the climate settings that move each other are declared coupled`() {
        assertTrue(SignalIds.HVAC_TEMP_PASSENGER in profile(SignalIds.HVAC_TEMP_DRIVER).coupledSignals, "with dual off the passenger follows the driver")
        assertTrue(SignalIds.HVAC_DUAL in profile(SignalIds.HVAC_TEMP_PASSENGER).coupledSignals, "setting the passenger switches dual on")
        assertTrue(SignalIds.HVAC_AUTO in profile(SignalIds.HVAC_FAN_SPEED).coupledSignals, "a manual fan speed ends AUTO")
        assertTrue(SignalIds.HVAC_AUTO_RECIRCULATION in profile(SignalIds.RECIRCULATION).coupledSignals, "manual recirculation ends automatic recirculation")
    }

    @Test
    fun `H09 mirror heat is on or off and is never switched off automatically`() {
        val mirror = catalog.getOrThrow(SignalIds.MIRROR_HEAT)
        assertEquals(0.0..1.0, mirror.range)
        assertEquals(WriteDirection.Only(setOf(SignalValue.of(1))), mirror.profile?.visibility)
    }

    @Test
    fun `continuously changing readings are compared to a tenth`() {
        assertEquals(Rounding.Step(0.1), catalog.getOrThrow(SignalIds.CABIN_TEMPERATURE).rounding)
        // Metre-resolution distances would raise an event every few metres while driving.
        assertEquals(Rounding.Step(0.1), catalog.getOrThrow(SignalIds.TRIP_DISTANCE).rounding)
        assertEquals(Rounding.Step(0.1), catalog.getOrThrow(SignalIds.ODOMETER).rounding)
    }

    @Test
    fun `the catalog builds and is complete, whatever the order its lists are declared in`() {
        assertTrue(SignalCatalog.default().all.size >= 45)
    }
}
