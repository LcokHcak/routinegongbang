package kr.routinegongbang.core.safety

import kr.routinegongbang.core.model.Action
import kr.routinegongbang.core.model.CompareOp
import kr.routinegongbang.core.model.Condition
import kr.routinegongbang.core.model.Limits
import kr.routinegongbang.core.model.Routine
import kr.routinegongbang.core.model.RoutineValidator
import kr.routinegongbang.core.model.SignalCatalog
import kr.routinegongbang.core.model.SignalId
import kr.routinegongbang.core.model.SignalIds
import kr.routinegongbang.core.model.SignalValue
import kr.routinegongbang.core.model.Trigger
import kr.routinegongbang.core.model.ValidationIssue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Static checks when a routine is saved, imported or reordered. */
class RoutineLinterTest {

    private val catalog = SignalCatalog.default()
    private val linter = RoutineLinter(catalog)
    private val yes = SignalValue.Bool(true)

    private fun routine(trigger: Trigger, vararg actions: Action, condition: Condition? = null, limits: Limits = Limits(), id: String = "r") =
        Routine(id = id, name = id, trigger = trigger, condition = condition, actions = actions.toList(), limits = limits)

    private fun write(signal: SignalId, value: SignalValue) = Action.Write(signal, value)

    private fun rules(findings: List<LintFinding>, severity: Severity) = findings.filter { it.severity == severity }.map { it.rule }.toSet()

    @Test
    fun `a plain comfort routine has nothing to say`() {
        assertTrue(linter.lint(routine(Trigger.RideStart, write(SignalIds.STEERING_WHEEL_HEAT, SignalValue.of(1)))).isEmpty())
    }

    @Test
    fun `H15 L1-2 a value outside the automatic range is allowed but will ask`() {
        val findings = linter.lint(routine(Trigger.RideStart, write(SignalIds.SEAT_HEAT_DRIVER, SignalValue.of(3))))
        assertEquals(setOf("L1-2"), rules(findings, Severity.WARNING))
    }

    @Test
    fun `H01 L1-3 writing the same signal twice in one routine is an error`() {
        val twice = routine(Trigger.RideStart, write(SignalIds.SEAT_HEAT_DRIVER, SignalValue.of(1)), write(SignalIds.SEAT_HEAT_DRIVER, SignalValue.of(2)))
        assertEquals(setOf("L1-3"), rules(linter.lint(twice), Severity.ERROR))
    }

    @Test
    fun `L1-4 a routine that writes its own trigger signal gets a note`() {
        val self = routine(Trigger.SignalEdge(SignalIds.DEFROSTER_FRONT, to = setOf(yes)), write(SignalIds.DEFROSTER_FRONT, yes))
        assertEquals(setOf("L1-4"), rules(linter.lint(self), Severity.WARNING))
    }

    @Test
    fun `H01 L1-5 two routines that can fire together and write different values are flagged`() {
        val a = routine(Trigger.RideStart, write(SignalIds.SEAT_HEAT_DRIVER, SignalValue.of(1)), id = "a")
        val b = routine(Trigger.SignalEdge(SignalIds.SEAT_OCCUPIED_DRIVER, to = setOf(yes)), write(SignalIds.SEAT_HEAT_DRIVER, SignalValue.of(2)), id = "b")
        val finding = linter.lintAll(listOf(a, b)).single { it.rule == "L1-5" }
        assertEquals(Severity.WARNING, finding.severity)
        assertEquals("b", finding.routineId, "the later routine is the one that loses")
        assertTrue(linter.lintAll(listOf(a, a.copy(id = "c"))).none { it.rule == "L1-5" }, "same value: nothing to decide")
    }

    @Test
    fun `H03 L1-6 a threshold trigger needs a cooldown of at least ten minutes`() {
        val soc = Trigger.Threshold(SignalIds.EV_SOC, CompareOp.GE, SignalValue.of(80))
        assertEquals(setOf("L1-6"), rules(linter.lint(routine(soc, Action.Notify("n"), limits = Limits(cooldownMinutes = 5))), Severity.ERROR))
        assertTrue(linter.lint(routine(soc, Action.Notify("n"), limits = Limits(cooldownMinutes = 10))).isEmpty())
    }

