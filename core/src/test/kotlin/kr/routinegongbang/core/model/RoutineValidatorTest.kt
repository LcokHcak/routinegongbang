package kr.routinegongbang.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoutineValidatorTest {

    private val validator = RoutineValidator(SignalCatalog.default())
    private val off = SignalValue.Text("OFF")
    private val on = SignalValue.Text("ON")

    private fun routine(
        actions: List<Action> = listOf(Action.Write(SignalIds.DEFROSTER_FRONT, SignalValue.Bool(true))),
        trigger: Trigger = Trigger.SignalEdge(SignalIds.WIPER_FRONT_STATE, from = setOf(off), to = setOf(on)),
        condition: Condition? = Condition.Compare(SignalIds.SEAT_OCCUPIED_DRIVER, CompareOp.EQ, SignalValue.Bool(true)),
    ) = Routine(id = "r_rain", name = "비 오는 날", trigger = trigger, condition = condition, actions = actions)

    private fun issuesOf(r: Routine) = validator.validate(r)

    @Test
    fun `valid routine has no issues`() {
        assertEquals(emptyList(), issuesOf(routine()))
    }

    @Test
    fun `H10 excluded action is rejected`() {
        assertTrue(ValidationIssue.Excluded(SignalIds.LOW_BEAM) in issuesOf(routine(listOf(Action.Write(SignalIds.LOW_BEAM, SignalValue.Bool(true))))))
    }

    @Test
    fun `H08 forbidden value such as door unlock is rejected`() {
        assertTrue(ValidationIssue.Excluded(SignalIds.DOOR_LOCK) in issuesOf(routine(listOf(Action.Write(SignalIds.DOOR_LOCK, SignalValue.Bool(false))))))
    }

    @Test
    fun `locking the doors is allowed as a body action`() {
        assertEquals(emptyList(), issuesOf(routine(listOf(Action.Write(SignalIds.DOOR_LOCK, SignalValue.Bool(true))))))
    }

    @Test
    fun `write to a different area than the catalog area is rejected`() {
        val bad = Action.Write(SignalIds.DOOR_LOCK, SignalValue.Bool(true), area = AreaKey("ROW_1_LEFT"))
        assertTrue(ValidationIssue.InvalidArea(SignalIds.DOOR_LOCK, AreaKey("ROW_1_LEFT")) in issuesOf(routine(listOf(bad))))
    }

    @Test
    fun `write with the catalog area is accepted`() {
        val ok = Action.Write(SignalIds.DOOR_LOCK, SignalValue.Bool(true), area = AreaKeys.ALL_PASSENGER_DOORS)
        assertEquals(emptyList(), issuesOf(routine(listOf(ok))))
    }

    @Test
    fun `numeric write outside the range is rejected`() {
        val bad = Action.Write(SignalIds.STEERING_WHEEL_HEAT, SignalValue.of(9))
        assertTrue(ValidationIssue.OutOfRange(SignalIds.STEERING_WHEEL_HEAT, SignalValue.of(9)) in issuesOf(routine(listOf(bad))))
    }

    @Test
    fun `H11 the wiper switch cannot be written at all`() {
        val auto = Action.Write(SignalIds.WIPER_FRONT_SWITCH, SignalValue.Text("AUTO"))
        assertTrue(ValidationIssue.Excluded(SignalIds.WIPER_FRONT_SWITCH) in issuesOf(routine(listOf(auto))))
    }

    @Test
    fun `signal-only signals cannot be written`() {
        assertTrue(ValidationIssue.NotWritable(SignalIds.EV_SOC) in issuesOf(routine(listOf(Action.Write(SignalIds.EV_SOC, SignalValue.of(80))))))
    }

    @Test
    fun `unknown and unsupported signals are rejected`() {
        val unknown = SignalId("x.unknown")
        val issues = issuesOf(routine(listOf(Action.Write(unknown, SignalValue.Bool(true)), Action.Write(SignalIds.ROOF_SUNROOF, SignalValue.of(0)))))
        assertTrue(ValidationIssue.UnknownSignal(unknown) in issues)
        assertTrue(ValidationIssue.Unsupported(SignalIds.ROOF_SUNROOF) in issues)
    }

    @Test
    fun `value type must match the signal`() {
        val issues = issuesOf(routine(listOf(Action.Write(SignalIds.DEFROSTER_FRONT, SignalValue.of(1)))))
        assertTrue(ValidationIssue.TypeMismatch(SignalIds.DEFROSTER_FRONT, ValueType.BOOLEAN, ValueType.NUMBER) in issues)
    }

    @Test
    fun `trigger signal must be subscribable`() {
        val issues = issuesOf(routine(trigger = Trigger.SignalEdge(SignalIds.WIPER_FRONT_SWITCH, to = setOf(SignalValue.Text("AUTO")))))
        assertTrue(ValidationIssue.NotSubscribable(SignalIds.WIPER_FRONT_SWITCH) in issues)
    }

    @Test
    fun `trigger needs a target and target values must fit the signal`() {
        assertTrue(ValidationIssue.EmptyTarget in issuesOf(routine(trigger = Trigger.SignalEdge(SignalIds.WIPER_FRONT_STATE, to = emptySet()))))
        val wrongType = issuesOf(routine(trigger = Trigger.SignalEdge(SignalIds.WIPER_FRONT_STATE, to = setOf(SignalValue.Bool(true)))))
        assertTrue(ValidationIssue.TypeMismatch(SignalIds.WIPER_FRONT_STATE, ValueType.ENUM, ValueType.BOOLEAN) in wrongType)
    }

    @Test
    fun `time trigger and window must be inside the day`() {
        assertTrue(ValidationIssue.InvalidTime in issuesOf(routine(trigger = Trigger.TimeOfDay(24, 0))))
        assertTrue(ValidationIssue.InvalidTime in issuesOf(routine(condition = Condition.TimeWindow(0, 1440))))
        assertEquals(emptyList(), issuesOf(routine(trigger = Trigger.TimeOfDay(18, 30), condition = null)))
    }

    @Test
    fun `day type that matches no day is rejected`() {
        assertTrue(ValidationIssue.NeverMatches in issuesOf(routine(condition = Condition.DayType(weekdays = false, weekends = false))))
    }

    @Test
    fun `empty time window is rejected`() {
        assertTrue(ValidationIssue.NeverMatches in issuesOf(routine(condition = Condition.TimeWindow(600, 600))))
    }

    @Test
    fun `ordering operators are only for numbers`() {
        val issues = issuesOf(routine(condition = Condition.Compare(SignalIds.SEAT_OCCUPIED_DRIVER, CompareOp.GT, SignalValue.Bool(true))))
        assertTrue(ValidationIssue.InvalidOperator(SignalIds.SEAT_OCCUPIED_DRIVER, CompareOp.GT) in issues)
    }

    @Test
    fun `empty groups and too deep nesting are rejected`() {
        assertTrue(ValidationIssue.EmptyGroup in issuesOf(routine(condition = Condition.And(emptyList()))))
        var deep: Condition = Condition.DayType(weekdays = true, weekends = true)
        repeat(RoutineValidator.MAX_CONDITION_DEPTH + 1) { deep = Condition.Not(deep) }
        assertTrue(ValidationIssue.TooDeep in issuesOf(routine(condition = deep)))
    }

    @Test
    fun `routine needs an id, a name and a bounded number of actions`() {
        val issues = issuesOf(routine(actions = emptyList()).copy(name = " ", id = ""))
        assertTrue(ValidationIssue.BlankName in issues)
        assertTrue(ValidationIssue.BlankId in issues)
        assertTrue(ValidationIssue.NoActions in issues)
        val many = List(RoutineValidator.MAX_ACTIONS + 1) { Action.Notify("n") }
        assertTrue(ValidationIssue.TooManyActions in issuesOf(routine(actions = many)))
    }

    @Test
    fun `negative or unbounded limits are rejected`() {
        assertTrue(ValidationIssue.InvalidLimits in issuesOf(routine().copy(limits = Limits(cooldownMinutes = -1))))
        assertTrue(ValidationIssue.InvalidLimits in issuesOf(routine().copy(limits = Limits(maxPerDay = 0))))
        assertTrue(ValidationIssue.InvalidLimits in issuesOf(routine().copy(limits = Limits(cooldownMinutes = 100_000))))
    }

    @Test
    fun `unsupported schema version is rejected`() {
        assertTrue(ValidationIssue.UnsupportedSchema(99) in issuesOf(routine().copy(schemaVersion = 99)))
    }

    @Test
    fun `nested conditions are checked too`() {
        val unknown = SignalId("x.nested")
        val nested = Condition.And(listOf(Condition.Not(Condition.Compare(unknown, CompareOp.EQ, SignalValue.Bool(true)))))
        assertTrue(ValidationIssue.UnknownSignal(unknown) in issuesOf(routine(condition = nested)))
    }
}
