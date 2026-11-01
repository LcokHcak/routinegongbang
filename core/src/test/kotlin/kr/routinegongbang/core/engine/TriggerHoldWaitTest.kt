package kr.routinegongbang.core.engine

import kotlinx.coroutines.test.runTest
import kr.routinegongbang.core.model.Action
import kr.routinegongbang.core.model.CompareOp
import kr.routinegongbang.core.model.Condition
import kr.routinegongbang.core.model.Limits
import kr.routinegongbang.core.model.Policy
import kr.routinegongbang.core.model.Routine
import kr.routinegongbang.core.model.SignalIds
import kr.routinegongbang.core.model.SignalValue
import kr.routinegongbang.core.model.Trigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** All-met triggers, hold times, ride-elapsed triggers, waits and ask-before-run. */
class TriggerHoldWaitTest {

    private val yes = SignalValue.Bool(true)
    private val no = SignalValue.Bool(false)
    private val drive = SignalValue.Text("DRIVE")
    private val park = SignalValue.Text("PARK")
    private val on = SignalValue.Text("ON")
    private val off = SignalValue.Text("OFF")

    private fun kph(v: Int) = SignalValue.of(v)
    private fun notify(
        id: String,
        trigger: Trigger,
        vararg actions: Action = arrayOf(Action.Notify(id)),
        condition: Condition? = null,
        policy: Policy = Policy(),
        limits: Limits = Limits(),
    ) =
        Routine(id = id, name = id, trigger = trigger, condition = condition, actions = actions.toList(), policy = policy, limits = limits)

    /** L1-6: a value compared by size needs a cooldown; a hold time does not replace it. */
    private fun held(id: String, trigger: Trigger) = notify(id, trigger, limits = Limits(cooldownMinutes = 10))

    private suspend fun Harness.after(seconds: Int) {
        clock.advance(seconds * 1_000L)
        engine.handle(EngineEvent.Tick)
    }

    private fun Harness.ran(id: String) = reports().count { it.routineId == id }

    // --- all met ---

    private val beltAndDrive = Trigger.AllOf(
        listOf(Condition.Compare(SignalIds.SEATBELT_DRIVER, CompareOp.EQ, SignalValue.Bool(true)), Condition.Compare(SignalIds.GEAR_SELECTION, CompareOp.EQ, SignalValue.Text("DRIVE"))),
    )

    @Test
    fun `all met fires when the last condition comes true, whichever comes first`() = runTest {
        val beltFirst = Harness(listOf(notify("go", beltAndDrive)))
        beltFirst.start(readings = mapOf(SignalIds.SEATBELT_DRIVER to no, SignalIds.GEAR_SELECTION to park))
        beltFirst.signal(SignalIds.SEATBELT_DRIVER, yes)
        assertEquals(0, beltFirst.ran("go"))
        beltFirst.signal(SignalIds.GEAR_SELECTION, drive)
        assertEquals(1, beltFirst.ran("go"))

        val gearFirst = Harness(listOf(notify("go", beltAndDrive)))
        gearFirst.start(readings = mapOf(SignalIds.SEATBELT_DRIVER to no, SignalIds.GEAR_SELECTION to park))
        gearFirst.signal(SignalIds.GEAR_SELECTION, drive)
        assertEquals(0, gearFirst.ran("go"))
        gearFirst.signal(SignalIds.SEATBELT_DRIVER, yes)
        assertEquals(1, gearFirst.ran("go"), "the order the driver does things in must not matter")
    }

    @Test
    fun `all met fires once while everything stays met, and again after one part was undone`() = runTest {
        val h = Harness(listOf(notify("go", beltAndDrive)))
        h.start(readings = mapOf(SignalIds.SEATBELT_DRIVER to yes, SignalIds.GEAR_SELECTION to park))
        h.signal(SignalIds.GEAR_SELECTION, drive)
        h.signal(SignalIds.SEATBELT_DRIVER, yes)
        assertEquals(1, h.ran("go"))
        h.signal(SignalIds.SEATBELT_DRIVER, no)
        h.signal(SignalIds.SEATBELT_DRIVER, yes)
        assertEquals(2, h.ran("go"))
    }

    @Test
    fun `all met never fires on a value it cannot read`() = runTest {
        val h = Harness(listOf(notify("go", beltAndDrive)))
        h.start(readings = mapOf(SignalIds.GEAR_SELECTION to park))
        h.signal(SignalIds.GEAR_SELECTION, drive)
        assertEquals(0, h.ran("go"), "the belt was never read")
    }

