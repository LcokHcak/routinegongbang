package kr.routinegongbang.app.ui.model

import kr.routinegongbang.core.engine.ActionResult
import kr.routinegongbang.core.engine.ExecutionReport
import kr.routinegongbang.core.engine.ExecutorState
import kr.routinegongbang.core.engine.Reason
import kr.routinegongbang.core.engine.ResultUpdate
import kr.routinegongbang.core.engine.WriteLedger
import kr.routinegongbang.core.engine.WriteRateLimiter
import kr.routinegongbang.core.model.Action
import kr.routinegongbang.core.model.CompareOp
import kr.routinegongbang.core.model.Condition
import kr.routinegongbang.core.model.SignalCatalog
import kr.routinegongbang.core.model.SignalIds
import kr.routinegongbang.core.model.SignalValue
import kr.routinegongbang.core.model.Trigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RunHistoryTest {

    private val heat = SignalIds.STEERING_WHEEL_HEAT
    private val state = ExecutorState(WriteLedger(SignalCatalog.default()), WriteRateLimiter.default())

    private fun report(vararg results: ActionResult, dryRun: Boolean = false, simulated: Boolean = false) =
        ExecutionReport("r1", results.toList(), state, dryRun, simulated)

    @Test
    fun `a report becomes the latest run of its routine`() {
        val history = RunHistory().withReport(report(ActionResult.Sent(heat), ActionResult.Notified(null)), atEpochMs = 10)
        assertEquals(LastRun(listOf(ActionResult.Sent(heat), ActionResult.Notified(null)), atEpochMs = 10, dryRun = false, simulated = false), history["r1"])
        assertNull(history["other"])
    }

    @Test
    fun `a later confirmation replaces the sent result`() {
        val history = RunHistory().withReport(report(ActionResult.Sent(heat)), 10)
            .withUpdate(ResultUpdate("r1", 0, ActionResult.Confirmed(heat)))
        assertEquals(listOf<ActionResult>(ActionResult.Confirmed(heat)), history["r1"]?.results)
    }

    @Test
    fun `a confirmation from an older run never lands on the newer one`() {
        val newer = report(ActionResult.Sent(heat)).copy(runId = 2)
        val history = RunHistory().withReport(newer, 20)
            .withUpdate(ResultUpdate("r1", 0, ActionResult.SentUnconfirmed(heat), runId = 1))
        assertEquals(listOf<ActionResult>(ActionResult.Sent(heat)), history["r1"]?.results)
        assertEquals(listOf<ActionResult>(ActionResult.Confirmed(heat)), history.withUpdate(ResultUpdate("r1", 0, ActionResult.Confirmed(heat), runId = 2))["r1"]?.results)
    }

    @Test
    fun `an update only applies to a result still waiting, so a newer run is never overwritten`() {
        val history = RunHistory().withReport(report(ActionResult.Skipped(Reason.USER_OVERRIDE)), 20)
            .withUpdate(ResultUpdate("r1", 0, ActionResult.SentUnconfirmed(heat)))
            .withUpdate(ResultUpdate("r1", 5, ActionResult.Confirmed(heat)))
            .withUpdate(ResultUpdate("ghost", 0, ActionResult.Confirmed(heat)))
        assertEquals(listOf<ActionResult>(ActionResult.Skipped(Reason.USER_OVERRIDE)), history["r1"]?.results)
    }
}

class ActionStatusTest {

    @Test
    fun `every result maps to one of the user facing states with its reason`() {
        val heat = SignalIds.STEERING_WHEEL_HEAT
        assertEquals(StatusView(Status.CONFIRMED), StatusView.of(ActionResult.Confirmed(heat)))
        assertEquals(StatusView(Status.WAITING), StatusView.of(ActionResult.Sent(heat)))
        assertEquals(StatusView(Status.UNCONFIRMED), StatusView.of(ActionResult.SentUnconfirmed(heat)))
        assertEquals(StatusView(Status.FAILED, Reason.ACTION_FAILED), StatusView.of(ActionResult.Failed(Reason.ACTION_FAILED, "boom")))
        assertEquals(StatusView(Status.NOTIFIED_INSTEAD, Reason.SDK_NOT_OPEN), StatusView.of(ActionResult.Notified(Reason.SDK_NOT_OPEN)))
        assertEquals(StatusView(Status.NOTIFIED), StatusView.of(ActionResult.Notified(null)))
        assertEquals(StatusView(Status.SKIPPED, Reason.BODY_WHILE_DRIVING), StatusView.of(ActionResult.Skipped(Reason.BODY_WHILE_DRIVING)))
        assertEquals(StatusView(Status.CANCELLED, Reason.CONFIRM_DECLINED), StatusView.of(ActionResult.Cancelled(Reason.CONFIRM_DECLINED)))
        assertEquals(StatusView(Status.ALREADY_SET), StatusView.of(ActionResult.NoChange))
        assertEquals(StatusView(Status.LATER), StatusView.of(ActionResult.Deferred))
        assertEquals(StatusView(Status.REQUESTED), StatusView.of(ActionResult.Requested))
    }

