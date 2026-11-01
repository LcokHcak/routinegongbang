package kr.routinegongbang.app.ui

import kr.routinegongbang.core.model.SignalCatalog
import kr.routinegongbang.core.model.SignalIds
import kr.routinegongbang.core.model.ValueType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Every signal a routine can use is shown with a Korean name, never its internal id. */
class SignalNamesTest {

    private val catalog = SignalCatalog.default()

    @Test
    fun `every usable signal has a Korean name`() {
        val missing = catalog.all.filter { !it.unsupported }.map { it.id }.filter { it !in SignalNames.names }
        assertTrue(missing.isEmpty(), "no name: ${missing.map { it.value }}")
    }

    @Test
    fun `every number has a unit, so 3 never reads as 3 degrees by accident`() {
        val missing = catalog.all.filter { !it.unsupported && it.valueType == ValueType.NUMBER }.map { it.id }.filter { it !in SignalNames.units }
        assertTrue(missing.isEmpty(), "no unit: ${missing.map { it.value }}")
        assertEquals("°C", SignalNames.units[SignalIds.HVAC_TEMP_PASSENGER])
        assertEquals("단계", SignalNames.units[SignalIds.SEAT_VENT_PASSENGER])
    }

    @Test
    fun `numbers are shown with at most one decimal`() {
        // The cabin sensor reports 19.100000381469727.
        assertEquals("19.1", SignalNames.number(19.100000381469727))
        assertEquals("22", SignalNames.number(22.0))
        assertEquals("22.5", SignalNames.number(22.5))
    }

    @Test
    fun `every driving mode has a Korean word`() {
        catalog.getOrThrow(SignalIds.DRIVING_MODE).allowedValues!!.forEach { assertTrue(it in SignalNames.drivingModes, it.toString()) }
    }
}