    @Test
    fun `all met is not fired by the app's own write`() = runTest {
        // Only changes made outside the app fire routines.
        val heated = Trigger.AllOf(
            listOf(Condition.Compare(SignalIds.SEAT_HEAT_DRIVER, CompareOp.GE, SignalValue.of(1)), Condition.Compare(SignalIds.SEATBELT_DRIVER, CompareOp.EQ, yes)),
        )
        val arrive = Routine(id = "arrive", name = "arrive", trigger = Trigger.NaviArrived, actions = listOf(Action.Write(SignalIds.SEAT_HEAT_DRIVER, SignalValue.of(1))))
        val h = Harness(listOf(held("heated", heated), arrive))
        h.start(readings = mapOf(SignalIds.SEAT_HEAT_DRIVER to SignalValue.of(0), SignalIds.SEATBELT_DRIVER to yes, SignalIds.SEAT_OCCUPIED_DRIVER to yes))
        h.engine.handle(EngineEvent.NaviArrived)
        h.signal(SignalIds.SEAT_HEAT_DRIVER, SignalValue.of(1))
        assertEquals(0, h.ran("heated"))
    }

    // --- hold time ---

    private val sport = Trigger.Threshold(SignalIds.VEHICLE_SPEED, CompareOp.GT, kph(70), holdSeconds = 30)

    @Test
    fun `a held trigger runs only after its state lasted the hold time`() = runTest {
        val h = Harness(listOf(held("sport", sport)))
        h.start(readings = mapOf(SignalIds.VEHICLE_SPEED to kph(50)))
        h.signal(SignalIds.VEHICLE_SPEED, kph(75))
        assertEquals(0, h.ran("sport"), "not at the crossing")
        h.after(20)
        h.signal(SignalIds.VEHICLE_SPEED, kph(80))
        assertEquals(0, h.ran("sport"), "still above, but only 20 s")
        h.after(10)
        assertEquals(1, h.ran("sport"))
        h.after(60)
        assertEquals(1, h.ran("sport"), "a hold fires once per crossing")
    }

    @Test
    fun `a hold is cancelled when the state goes away, and starts again on the next crossing`() = runTest {
        val h = Harness(listOf(held("sport", sport)))
        h.start(readings = mapOf(SignalIds.VEHICLE_SPEED to kph(50)))
        h.signal(SignalIds.VEHICLE_SPEED, kph(75))
        h.after(20)
        h.signal(SignalIds.VEHICLE_SPEED, kph(65))
        h.after(20)
        assertEquals(0, h.ran("sport"))
        h.signal(SignalIds.VEHICLE_SPEED, kph(72))
        h.after(29)
        assertEquals(0, h.ran("sport"), "the new hold started at the second crossing")
        h.after(1)
        assertEquals(1, h.ran("sport"))
    }

    @Test
    fun `speed between 80 and 100 for ten minutes, the example from the editor research`() = runTest {
        val steady = Trigger.AllOf(
            listOf(Condition.Compare(SignalIds.VEHICLE_SPEED, CompareOp.GE, kph(80)), Condition.Compare(SignalIds.VEHICLE_SPEED, CompareOp.LE, kph(100))),
            holdSeconds = 600,
        )
        val h = Harness(listOf(held("steady", steady)))
        h.start(readings = mapOf(SignalIds.VEHICLE_SPEED to kph(60)))
        h.signal(SignalIds.VEHICLE_SPEED, kph(85))
        repeat(9) {
            h.after(60)
            h.signal(SignalIds.VEHICLE_SPEED, kph(if (it % 2 == 0) 95 else 88))
        }
        assertEquals(0, h.ran("steady"))
        h.after(60)
        assertEquals(1, h.ran("steady"))
    }

    @Test
    fun `a hold that ended before the next event runs before that event is applied`() = runTest {
        // The drop at 40 s comes after the hold is already complete at 30 s.
        val h = Harness(listOf(held("sport", sport)))
        h.start(readings = mapOf(SignalIds.VEHICLE_SPEED to kph(50)))
        h.signal(SignalIds.VEHICLE_SPEED, kph(75))
        h.clock.advance(40_000)
        h.signal(SignalIds.VEHICLE_SPEED, kph(60))
        assertEquals(1, h.ran("sport"))
    }

