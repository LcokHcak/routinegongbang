package kr.routinegongbang.core.model

import kr.routinegongbang.core.engine.Transition
import kr.routinegongbang.core.engine.firesOn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThresholdTriggerTest {

    private val validator = RoutineValidator(SignalCatalog.default())
    private val soc80 = Trigger.Threshold(SignalIds.EV_SOC, CompareOp.GE, SignalValue.of(80))

    private fun routine(trigger: Trigger) =
        Routine(id = "r", name = "충전", trigger = trigger, actions = listOf(Action.Notify("notify.charged")), limits = Limits(cooldownMinutes = 10))

    private fun change(previous: Int?, current: Int) =
        Transition(SignalIds.EV_SOC, AreaKeys.GLOBAL, previous?.let { SignalValue.of(it) }, SignalValue.of(current))

    @Test
    fun `fires once when the value crosses the threshold, even if it jumps over it`() {
        assertTrue(soc80.firesOn(rounding = Rounding.None, transition = change(79, 80)))
        assertTrue(soc80.firesOn(rounding = Rounding.None, transition = change(78, 83)))
        assertFalse(soc80.firesOn(rounding = Rounding.None, transition = change(80, 81)), "already above: no new crossing")
        assertFalse(soc80.firesOn(rounding = Rounding.None, transition = change(81, 79)))
    }

    @Test
    fun `never fires on an unknown previous value or another signal`() {
        assertFalse(soc80.firesOn(rounding = Rounding.None, transition = change(null, 85)))
        assertFalse(soc80.firesOn(rounding = Rounding.None, transition = Transition(SignalIds.HVAC_TEMP_DRIVER, AreaKeys.GLOBAL, SignalValue.of(20), SignalValue.of(90))))
    }

    @Test
    fun `falling thresholds work too`() {
        val low = Trigger.Threshold(SignalIds.EV_SOC, CompareOp.LT, SignalValue.of(20))
        assertTrue(low.firesOn(rounding = Rounding.None, transition = change(20, 19)))
        assertFalse(low.firesOn(rounding = Rounding.None, transition = change(19, 18)))
    }

    @Test
    fun `validator accepts a numeric ordering threshold`() {
        assertEquals(emptyList(), validator.validate(routine(soc80)))
    }

    @Test
    fun `validator rejects equality, non numeric signals and out of range values`() {
        assertTrue(ValidationIssue.InvalidOperator(SignalIds.EV_SOC, CompareOp.EQ) in validator.validate(routine(soc80.copy(op = CompareOp.EQ))))
        assertTrue(
            ValidationIssue.InvalidOperator(SignalIds.EV_CHARGING, CompareOp.GE) in
                validator.validate(routine(Trigger.Threshold(SignalIds.EV_CHARGING, CompareOp.GE, SignalValue.Bool(true)))),
        )
        assertTrue(ValidationIssue.OutOfRange(SignalIds.EV_SOC, SignalValue.of(120)) in validator.validate(routine(soc80.copy(value = SignalValue.of(120)))))
        assertTrue(
            ValidationIssue.NotSubscribable(SignalIds.WIPER_FRONT_SWITCH) in
                validator.validate(routine(Trigger.Threshold(SignalIds.WIPER_FRONT_SWITCH, CompareOp.GE, SignalValue.of(1)))),
        )
    }

    @Test
    fun `threshold trigger round trips through json`() {
        val json = """{"id":"r","name":"충전","trigger":{"type":"Threshold","signal":"ev.soc","op":"GE","value":80},"actions":[{"type":"Notify","textRes":"n"}]}"""
        assertEquals(soc80, RoutineJson.decode(json).getOrThrow().trigger)
    }

    @Test
    fun `threshold uses the signal rounding like conditions do`() {
        val warm = Trigger.Threshold(SignalIds.HVAC_TEMP_DRIVER, CompareOp.GE, SignalValue.of(24))
        val change = Transition(SignalIds.HVAC_TEMP_DRIVER, AreaKeys.GLOBAL, SignalValue.num(23.0), SignalValue.num(23.8))
        assertTrue(warm.firesOn(change, Rounding.Step(0.5)))
        assertFalse(warm.firesOn(change, Rounding.None))
    }

    @Test
    fun `non numeric values never fire`() {
        assertFalse(soc80.firesOn(rounding = Rounding.None, transition = Transition(SignalIds.EV_SOC, AreaKeys.GLOBAL, SignalValue.of(70), SignalValue.Text("x"))))
    }

    @Test
    fun `a threshold outside what the signal can reach never matches`() {
        assertTrue(ValidationIssue.NeverMatches in validator.validate(routine(soc80.copy(op = CompareOp.GT, value = SignalValue.of(100)))))
        assertTrue(ValidationIssue.NeverMatches in validator.validate(routine(soc80.copy(op = CompareOp.LT, value = SignalValue.of(0)))))
        assertEquals(emptyList(), validator.validate(routine(soc80.copy(op = CompareOp.GE, value = SignalValue.of(100)))))
    }
}
