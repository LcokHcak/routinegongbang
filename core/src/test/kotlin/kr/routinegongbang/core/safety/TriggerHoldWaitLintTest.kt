package kr.routinegongbang.core.safety

import kr.routinegongbang.core.model.Action
import kr.routinegongbang.core.model.CompareOp
import kr.routinegongbang.core.model.Condition
import kr.routinegongbang.core.model.Limits
import kr.routinegongbang.core.model.Routine
import kr.routinegongbang.core.model.SignalCatalog
import kr.routinegongbang.core.model.SignalIds
import kr.routinegongbang.core.model.SignalValue
import kr.routinegongbang.core.model.Trigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the static checks say about waits and the new triggers. */
class TriggerHoldWaitLintTest {

    private val catalog = SignalCatalog.default()
    private val linter = RoutineLinter(catalog)
    private val arbiter = Arbiter(catalog)
    private val yes = SignalValue.Bool(true)

    private fun routine(trigger: Trigger, vararg actions: Action, limits: Limits = Limits(), id: String = "r") =
        Routine(id = id, name = id, trigger = trigger, actions = actions.toList(), limits = limits)

    private fun heat(level: Int) = Action.Write(SignalIds.SEAT_HEAT_DRIVER, SignalValue.of(level))
    private fun rules(r: Routine, severity: Severity) = linter.lint(r).filter { it.severity == severity }.map { it.rule }.toSet()

    @Test
    fun `the same signal may be written again after a wait, the step-down example`() {
        val stepDown = routine(Trigger.RideStart, heat(2), Action.Wait(600), heat(1))
        assertTrue("L1-3" !in rules(stepDown, Severity.ERROR))
        assertEquals(emptySet(), rules(stepDown, Severity.WARNING))
    }

    @Test
    fun `without a wait between them the second write still only undoes the first`() {
        assertTrue("L1-3" in rules(routine(Trigger.RideStart, heat(2), Action.Notify("n"), heat(1)), Severity.ERROR))
    }

    @Test
    fun `a wait shorter than the flip-flop window before the same signal is warned about`() {
        // The flip-flop check would skip the second write as flipping back and forth.
        val quick = routine(Trigger.RideStart, Action.Write(SignalIds.HVAC_AC, yes), Action.Wait(60), Action.Write(SignalIds.HVAC_AC, SignalValue.Bool(false)))
        assertTrue("L1-14" in rules(quick, Severity.WARNING))
    }

    @Test
    fun `a wait at the end, or two in a row, is an error`() {
        assertTrue("L1-13" in rules(routine(Trigger.RideStart, heat(1), Action.Wait(60)), Severity.ERROR))
        assertTrue("L1-13" in rules(routine(Trigger.RideStart, heat(1), Action.Wait(60), Action.Wait(60), heat(2)), Severity.ERROR))
    }

    @Test
    fun `an all-met trigger comparing sizes needs a cooldown like a threshold, a hold does not replace it`() {
        val steady = Trigger.AllOf(
            listOf(Condition.Compare(SignalIds.VEHICLE_SPEED, CompareOp.GE, SignalValue.of(80)), Condition.Compare(SignalIds.VEHICLE_SPEED, CompareOp.LE, SignalValue.of(100))),
            holdSeconds = 600,
        )
        assertTrue("L1-6" in rules(routine(steady, Action.Notify("n")), Severity.ERROR))
        assertTrue("L1-6" !in rules(routine(steady, Action.Notify("n"), limits = Limits(cooldownMinutes = 10)), Severity.ERROR))
        val equalsOnly = Trigger.AllOf(listOf(Condition.Compare(SignalIds.SEATBELT_DRIVER, CompareOp.EQ, yes)))
        assertTrue("L1-6" !in rules(routine(equalsOnly, Action.Notify("n")), Severity.ERROR))
    }

    @Test
    fun `an all-met trigger on a signal the routine writes warns that it never triggers itself`() {
        val heated = Trigger.AllOf(listOf(Condition.Compare(SignalIds.SEATBELT_DRIVER, CompareOp.EQ, yes), Condition.Compare(SignalIds.HVAC_AC, CompareOp.EQ, yes)))
        assertTrue("L1-4" in rules(routine(heated, Action.Write(SignalIds.HVAC_AC, yes)), Severity.WARNING))
    }

    @Test
    fun `routines sharing a signal in an all-met trigger may fire together and conflict`() {
        val belt = Trigger.AllOf(listOf(Condition.Compare(SignalIds.SEATBELT_DRIVER, CompareOp.EQ, yes)))
        val first = routine(belt, heat(1), id = "a")
        val second = routine(Trigger.SignalEdge(SignalIds.SEATBELT_DRIVER, to = setOf(yes)), heat(2), id = "b")
        assertTrue(linter.lintAll(listOf(first, second)).any { it.rule == "L1-5" && it.routineId == "b" })
    }

    @Test
    fun `arbitration only looks at the part of a routine that runs now`() {
        val stepDown = routine(Trigger.RideStart, heat(2), Action.Wait(600), heat(1))
        assertEquals(listOf(emptySet()), arbiter.suppressed(listOf(stepDown), listOf(0 until 1)))
        assertEquals(listOf(emptySet()), arbiter.suppressed(listOf(stepDown), listOf(2 until 3)))
    }
}