    @Test
    fun `the app's own write can end a hold but never start one`() = runTest {
        // Only changes made outside the app fire routines, holds included.
        val warm = held("warm", Trigger.Threshold(SignalIds.HVAC_TEMP_DRIVER, CompareOp.GE, SignalValue.of(24), holdSeconds = 60))
        val cool = Routine(id = "cool", name = "cool", trigger = Trigger.NaviArrived, actions = listOf(Action.Write(SignalIds.HVAC_TEMP_DRIVER, SignalValue.of(20))))
        val h = Harness(listOf(warm, cool))
        h.start(readings = mapOf(SignalIds.HVAC_TEMP_DRIVER to SignalValue.of(20), SignalIds.SEAT_OCCUPIED_DRIVER to yes))
        h.signal(SignalIds.HVAC_TEMP_DRIVER, SignalValue.of(25))
        h.engine.handle(EngineEvent.NaviArrived)
        h.signal(SignalIds.HVAC_TEMP_DRIVER, SignalValue.of(20))
        h.after(60)
        assertEquals(0, h.ran("warm"), "our write ended the hold")
    }

    @Test
    fun `the engine asks to be woken when a hold ends`() = runTest {
        val h = Harness(listOf(held("sport", sport)))
        h.start(readings = mapOf(SignalIds.VEHICLE_SPEED to kph(50)))
        assertEquals(null, h.engine.wakeInMs())
        h.signal(SignalIds.VEHICLE_SPEED, kph(75))
        assertEquals(30_000L, h.engine.wakeInMs())
        h.clock.advance(12_000)
        assertEquals(18_000L, h.engine.wakeInMs())
    }

    // --- ride elapsed ---

    @Test
    fun `ride elapsed fires once, when the trip has lasted the minutes`() = runTest {
        val h = Harness(listOf(notify("half", Trigger.RideElapsed(minutes = 30))))
        h.start(readings = mapOf(SignalIds.SEAT_OCCUPIED_DRIVER to no))
        h.signal(SignalIds.SEAT_OCCUPIED_DRIVER, yes)
        h.after(29 * 60)
        assertEquals(0, h.ran("half"))
        assertEquals(60_000L, h.engine.wakeInMs())
        h.after(60)
        assertEquals(1, h.ran("half"))
        h.after(60 * 60)
        assertEquals(1, h.ran("half"), "once per trip")
    }

    @Test
    fun `ride elapsed never fires outside a trip`() = runTest {
        val h = Harness(listOf(notify("half", Trigger.RideElapsed(minutes = 30))))
        h.start(readings = mapOf(SignalIds.SEAT_OCCUPIED_DRIVER to no))
        h.after(31 * 60)
        assertEquals(0, h.ran("half"))
    }

    // --- wait ---

    private val heatThenLower = Routine(
        id = "heat", name = "heat", trigger = Trigger.NaviArrived,
        actions = listOf(Action.Write(SignalIds.SEAT_HEAT_DRIVER, SignalValue.of(2)), Action.Wait(seconds = 600), Action.Write(SignalIds.SEAT_HEAT_DRIVER, SignalValue.of(1))),
    )
    private val seated = mapOf(SignalIds.SEAT_OCCUPIED_DRIVER to yes, SignalIds.SEAT_HEAT_DRIVER to SignalValue.of(0))

    @Test
    fun `actions after a wait run when it is over, reported on the same run`() = runTest {
        val h = Harness(listOf(heatThenLower))
        h.start(readings = seated)
        h.engine.handle(EngineEvent.NaviArrived)
        assertEquals(listOf(SignalIds.SEAT_HEAT_DRIVER to SignalValue.of(2)), h.writer.writes)
        val first = h.reports().single()
        assertEquals(ActionResult.Scheduled, first.results[1])
        assertEquals(ActionResult.Scheduled, first.results[2])
        assertEquals(600_000L, h.engine.wakeInMs())
        h.signal(SignalIds.SEAT_HEAT_DRIVER, SignalValue.of(2))
        h.after(600)
        assertEquals(SignalValue.of(1), h.writer.writes.last().second)
        val updates = h.observer.results.filter { it.runId == first.runId }
        assertTrue(updates.any { it.actionIndex == 1 && it.result == ActionResult.Waited })
        assertTrue(updates.any { it.actionIndex == 2 && it.result is ActionResult.Sent })
    }