    @Test
    fun `a notice held until parking is on its way, not told`() {
        // The card must not say "알려드렸어요" for a whole drive while the notice waits for parking.
        assertEquals(StatusView(Status.LATER), StatusView.of(ActionResult.Notified(null, held = true)))
        assertEquals(StatusView(Status.LATER), StatusView.of(ActionResult.Notified(Reason.SDK_NOT_OPEN, held = true)))
    }
}

class SummaryTest {

    @Test
    fun `triggers are summarised as a kind with arguments`() {
        assertEquals(TriggerSummary.Edge(SignalIds.WIPER_FRONT_STATE, listOf(SignalValue.Text("ON"))),
            TriggerSummary.of(Trigger.SignalEdge(SignalIds.WIPER_FRONT_STATE, to = setOf(SignalValue.Text("ON")))))
        assertEquals(TriggerSummary.Threshold(SignalIds.EV_SOC, CompareOp.GE, SignalValue.of(80)),
            TriggerSummary.of(Trigger.Threshold(SignalIds.EV_SOC, CompareOp.GE, SignalValue.of(80))))
        assertEquals(TriggerSummary.At(18, 5), TriggerSummary.of(Trigger.TimeOfDay(18, 5)))
        assertEquals(TriggerSummary.RideStart, TriggerSummary.of(Trigger.RideStart))
    }

    @Test
    fun `time windows in a condition are shown as clock ranges`() {
        val condition = Condition.And(listOf(Condition.DayType(weekdays = true, weekends = false), Condition.TimeWindow(18 * 60, 20 * 60)))
        assertEquals(listOf(ConditionSummary.Weekdays, ConditionSummary.Window("18:00", "20:00")), ConditionSummary.of(condition))
        assertEquals(listOf(ConditionSummary.Other), ConditionSummary.of(Condition.Not(Condition.DayType(true, false))))
        val windowOpen = Condition.Compare(SignalIds.WINDOW_POSITION_DRIVER, CompareOp.GT, SignalValue.of(0))
        assertEquals(listOf(ConditionSummary.Compare(SignalIds.WINDOW_POSITION_DRIVER, CompareOp.GT, SignalValue.of(0))), ConditionSummary.of(windowOpen))
        assertEquals(emptyList(), ConditionSummary.of(null))
    }

    @Test
    fun `actions keep their target for display`() {
        assertEquals(ActionSummary.Set(SignalIds.STEERING_WHEEL_HEAT, SignalValue.of(1)), ActionSummary.of(Action.Write(SignalIds.STEERING_WHEEL_HEAT, SignalValue.of(1))))
        assertEquals(ActionSummary.Navigate("home"), ActionSummary.of(Action.SetDestination("home")))
        assertEquals(ActionSummary.Message("routine_rain_done"), ActionSummary.of(Action.Notify("routine_rain_done")))
    }
}

/** Holds and waits on the main screen: results after a wait, and the new triggers and the wait in a card. */
class WaitOnScreenTest {

    private val heat = SignalIds.SEAT_HEAT_DRIVER
    private val state = ExecutorState(WriteLedger(SignalCatalog.default()), WriteRateLimiter.default())

    @Test
    fun `results after a wait are filled in by later updates of the same run`() {
        val run = ExecutionReport("r1", listOf(ActionResult.Sent(heat), ActionResult.Scheduled, ActionResult.Scheduled), state, dryRun = false).copy(runId = 3)
        val history = RunHistory().withReport(run, 10)
            .withUpdate(ResultUpdate("r1", 1, ActionResult.Waited, runId = 3))
            .withUpdate(ResultUpdate("r1", 2, ActionResult.Sent(heat), runId = 3))
            .withUpdate(ResultUpdate("r1", 2, ActionResult.Confirmed(heat), runId = 3))
        assertEquals(listOf(ActionResult.Sent(heat), ActionResult.Waited, ActionResult.Confirmed(heat)), history["r1"]?.results)
    }

