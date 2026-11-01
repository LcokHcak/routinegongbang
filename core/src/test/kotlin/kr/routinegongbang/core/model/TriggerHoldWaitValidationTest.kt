package kr.routinegongbang.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the new triggers and the wait action accept when a routine is saved, and how they are stored. */
class TriggerHoldWaitValidationTest {

    private val validator = RoutineValidator(SignalCatalog.default())
    private val yes = SignalValue.Bool(true)
    private val speed = SignalIds.VEHICLE_SPEED

    // L1-6 asks size-compared triggers for a cooldown; that is tested in the lint tests, not here.
    private fun routine(trigger: Trigger = Trigger.NaviArrived, actions: List<Action> = listOf(Action.Notify("n")), policy: Policy = Policy()) =
        Routine(id = "r", name = "r", trigger = trigger, actions = actions, policy = policy, limits = Limits(cooldownMinutes = 10))

    private fun issues(r: Routine) = validator.validate(r)
    private fun between(low: Int, high: Int, hold: Int = 0) = Trigger.AllOf(
        listOf(Condition.Compare(speed, CompareOp.GE, SignalValue.of(low)), Condition.Compare(speed, CompareOp.LE, SignalValue.of(high))),
        holdSeconds = hold,
    )

    @Test
    fun `all met with a reachable range and a hold time is valid`() {
        assertEquals(emptyList(), issues(routine(between(80, 100, hold = 600))))
    }

    @Test
    fun `all met needs between 1 and 8 comparisons`() {
        assertTrue(ValidationIssue.EmptyGroup in issues(routine(Trigger.AllOf(emptyList()))))
        val nine = Trigger.AllOf(List(9) { Condition.Compare(speed, CompareOp.GE, SignalValue.of(it)) })
        assertTrue(ValidationIssue.TooManyConditions in issues(routine(nine)))
    }

    @Test
    fun `all met checks every comparison like a condition`() {
        val bad = Trigger.AllOf(listOf(Condition.Compare(SignalIds.SEATBELT_DRIVER, CompareOp.GT, yes)))
        assertTrue(ValidationIssue.InvalidOperator(SignalIds.SEATBELT_DRIVER, CompareOp.GT) in issues(routine(bad)))
        val unknown = Trigger.AllOf(listOf(Condition.Compare(SignalId("nope"), CompareOp.EQ, yes)))
        assertTrue(ValidationIssue.UnknownSignal(SignalId("nope")) in issues(routine(unknown)))
    }

    @Test
    fun `all met that contradicts itself can never fire`() {
        assertTrue(ValidationIssue.NeverMatches in issues(routine(between(100, 80))))
        val twoValues = Trigger.AllOf(
            listOf(Condition.Compare(SignalIds.SEATBELT_DRIVER, CompareOp.EQ, yes), Condition.Compare(SignalIds.SEATBELT_DRIVER, CompareOp.EQ, SignalValue.Bool(false))),
        )
        assertTrue(ValidationIssue.NeverMatches in issues(routine(twoValues)))
    }

    @Test
    fun `hold times are 0 to 3600 seconds on every trigger that has one`() {
        assertTrue(ValidationIssue.InvalidDuration in issues(routine(between(80, 100, hold = 3601))))
        assertTrue(ValidationIssue.InvalidDuration in issues(routine(Trigger.Threshold(speed, CompareOp.GT, SignalValue.of(70), holdSeconds = -1))))
        assertTrue(ValidationIssue.InvalidDuration in issues(routine(Trigger.SignalEdge(SignalIds.SEATBELT_DRIVER, to = setOf(yes), holdSeconds = 4000))))
        assertEquals(emptyList(), issues(routine(Trigger.Threshold(speed, CompareOp.GT, SignalValue.of(70), holdSeconds = 3600))))
    }

    @Test
    fun `ride elapsed is 1 to 600 minutes`() {
        assertEquals(emptyList(), issues(routine(Trigger.RideElapsed(30))))
        assertTrue(ValidationIssue.InvalidDuration in issues(routine(Trigger.RideElapsed(0))))
        assertTrue(ValidationIssue.InvalidDuration in issues(routine(Trigger.RideElapsed(601))))
    }

    @Test
    fun `waits are 10 to 600 seconds, at most 1800 in total, and count as actions`() {
        assertEquals(emptyList(), issues(routine(actions = listOf(Action.Notify("a"), Action.Wait(600), Action.Notify("b")))))
        assertTrue(ValidationIssue.InvalidDuration in issues(routine(actions = listOf(Action.Wait(9), Action.Notify("b")))), "short waits invite on-off flicker")
        assertTrue(ValidationIssue.InvalidDuration in issues(routine(actions = listOf(Action.Wait(601), Action.Notify("b")))))
        val long = List(4) { Action.Wait(600) } + Action.Notify("b")
        assertTrue(ValidationIssue.InvalidDuration in issues(routine(actions = long)), "2400 s of waiting in total")
        assertTrue(ValidationIssue.TooManyActions in issues(routine(actions = List(20) { Action.Notify("n") } + Action.Wait(10))))
    }

    @Test
    fun `a routine of nothing but waits does nothing and is rejected`() {
        assertTrue(ValidationIssue.NoActions in issues(routine(actions = listOf(Action.Wait(10)))))
    }

    @Test
    fun `new triggers, the wait and ask before run survive a JSON round trip`() {
        val r = Routine(
            id = "steady", name = "정속", trigger = between(80, 100, hold = 600), policy = Policy(askBeforeRun = true), limits = Limits(cooldownMinutes = 10),
            actions = listOf(Action.Write(SignalIds.HVAC_AC, yes), Action.Wait(300), Action.Notify("n")),
        )
        val json = RoutineJson.encode(r)
        assertEquals(r, RoutineJson.decode(json).getOrThrow())
        assertTrue("\"type\":\"AllOf\"" in json && "\"holdSeconds\":600" in json && "\"type\":\"Wait\"" in json, json)
        assertEquals(Trigger.RideElapsed(30), RoutineJson.decode(RoutineJson.encode(routine(Trigger.RideElapsed(30)))).getOrThrow().trigger)
    }

    @Test
    fun `routines saved before holds and waits existed still load with no hold and no question`() {
        val old = """{"schemaVersion":1,"id":"a","name":"a","trigger":{"type":"Threshold","signal":"vehicle.speed","op":"GT","value":70},"actions":[{"type":"Notify","textRes":"n"}]}"""
        val decoded = RoutineJson.decode(old).getOrThrow()
        assertEquals(0, (decoded.trigger as Trigger.Threshold).holdSeconds)
        assertEquals(false, decoded.policy.askBeforeRun)
    }
}
