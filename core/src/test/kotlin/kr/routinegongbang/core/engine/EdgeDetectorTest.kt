package kr.routinegongbang.core.engine

import kr.routinegongbang.core.model.AreaKeys
import kr.routinegongbang.core.model.Rounding
import kr.routinegongbang.core.model.SignalCatalog
import kr.routinegongbang.core.model.SignalIds
import kr.routinegongbang.core.model.SignalValue
import kr.routinegongbang.core.model.Trigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EdgeDetectorTest {

    private val catalog = SignalCatalog.default()
    private val wiper = SignalIds.WIPER_FRONT_STATE
    private val off = SignalValue.Text("OFF")
    private val on = SignalValue.Text("ON")
    private val rain = Trigger.SignalEdge(wiper, from = setOf(off), to = setOf(on))

    private fun detector() = EdgeDetector(catalog)

    @Test
    fun `priming value at startup never produces a transition`() {
        assertNull(detector().observe(wiper, on, priming = true).first)
    }

    @Test
    fun `OFF to ON produces a transition that fires the rain trigger`() {
        val primed = detector().observe(wiper, off, priming = true).second
        val transition = requireNotNull(primed.observe(wiper, on, priming = false).first)
        assertEquals(Transition(wiper, AreaKeys.FRONT_WINDSHIELD, previous = off, current = on), transition)
        assertTrue(rain.firesOn(transition, Rounding.None))
    }

    @Test
    fun `repeated identical values do not produce a transition`() {
        val state = detector().observe(wiper, on, priming = true).second
        assertNull(state.observe(wiper, on, priming = false).first)
    }

    @Test
    fun `ON OFF ON fires again on the second rising edge`() {
        var d = detector().observe(wiper, on, priming = true).second
        d = d.observe(wiper, off, priming = false).second
        assertTrue(rain.firesOn(requireNotNull(d.observe(wiper, on, priming = false).first), Rounding.None))
    }

    @Test
    fun `first value without priming has no previous value and never fires`() {
        val t = requireNotNull(detector().observe(wiper, on, priming = false).first)
        assertNull(t.previous)
        assertFalse(rain.firesOn(t, Rounding.None))
        assertFalse(Trigger.SignalEdge(wiper, to = setOf(on)).firesOn(t, Rounding.None))
    }

    @Test
    fun `trigger without from fires when entering the target set from any other value`() {
        val anyToOn = Trigger.SignalEdge(wiper, to = setOf(on))
        assertTrue(anyToOn.firesOn(Transition(wiper, AreaKeys.FRONT_WINDSHIELD, SignalValue.Text("OTHER"), on), Rounding.None))
        assertFalse(anyToOn.firesOn(Transition(SignalIds.EV_SOC, AreaKeys.GLOBAL, off, on), Rounding.None), "different signal must not fire")
    }

    @Test
    fun `transition into a value outside the target set does not fire`() {
        assertFalse(rain.firesOn(Transition(wiper, AreaKeys.FRONT_WINDSHIELD, off, SignalValue.Text("SERVICE")), Rounding.None))
    }

    @Test
    fun `numeric jitter within the rounding step is not a transition`() {
        val temp = SignalIds.HVAC_TEMP_DRIVER
        val d = detector().observe(temp, SignalValue.Num(22.0), priming = true).second
        assertNull(d.observe(temp, SignalValue.Num(22.2), priming = false).first)
    }

    @Test
    fun `numeric target matches within the rounding step`() {
        val temp = SignalIds.HVAC_TEMP_DRIVER
        val hot = Trigger.SignalEdge(temp, to = setOf(SignalValue.Num(30.0)))
        val t = Transition(temp, AreaKeys.GLOBAL, SignalValue.Num(20.0), SignalValue.Num(29.8))
        assertTrue(hot.firesOn(t, Rounding.Step(0.5)))
    }

    @Test
    fun `explicit catalog area and default area share the same history`() {
        val d = detector().observe(wiper, off, priming = true, area = AreaKeys.FRONT_WINDSHIELD).second
        val t = requireNotNull(d.observe(wiper, on, priming = false).first)
        assertEquals(off, t.previous)
    }
}
