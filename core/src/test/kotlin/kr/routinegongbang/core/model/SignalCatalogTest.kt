package kr.routinegongbang.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignalCatalogTest {

    private val catalog = SignalCatalog.default()

    @Test
    fun `every signal has a unique id`() {
        val ids = catalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `duplicate ids are rejected with the offending id in the message`() {
        val spec = catalog.getOrThrow(SignalIds.EV_SOC)
        val error = assertFailsWith<IllegalArgumentException> { SignalCatalog(listOf(spec, spec)) }
        assertTrue("ev.soc" in error.message.orEmpty())
    }

    @Test
    fun `wiper switch and wiper state are separate signals and only the state is subscribable`() {
        val switch = assertNotNull(catalog[SignalIds.WIPER_FRONT_SWITCH])
        val state = assertNotNull(catalog[SignalIds.WIPER_FRONT_STATE])
        assertFalse(switch.subscribable)
        assertTrue(state.subscribable)
        assertEquals(SafetyClass.SIGNAL_ONLY, state.safetyClass)
        assertTrue(SignalValue.Text("AUTO") in assertNotNull(switch.allowedValues))
    }

    @Test
    fun `door lock is subscribed with the combined four-door area and unlocking is forbidden`() {
        val lock = assertNotNull(catalog[SignalIds.DOOR_LOCK])
        assertEquals(AreaKeys.ALL_PASSENGER_DOORS, lock.area)
        assertEquals(SafetyClass.BODY, lock.safetyClass)
        assertEquals(WriteDirection.Only(setOf(SignalValue.Bool(true))), lock.profile?.direction)
    }

    @Test
    fun `seat occupancy carries the inverted boolean correction found on the emulator`() {
        assertEquals(ValueCorrection.INVERT_BOOLEAN, catalog.getOrThrow(SignalIds.SEAT_OCCUPIED_DRIVER).correction)
    }

    @Test
    fun `sunroof is registered as unsupported`() {
        assertTrue(catalog.getOrThrow(SignalIds.ROOF_SUNROOF).unsupported)
    }

    @Test
    fun `comfort signals are writable through the vehicle control sdk and have a range`() {
        val heat = catalog.getOrThrow(SignalIds.STEERING_WHEEL_HEAT)
        assertEquals(SafetyClass.COMFORT, heat.safetyClass)
        assertEquals(SdkModule.VEHICLE_CONTROL, heat.writeSdk)
        assertTrue(heat.writable)
        assertEquals(0.0..3.0, heat.range)
    }

    @Test
    fun `unknown ids return null and getOrThrow fails clearly`() {
        assertNull(catalog[SignalId("does.not.exist")])
        assertFailsWith<IllegalArgumentException> { catalog.getOrThrow(SignalId("does.not.exist")) }
    }

    @Test
    fun `gear selection comes from the AAOS car property`() {
        assertEquals(SdkModule.AAOS_CAR_PROPERTY, catalog.getOrThrow(SignalIds.GEAR_SELECTION).readSdk)
    }

    @Test
    fun `signal-only specs cannot declare a write sdk`() {
        assertFailsWith<IllegalArgumentException> {
            SignalSpec(SignalId("x"), ValueType.NUMBER, SafetyClass.SIGNAL_ONLY, SdkModule.VEHICLE_STATE, writeSdk = SdkModule.VEHICLE_CONTROL)
        }
    }

    @Test
    fun `direction values must match the value type`() {
        val lockProfile = SignalCatalog.default().getOrThrow(SignalIds.DOOR_LOCK).profile!!
        assertFailsWith<IllegalArgumentException> {
            SignalSpec(
                SignalId("x"), ValueType.BOOLEAN, SafetyClass.BODY, SdkModule.VEHICLE_STATE, SdkModule.VEHICLE_CONTROL,
                profile = lockProfile.copy(direction = WriteDirection.Only(setOf(SignalValue.of(1)))),
            )
        }
    }
}