    @Test
    fun `scheduled and waited have their own states`() {
        assertEquals(StatusView(Status.SCHEDULED), StatusView.of(ActionResult.Scheduled))
        assertEquals(StatusView(Status.WAITED), StatusView.of(ActionResult.Waited))
    }

    @Test
    fun `the new triggers and the wait are summarised for the card`() {
        val speed = Condition.Compare(SignalIds.VEHICLE_SPEED, CompareOp.GE, SignalValue.of(80))
        assertEquals(
            TriggerSummary.AllOf(listOf(ConditionSummary.Compare(SignalIds.VEHICLE_SPEED, CompareOp.GE, SignalValue.of(80))), holdSeconds = 600),
            TriggerSummary.of(Trigger.AllOf(listOf(speed), holdSeconds = 600)),
        )
        assertEquals(TriggerSummary.RideElapsed(30), TriggerSummary.of(Trigger.RideElapsed(30)))
        assertEquals(
            TriggerSummary.Threshold(SignalIds.VEHICLE_SPEED, CompareOp.GT, SignalValue.of(70), holdSeconds = 30),
            TriggerSummary.of(Trigger.Threshold(SignalIds.VEHICLE_SPEED, CompareOp.GT, SignalValue.of(70), holdSeconds = 30)),
        )
        assertEquals(ActionSummary.Wait(600), ActionSummary.of(Action.Wait(600)))
    }
}

/** Thirteen results are too many; the driver reads 했어요 / 알려드렸어요 / 안 했어요 plus one line. */
class OutcomeTest {

    private val heat = SignalIds.STEERING_WHEEL_HEAT

    @Test
    fun `finished results fall into three outcomes`() {
        val done = listOf(ActionResult.Confirmed(heat), ActionResult.NoChange, ActionResult.Requested, ActionResult.Waited)
        val told = listOf(ActionResult.Notified(null), ActionResult.Notified(Reason.SDK_NOT_OPEN))
        val notDone = listOf(
            ActionResult.Failed(Reason.ACTION_FAILED, "boom"),
            ActionResult.Skipped(Reason.BODY_WHILE_DRIVING),
            ActionResult.Cancelled(Reason.CONFIRM_DECLINED),
        )
        done.forEach { assertEquals(Outcome.DONE, StatusView.of(it).outcome, "$it") }
        told.forEach { assertEquals(Outcome.TOLD, StatusView.of(it).outcome, "$it") }
        notDone.forEach { assertEquals(Outcome.NOT_DONE, StatusView.of(it).outcome, "$it") }
    }

    @Test
    fun `a write the car never confirmed is never shown as done`() {
        // A successful call is not a completed action.
        assertEquals(Outcome.UNCONFIRMED, StatusView.of(ActionResult.SentUnconfirmed(heat)).outcome)
    }

    @Test
    fun `results still on their way are shown as in progress`() {
        assertEquals(Outcome.IN_PROGRESS, StatusView.of(ActionResult.Sent(heat)).outcome)
        assertEquals(Outcome.IN_PROGRESS, StatusView.of(ActionResult.Scheduled).outcome)
    }

    @Test
    fun `a reminder held until parking is not told yet`() {
        // No "알려드렸어요" before the driver saw anything; the ride may end before it is shown.
        assertEquals(Outcome.IN_PROGRESS, StatusView.of(ActionResult.Deferred).outcome)
    }

    @Test
    fun `every status belongs to exactly one outcome`() {
        assertEquals(Status.entries.toSet(), Outcome.entries.flatMap { it.statuses }.toSet())
        assertEquals(Status.entries.size, Outcome.entries.sumOf { it.statuses.size })
    }
}

/** "길안내: 집" fails when home is not registered, and the card must not say "차량 응답 오류". */
class NavigationFailureTest {

    @Test
    fun `a failed navigation is told apart from a failed setting`() {
        val failed = ActionResult.Failed(Reason.ACTION_FAILED, "'home' is not registered in the navigation bookmarks")
        assertEquals(true, StatusView.of(failed, Action.SetDestination("home")).navigation)
        assertEquals(false, StatusView.of(failed, Action.Write(SignalIds.HVAC_AC, SignalValue.Bool(true))).navigation)
    }
}
