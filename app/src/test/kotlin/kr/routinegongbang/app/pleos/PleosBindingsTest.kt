package kr.routinegongbang.app.pleos

import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import kr.routinegongbang.core.engine.Access
import kr.routinegongbang.core.model.SafetyClass
import kr.routinegongbang.core.model.SdkModule
import kr.routinegongbang.core.model.SignalCatalog
import kr.routinegongbang.core.model.SignalValue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every catalog signal served by the Pleos SDK is bound once, written only where allowed, with its permissions declared. */
class PleosBindingsTest {

    private val catalog = SignalCatalog.default()
    private val bindings = PleosBindings.all

    @Test
    fun `every Pleos signal of the catalog has exactly one binding and nothing else is bound`() {
        val pleos = catalog.all.filter { it.readSdk == SdkModule.VEHICLE_STATE && !it.unsupported }.map { it.id }.toSet()
        val bound = bindings.map { it.signal }
        assertEquals(bound.size, bound.toSet().size, "bound twice: ${bound.groupBy { it }.filterValues { it.size > 1 }.keys}")
        assertEquals(pleos - setOf(kr.routinegongbang.core.model.SignalIds.WIPER_FRONT_SWITCH, kr.routinegongbang.core.model.SignalIds.LOW_BEAM), bound.toSet())
    }

    @Test
    fun `every comfort control has a write, and nothing that may not be written has one`() {
        bindings.forEach { binding ->
            val spec = catalog.getOrThrow(binding.signal)
            val shouldWrite = spec.writable && spec.safetyClass == SafetyClass.COMFORT
            assertEquals(shouldWrite, binding.write != null, binding.signal.value)
            if (shouldWrite) assertNotNull(binding.writePermission, binding.signal.value)
        }
    }

    @Test
    fun `read permissions are declared for every build, control permissions only for the emulator build`() {
        val main = File("src/main/AndroidManifest.xml").readText()
        val debug = File("src/debug/AndroidManifest.xml").readText()
        bindings.forEach { binding ->
            assertTrue("\"${binding.readPermission}\"" in main, "${binding.signal.value}: ${binding.readPermission} missing in the main manifest")
            binding.writePermission?.let { control ->
                assertTrue("\"$control\"" in debug, "${binding.signal.value}: $control missing in the debug manifest")
                assertTrue("\"$control\"" !in main, "$control must not ship in release until the Control SDK opens")
            }
        }
    }

    @Test
    fun `an area is used only when it is exactly our seat, or contains it for whole-car settings`() {
        val combined = SeatArea(SeatValue.ROW_1_LEFT, SeatValue.ROW_1_RIGHT)
        val passenger = SeatArea(SeatValue.ROW_1_RIGHT)
        assertNull(seatArea(listOf(combined), SeatValue.ROW_1_RIGHT, exact = true), "a combined area would write to the driver too")
        assertEquals(passenger, seatArea(listOf(combined, passenger), SeatValue.ROW_1_RIGHT, exact = true))
        assertEquals(combined, seatArea(listOf(combined), SeatValue.ROW_1_LEFT, exact = false))
        // An area that does not contain the driver is someone else's, never a fallback.
        assertNull(seatArea(listOf(passenger), SeatValue.ROW_1_LEFT, exact = false))
    }

    @Test
    fun `the trip distance arrives in metres and is kept in kilometres`() {
        // The SDK documents the current trip distance in metres, the odometer in kilometres.
        assertEquals(SignalValue.num(3.2), kilometres(metres = 3200f))
        assertNull(kilometres(metres = null))
    }

    @Test
    fun `mirror heat uses the area that covers both mirrors`() {
        val left = ai.pleos.playground.vehicle.api.model.area.MirrorArea(ai.pleos.playground.vehicle.constant.area.MirrorValue.DRIVER_LEFT)
        val both = ai.pleos.playground.vehicle.api.model.area.MirrorArea(
            ai.pleos.playground.vehicle.constant.area.MirrorValue.DRIVER_LEFT, ai.pleos.playground.vehicle.constant.area.MirrorValue.DRIVER_RIGHT,
        )
        assertEquals(both, bothMirrors(listOf(left, both)))
        assertEquals(left, bothMirrors(listOf(left)), "a car with one heated area still gets it")
    }

    @Test
    fun `ambient light is switched only where the car offers on and off, and never out of automatic`() {
        // The driver's AUTOMATIC setting is theirs; a routine turning it ON would silently replace it.
        val auto = SeatArea(SeatValue.ROW_1_LEFT)
        val both = SeatArea(SeatValue.ROW_1_LEFT, SeatValue.ROW_1_RIGHT)
        assertNull(ambientArea(mapOf(auto to listOf(LightSwitch.AUTOMATIC)), SeatValue.ROW_1_LEFT))
        assertEquals(both, ambientArea(mapOf(auto to listOf(LightSwitch.AUTOMATIC), both to listOf(LightSwitch.ON, LightSwitch.OFF)), SeatValue.ROW_1_LEFT))
        assertTrue(switchable(LightSwitch.ON) && switchable(LightSwitch.OFF))
        assertTrue(!switchable(LightSwitch.AUTOMATIC) && !switchable(LightSwitch.DAYTIME_RUNNING) && !switchable(null))
    }

    @Test
    fun `bounds give a percent scale only when they span something`() {
        assertEquals(10, percentScale(AreaBounds(0, 10))?.max)
        assertNull(percentScale(AreaBounds(3, 3)))
        assertEquals(Access.NONE, probe(Access.READ_WRITE, area = null, required = true).access)
    }

    @Test
    fun `light states and door positions become plain on and off`() {
        assertEquals(SignalValue.Bool(true), lightOn(LightState.ON))
        assertEquals(SignalValue.Bool(false), lightOn(LightState.OFF))
        assertNull(lightOn(null))
        assertEquals(SignalValue.Bool(false), doorOpen(0, AreaBounds(0, 100)))
        assertEquals(SignalValue.Bool(true), doorOpen(30, AreaBounds(0, 100)))
    }
}