    @Test
    fun `the rest of a wait runs even when the condition changed, so a step down is never lost`() = runTest {
        // Cancelling here would leave the seat at the higher level. Safety is checked per action.
        val belted = heatThenLower.copy(condition = Condition.Compare(SignalIds.SEATBELT_DRIVER, CompareOp.EQ, yes))
        val h = Harness(listOf(belted))
        h.start(readings = seated + (SignalIds.SEATBELT_DRIVER to yes))
        h.engine.handle(EngineEvent.NaviArrived)
        h.signal(SignalIds.SEAT_HEAT_DRIVER, SignalValue.of(2))
        h.signal(SignalIds.SEATBELT_DRIVER, no)
        h.after(600)
        assertEquals(SignalValue.of(1), h.writer.writes.last().second)
    }

    @Test
    fun `an approval given before a wait does not cover what comes after it`() = runTest {
        // A yes given while parked must not authorise a write ten minutes later.
        val asked = heatThenLower.copy(policy = Policy(askBeforeRun = true))
        val h = Harness(listOf(asked))
        h.start(readings = seated)
        h.engine.handle(EngineEvent.NaviArrived)
        assertEquals(1, h.confirmer.asked)
        h.signal(SignalIds.SEAT_HEAT_DRIVER, SignalValue.of(2))
        h.after(600)
        assertEquals(2, h.confirmer.asked, "the rest is asked about again")
        assertEquals(SignalValue.of(1), h.writer.writes.last().second)
    }

    @Test
    fun `waits that end together ask one question for all of them`() = runTest {
        // Found by the 10x random run (seed 604): two continuations asked one after the other (I14).
        fun asking(id: String, signal: kr.routinegongbang.core.model.SignalId) = Routine(
            id = id, name = id, trigger = Trigger.NaviArrived, policy = Policy(askBeforeRun = true),
            actions = listOf(Action.Notify(id), Action.Wait(60), Action.Write(signal, SignalValue.of(1))),
        )
        val h = Harness(listOf(asking("a", SignalIds.SEAT_HEAT_DRIVER), asking("b", SignalIds.STEERING_WHEEL_HEAT)))
        h.start(readings = seated + (SignalIds.STEERING_WHEEL_HEAT to SignalValue.of(0)))
        h.engine.handle(EngineEvent.NaviArrived)
        h.after(60)
        assertEquals(1, h.confirmer.asked)
        assertEquals(2, h.writer.writes.size)
    }

    @Test
    fun `a run that starts with a wait still counts toward once per trip`() = runTest {
        // The first part did nothing visible, but the run happened.
        val later = Routine(
            id = "later", name = "later", trigger = Trigger.NaviArrived, limits = Limits(oncePerTrip = true),
            actions = listOf(Action.Wait(60), Action.Write(SignalIds.SEAT_HEAT_DRIVER, SignalValue.of(1))),
        )
        val h = Harness(listOf(later))
        h.start(readings = seated)
        h.engine.handle(EngineEvent.NaviArrived)
        h.after(60)
        h.engine.handle(EngineEvent.NaviArrived)
        assertTrue(h.observer.decisions.any { it == Decision.LimitBlocked("later", LimitReason.TRIP_LIMIT) })
        assertEquals(1, h.writer.writes.size)
    }

    @Test
    fun `a wait survives a restart within the same trip`() = runTest {
        // Losing the step down would leave the seat at the higher level.
        val first = Harness(listOf(heatThenLower))
        first.start(readings = seated)
        first.engine.handle(EngineEvent.NaviArrived)
        first.signal(SignalIds.SEAT_HEAT_DRIVER, SignalValue.of(2))
        val second = Harness(listOf(heatThenLower), clock = first.clock, store = first.store)
        second.start(readings = seated + (SignalIds.SEAT_HEAT_DRIVER to SignalValue.of(2)))
        second.after(600)
        assertEquals(listOf(SignalIds.SEAT_HEAT_DRIVER to SignalValue.of(1)), second.writer.writes)
    }

    @Test
    fun `a resumed wait is saved as taken before it writes, so a crash never repeats it`() = runTest {
        // Like the counters, rather skip than repeat.
        val h = Harness(listOf(heatThenLower))
        h.start(readings = seated)
        h.engine.handle(EngineEvent.NaviArrived)
        h.signal(SignalIds.SEAT_HEAT_DRIVER, SignalValue.of(2))
        var savedAtWrite: EngineSnapshot? = null
        h.writer.onWrite = { savedAtWrite = h.store.saved }
        h.after(600)
        assertEquals(emptyList(), savedAtWrite!!.continuations)
    }

