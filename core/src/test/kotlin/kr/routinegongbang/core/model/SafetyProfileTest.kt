package kr.routinegongbang.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every writable signal answers every safety question; nothing falls back to a default. */
class SafetyProfileTest {

    private val catalog = SignalCatalog.default()

    @Test
    fun `every writable signal in the catalog carries a safety profile`() {
        val missing = catalog.all.filter { it.writable && it.profile == null }.map { it.id.value }
        assertTrue(missing.isEmpty(), "no profile: $missing")
        assertTrue(catalog.all.any { it.writable }, "the catalog must keep writable signals")
    }

    @Test
    fun `a writable spec without a profile cannot be built`() {
        assertFailsWith<IllegalArgumentException> {
            SignalSpec(SignalId("x"), ValueType.BOOLEAN, SafetyClass.COMFORT, SdkModule.VEHICLE_STATE, SdkModule.VEHICLE_CONTROL)
        }
    }

    @Test
    fun `H12 H13 seat posture motors are never writable in V1`() {
        val motor = SignalSpec(SignalId("seat.position.driver"), ValueType.NUMBER, SafetyClass.POSTURE, SdkModule.VEHICLE_STATE, SdkModule.VEHICLE_CONTROL)
        assertFalse(motor.writable)
    }

    @Test
    fun `H11 the wiper switch is excluded from writes`() {
        val switch = catalog.getOrThrow(SignalIds.WIPER_FRONT_SWITCH)
        assertEquals(SafetyClass.EXCLUDED, switch.safetyClass)
        assertFalse(switch.writable)
    }

    @Test
    fun `H08 the door lock may only be locked automatically`() {
        val profile = assertNotNull(catalog.getOrThrow(SignalIds.DOOR_LOCK).profile)
        assertTrue(profile.direction.allows(SignalValue.Bool(true), current = null))
        assertFalse(profile.direction.allows(SignalValue.Bool(false), current = null))
        assertTrue(profile.confirmRequired)
    }

    @Test
    fun `H09 the defroster is visibility critical and only turning it on is favourable`() {
        val visibility = assertNotNull(catalog.getOrThrow(SignalIds.DEFROSTER_FRONT).profile?.visibility)
        assertTrue(visibility.allows(SignalValue.Bool(true), current = SignalValue.Bool(false)))
        assertFalse(visibility.allows(SignalValue.Bool(false), current = SignalValue.Bool(true)))
    }

    @Test
    fun `H16 the cabin temperature is life support with a narrower automatic range`() {
        val spec = catalog.getOrThrow(SignalIds.HVAC_TEMP_DRIVER)
        val profile = assertNotNull(spec.profile)
        assertEquals(LifeSupport.ComfortBand(18.0..26.0), profile.lifeSupport)
        assertTrue(profile.lifeSupport!!.weakens(SignalValue.of(27), current = SignalValue.of(22)))
        assertFalse(profile.lifeSupport!!.weakens(SignalValue.of(22), current = SignalValue.of(16)))
        assertEquals(17.0..28.0, profile.autoRange)
        assertEquals(16.0..30.0, spec.range, "saved routines inside the SDK range stay valid; outside the automatic range they ask")
    }

    @Test
    fun `H15 heaters run automatically only up to level 2`() {
        listOf(SignalIds.SEAT_HEAT_DRIVER, SignalIds.STEERING_WHEEL_HEAT).forEach {
            assertEquals(0.0..2.0, catalog.getOrThrow(it).profile?.autoRange, it.value)
        }
    }

    @Test
    fun `H07 the window may only be closed automatically, never opened`() {
        val direction = assertNotNull(catalog.getOrThrow(SignalIds.WINDOW_POSITION_DRIVER).profile).direction
        assertTrue(direction.allows(SignalValue.of(0), current = SignalValue.of(30)))
        assertFalse(direction.allows(SignalValue.of(40), current = SignalValue.of(30)))
        assertFalse(direction.allows(SignalValue.of(0), current = null), "an unknown position cannot prove a decrease")
        assertTrue(direction.allows(SignalValue.of(0), current = SignalValue.of(0)), "not moving is not the wrong direction; it is no change")
    }

    @Test
    fun `directions explain themselves for the linter`() {
        assertNull(WriteDirection.Any.staticViolation(SignalValue.Bool(false)))
        assertNotNull(WriteDirection.Only(setOf(SignalValue.Bool(true))).staticViolation(SignalValue.Bool(false)))
        assertNull(WriteDirection.Decrease.staticViolation(SignalValue.of(0)), "decrease can only be judged at run time")
    }

    @Test
    fun `rate limits are positive and the hour limit covers the minute limit`() {
        catalog.all.mapNotNull { spec -> spec.profile?.let { spec.id to it } }.forEach { (id, profile) ->
            assertTrue(profile.maxWritesPerMinute > 0, id.value)
            assertTrue(profile.maxWritesPerHour >= profile.maxWritesPerMinute, id.value)
            assertTrue(profile.evidence.isNotBlank(), id.value)
        }
    }
}