    @Test
    fun `L1-7 a condition that can never hold is an error`() {
        val contradiction = Condition.And(
            listOf(
                Condition.Compare(SignalIds.GEAR_SELECTION, CompareOp.EQ, SignalValue.Text("PARK")),
                Condition.Compare(SignalIds.GEAR_SELECTION, CompareOp.EQ, SignalValue.Text("DRIVE")),
            ),
        )
        assertEquals(setOf("L1-7"), rules(linter.lint(routine(Trigger.RideStart, Action.Notify("n"), condition = contradiction)), Severity.ERROR))
    }

    @Test
    fun `H05 L1-8 body actions on a trigger that can fire while driving will only be reminded`() {
        val lockOnArrival = routine(Trigger.NaviArrived, write(SignalIds.DOOR_LOCK, yes))
        assertEquals(setOf("L1-8"), rules(linter.lint(lockOnArrival), Severity.WARNING))
    }

    @Test
    fun `H07 L1-9 nothing moves or locks after the driver has left`() {
        assertEquals(setOf("L1-9"), rules(linter.lint(routine(Trigger.RideEnd, write(SignalIds.DOOR_LOCK, yes))), Severity.ERROR))
    }

    @Test
    fun `H09 L1-10 making the view worse is never saved`() {
        val off = routine(Trigger.RideStart, write(SignalIds.DEFROSTER_FRONT, SignalValue.Bool(false)))
        assertEquals(setOf("L1-10"), rules(linter.lint(off), Severity.ERROR))
    }

    @Test
    fun `H29 L1-11 weakening the cabin climate after the driver left is an error, on a clock it is a warning`() {
        val hot = write(SignalIds.HVAC_TEMP_DRIVER, SignalValue.of(27))
        assertEquals(setOf("L1-11"), rules(linter.lint(routine(Trigger.RideEnd, hot)), Severity.ERROR))
        assertEquals(setOf("L1-11"), rules(linter.lint(routine(Trigger.TimeOfDay(7, 0), hot)), Severity.WARNING))
        assertTrue(linter.lint(routine(Trigger.TimeOfDay(7, 0), write(SignalIds.HVAC_TEMP_DRIVER, SignalValue.of(22)))).isEmpty(), "a warm-up is fine")
    }

    @Test
    fun `L1-5 two edges of one signal towards different values never fire together`() {
        val locked = routine(Trigger.SignalEdge(SignalIds.DOOR_LOCK, to = setOf(yes)), write(SignalIds.SEAT_HEAT_DRIVER, SignalValue.of(1)), id = "a")
        val unlocked = routine(Trigger.SignalEdge(SignalIds.DOOR_LOCK, to = setOf(SignalValue.Bool(false))), write(SignalIds.SEAT_HEAT_DRIVER, SignalValue.of(2)), id = "b")
        assertTrue(linter.lintAll(listOf(locked, unlocked)).none { it.rule == "L1-5" })
        val seatEmptied = routine(Trigger.SignalEdge(SignalIds.SEAT_OCCUPIED_DRIVER, to = setOf(SignalValue.Bool(false))), write(SignalIds.SEAT_HEAT_DRIVER, SignalValue.of(2)), id = "c")
        val ride = routine(Trigger.RideStart, write(SignalIds.SEAT_HEAT_DRIVER, SignalValue.of(1)), id = "d")
        assertTrue(linter.lintAll(listOf(ride, seatEmptied)).none { it.rule == "L1-5" }, "a ride starts when the seat fills, not when it empties")
    }

    @Test
    fun `H27 L1-12 at most fifty routines`() {
        val many = (1..51).map { routine(Trigger.RideStart, Action.Notify("n"), id = "r$it") }
        assertEquals(setOf("L1-12"), rules(linter.lintAll(many), Severity.ERROR))
    }

    @Test
    fun `errors keep a routine from being validated, warnings do not`() {
        val validator = RoutineValidator(catalog)
        val unsafe = routine(Trigger.RideEnd, write(SignalIds.DOOR_LOCK, yes))
        assertTrue(validator.validate(unsafe).any { it is ValidationIssue.Unsafe && it.rule == "L1-9" })
        assertTrue(validator.validate(routine(Trigger.RideStart, write(SignalIds.SEAT_HEAT_DRIVER, SignalValue.of(3)))).isEmpty())
    }
}