    @Test
    fun `write failures before a wait still count when the rest is cancelled`() = runTest {
        // Pausing a failing routine must not miss the failures of a run whose trip ended during its wait.
        val h = Harness(listOf(heatThenLower))
        h.start(readings = seated + (SignalIds.DOOR_LOCK to no))
        h.writer.failing = true
        h.engine.handle(EngineEvent.NaviArrived)
        h.signal(SignalIds.SEAT_OCCUPIED_DRIVER, no)
        h.signal(SignalIds.DOOR_LOCK, yes)
        h.after(600)
        assertEquals(1, h.engine.snapshot.counters.getValue("heat").failStreak)
    }

    @Test
    fun `the rest of a wait is cancelled when the driver has left and locked`() = runTest {
        // Comfort writes into an empty car help nobody.
        val h = Harness(listOf(heatThenLower))
        h.start(readings = seated + (SignalIds.DOOR_LOCK to no))
        h.engine.handle(EngineEvent.NaviArrived)
        h.signal(SignalIds.SEAT_OCCUPIED_DRIVER, no)
        h.signal(SignalIds.DOOR_LOCK, yes)
        h.after(600)
        assertEquals(1, h.writer.writes.size)
    }

    @Test
    fun `the rest of a wait still passes every safety rule, like a manual change by the user`() = runTest {
        val h = Harness(listOf(heatThenLower))
        h.start(readings = seated)
        h.engine.handle(EngineEvent.NaviArrived)
        h.signal(SignalIds.SEAT_HEAT_DRIVER, SignalValue.of(2))
        h.signal(SignalIds.SEAT_HEAT_DRIVER, SignalValue.of(3))
        h.after(600)
        assertEquals(1, h.writer.writes.size, "the driver turned it up by hand meanwhile")
    }

    @Test
    fun `a routine edited or removed during a wait does not continue`() = runTest {
        val h = Harness(listOf(heatThenLower))
        h.start(readings = seated)
        h.engine.handle(EngineEvent.NaviArrived)
        h.engine.handle(EngineEvent.RoutinesChanged(emptyList()))
        h.after(600)
        assertEquals(1, h.writer.writes.size)
        val run = h.reports().single().runId
        assertTrue(h.observer.results.any { it.runId == run && it.actionIndex == 2 && it.result == ActionResult.Cancelled(Reason.STATE_CHANGED) })
    }

    @Test
    fun `a dry run does not wait, so every action can be checked at once`() = runTest {
        // Found by the 10x random run (seed 143): a dry run's wait resumed later among real writes. Trying a routine
        // in the editor should show every action now; the safety rules still judge each one.
        val h = Harness(listOf(heatThenLower))
        h.start(readings = seated)
        h.engine.handle(EngineEvent.RunNow("heat"))
        val report = h.reports().single()
        assertTrue(report.dryRun)
        assertEquals(ActionResult.Waited, report.results[1])
        assertEquals(listOf(SignalValue.of(2), SignalValue.of(1)), h.writer.writes.map { it.second })
        assertEquals(null, h.engine.wakeInMs(), "nothing left waiting")
    }

    @Test
    fun `a routine still waiting ignores a new trigger`() = runTest {
        val h = Harness(listOf(heatThenLower))
        h.start(readings = seated)
        h.engine.handle(EngineEvent.NaviArrived)
        h.engine.handle(EngineEvent.NaviArrived)
        assertEquals(1, h.reports().size)
        assertTrue(h.observer.decisions.any { it == Decision.LimitBlocked("heat", LimitReason.STILL_WAITING) })
    }

    // --- ask before run ---

    @Test
    fun `ask before run asks even for a setting the routine could make on its own`() = runTest {
        val asked = Routine(
            id = "ask", name = "ask", trigger = Trigger.NaviArrived, policy = Policy(askBeforeRun = true),
            actions = listOf(Action.Write(SignalIds.SEAT_HEAT_DRIVER, SignalValue.of(1)), Action.Notify("done")),
        )
        val h = Harness(listOf(asked))
        h.confirmer.answer = false
        h.start(readings = seated)
        h.engine.handle(EngineEvent.NaviArrived)
        assertEquals(1, h.confirmer.asked)
        assertTrue(h.writer.writes.isEmpty())
    }
}
