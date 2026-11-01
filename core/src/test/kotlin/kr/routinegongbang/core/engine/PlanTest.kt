package kr.routinegongbang.core.engine

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kr.routinegongbang.core.model.Action
import kr.routinegongbang.core.model.Limits
import kr.routinegongbang.core.model.Routine
import kr.routinegongbang.core.model.SignalId
import kr.routinegongbang.core.model.SignalIds
import kr.routinegongbang.core.model.SignalValue
import kr.routinegongbang.core.model.Trigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Everything one input event triggers runs as one plan — arbitrated, confirmed once. */
class PlanTest {

    private val yes = SignalValue.Bool(true)
    private val no = SignalValue.Bool(false)
    private val seated = mapOf(SignalIds.SEAT_OCCUPIED_DRIVER to yes)

    private fun onArrival(id: String, vararg actions: Action, limits: Limits = Limits()) =
        Routine(id = id, name = id, trigger = Trigger.NaviArrived, actions = actions.toList(), limits = limits)

    private fun write(signal: SignalId, value: SignalValue) = Action.Write(signal, value)

    private fun Harness.resultsOf(id: String) = reports().single { it.routineId == id }.results

    @Test
    fun `H01 two routines writing the same signal on one event - only the first in the list writes`() = runTest {
        val h = Harness(listOf(onArrival("first", write(SignalIds.SEAT_HEAT_DRIVER, SignalValue.of(1))), onArrival("second", write(SignalIds.SEAT_HEAT_DRIVER, SignalValue.of(2)))))
        h.start(readings = seated)
        h.engine.handle(EngineEvent.NaviArrived)
        assertEquals(listOf(SignalIds.SEAT_HEAT_DRIVER to SignalValue.of(1)), h.writer.writes)
        assertEquals(listOf<ActionResult>(ActionResult.Skipped(Reason.CONFLICT)), h.resultsOf("second"))
    }

    @Test
    fun `H01 ride start and a seat edge caused by the same event form one plan`() = runTest {
        val onRide = Routine(id = "ride", name = "ride", trigger = Trigger.RideStart, actions = listOf(write(SignalIds.STEERING_WHEEL_HEAT, SignalValue.of(1))))
        val onSeat = Routine(
            id = "seat", name = "seat", trigger = Trigger.SignalEdge(SignalIds.SEAT_OCCUPIED_DRIVER, to = setOf(yes)),
            actions = listOf(write(SignalIds.STEERING_WHEEL_HEAT, SignalValue.of(2))),
        )
        val h = Harness(listOf(onRide, onSeat))
        h.start(readings = mapOf(SignalIds.SEAT_OCCUPIED_DRIVER to no))
        h.signal(SignalIds.SEAT_OCCUPIED_DRIVER, yes)
        assertEquals(listOf(SignalIds.STEERING_WHEEL_HEAT to SignalValue.of(1)), h.writer.writes)
        assertEquals(listOf<ActionResult>(ActionResult.Skipped(Reason.CONFLICT)), h.resultsOf("seat"))
    }

    @Test
    fun `H28 arbitration covers the signals the car changes along with a write`() = runTest {
        val h = Harness(
            listOf(onArrival("defrost", write(SignalIds.DEFROSTER_FRONT, yes)), onArrival("air", write(SignalIds.RECIRCULATION, yes))),
            catalog = coupledCatalog,
        )
        h.start(readings = seated)
        h.engine.handle(EngineEvent.NaviArrived)
        assertEquals(listOf<Pair<SignalId, SignalValue>>(SignalIds.DEFROSTER_FRONT to yes), h.writer.writes)
        assertEquals(listOf<ActionResult>(ActionResult.Skipped(Reason.CONFLICT)), h.resultsOf("air"))
    }

    @Test
    fun `H01 a routine that lost the arbitration still counts as fired, so it cannot win later and flip the value`() = runTest {
        val loser = onArrival("second", write(SignalIds.SEAT_HEAT_DRIVER, SignalValue.of(2)), limits = Limits(oncePerTrip = true))
        val h = Harness(listOf(onArrival("first", write(SignalIds.SEAT_HEAT_DRIVER, SignalValue.of(1))), loser))
        h.start(readings = seated)
        h.engine.handle(EngineEvent.NaviArrived)
        assertTrue("second" in h.engine.snapshot.counters)
    }

    @Test
    fun `H32 the whole plan asks once`() = runTest {
        val h = Harness(listOf(onArrival("lock", write(SignalIds.DOOR_LOCK, yes)), onArrival("hot", write(SignalIds.SEAT_HEAT_DRIVER, SignalValue.of(3)))))
        h.start(readings = seated)
        h.engine.handle(EngineEvent.NaviArrived)
        assertEquals(1, h.confirmer.asked)
        assertEquals(2, h.confirmer.requests.single().actions.size)
        assertEquals(2, h.writer.writes.size)
    }

    @Test
    fun `H12 a refused signal has no writer in the plan - it does not fall back to the next routine`() = runTest {
        val h = Harness(listOf(onArrival("hot", write(SignalIds.SEAT_HEAT_DRIVER, SignalValue.of(3))), onArrival("warm", write(SignalIds.SEAT_HEAT_DRIVER, SignalValue.of(1)))))
        h.confirmer.answer = false
        h.start(readings = seated)
        h.engine.handle(EngineEvent.NaviArrived)
        assertTrue(h.writer.writes.isEmpty())
        assertEquals(listOf<ActionResult>(ActionResult.Cancelled(Reason.CONFIRM_DECLINED)), h.resultsOf("hot"))
        assertEquals(listOf<ActionResult>(ActionResult.Skipped(Reason.CONFLICT)), h.resultsOf("warm"))
    }

    @Test
    fun `H31 H22 a change that arrives while the driver is deciding drops the rest of the plan`() = runTest {
        val h = Harness(listOf(onArrival("lock", write(SignalIds.DOOR_LOCK, yes)), onArrival("note", Action.Notify("n"))))
        h.confirmer.onAsk = { h.engine.post(EngineEvent.SignalChanged(SignalIds.SEAT_OCCUPIED_DRIVER, no)) }
        h.start(readings = seated)
        h.engine.handle(EngineEvent.NaviArrived)
        assertTrue(h.writer.writes.isEmpty())
        assertTrue(h.notifier.messages.isEmpty())
        assertEquals(listOf<ActionResult>(ActionResult.Cancelled(Reason.STATE_CHANGED)), h.resultsOf("lock"))
    }

    @Test
    fun `H07 a write before the echo is judged against the value the car is heading to, not the stale one`() = runTest {
        // Found by RandomCompositionTest seed 58: lowering to 20 and then "lowering" to 54 from a stale 60 reopens it.
        val second = Routine(
            id = "second", name = "second", trigger = Trigger.SignalEdge(SignalIds.SEAT_OCCUPIED_PASSENGER, to = setOf(yes)),
            actions = listOf(write(SignalIds.WINDOW_POSITION_DRIVER, SignalValue.of(54))),
        )
        val h = Harness(listOf(onArrival("first", write(SignalIds.WINDOW_POSITION_DRIVER, SignalValue.of(20))), second))
        h.start(readings = seated + (SignalIds.WINDOW_POSITION_DRIVER to SignalValue.of(60)) + (SignalIds.SEAT_OCCUPIED_PASSENGER to no))
        h.engine.handle(EngineEvent.NaviArrived)
        h.signal(SignalIds.SEAT_OCCUPIED_PASSENGER, yes)
        assertEquals(listOf<Pair<SignalId, SignalValue>>(SignalIds.WINDOW_POSITION_DRIVER to SignalValue.of(20)), h.writer.writes)
        assertEquals(listOf<ActionResult>(ActionResult.Skipped(Reason.UNSAFE_DIRECTION)), h.resultsOf("second"))
    }

    @Test
    fun `the same value written again before its echo is no change`() = runTest {
        val h = Harness(listOf(onArrival("a", write(SignalIds.SEAT_HEAT_DRIVER, SignalValue.of(1)))))
        h.start(readings = seated + (SignalIds.SEAT_HEAT_DRIVER to SignalValue.of(0)))
        h.engine.handle(EngineEvent.NaviArrived)
        h.engine.handle(EngineEvent.RunNow("a"))
        assertEquals(1, h.writer.writes.size)
        assertEquals(listOf<ActionResult>(ActionResult.NoChange), h.reports().last().results)
    }

    @Test
    fun `H31 a speed sample or an unrelated signal while the driver decides does not drop the plan`() = runTest {
        // The speed changes every second while driving, so counting it would drop every voice confirmation.
        val h = Harness(listOf(onArrival("lock", write(SignalIds.DOOR_LOCK, yes))))
        h.confirmer.onAsk = {
            h.engine.post(EngineEvent.SignalChanged(SignalIds.VEHICLE_SPEED, SignalValue.of(0)))
            h.engine.post(EngineEvent.SignalChanged(SignalIds.STEERING_WHEEL_HEAT, SignalValue.of(1)))
            h.engine.post(EngineEvent.DrivingChanged(PARKED))
            h.engine.post(EngineEvent.NaviArrived)
        }
        h.start(readings = seated)
        h.engine.handle(EngineEvent.NaviArrived)
        assertEquals(listOf<Pair<SignalId, SignalValue>>(SignalIds.DOOR_LOCK to yes), h.writer.writes)
    }

    @Test
    fun `H31 a user change of a signal the plan writes drops the plan`() = runTest {
        val h = Harness(listOf(onArrival("hot", write(SignalIds.SEAT_HEAT_DRIVER, SignalValue.of(3)))))
        h.confirmer.onAsk = { h.engine.post(EngineEvent.SignalChanged(SignalIds.SEAT_HEAT_DRIVER, SignalValue.of(1))) }
        h.start(readings = seated)
        h.engine.handle(EngineEvent.NaviArrived)
        assertTrue(h.writer.writes.isEmpty())
        assertEquals(listOf<ActionResult>(ActionResult.Cancelled(Reason.STATE_CHANGED)), h.resultsOf("hot"))
    }

    @Test
    fun `H31 a dropped plan is planned again once the change has been handled`() = runTest {
        val h = Harness(listOf(onArrival("lock", write(SignalIds.DOOR_LOCK, yes))))
        var first = true
        h.confirmer.onAsk = {
            if (first) h.engine.post(EngineEvent.SignalChanged(SignalIds.SEAT_OCCUPIED_PASSENGER, yes))
            first = false
        }
        h.start(readings = seated + (SignalIds.SEAT_OCCUPIED_PASSENGER to no))
        backgroundScope.launch { h.engine.runLoop() }
        h.engine.postAndAwait(EngineEvent.NaviArrived)
        h.engine.postAndAwait(EngineEvent.Tick)
        assertEquals(2, h.confirmer.asked, "asked again for the new state")
        assertEquals(listOf<Pair<SignalId, SignalValue>>(SignalIds.DOOR_LOCK to yes), h.writer.writes)
    }

    @Test
    fun `each echo confirms the run that wrote that value, in order`() = runTest {
        val second = Routine(
            id = "second", name = "second", trigger = Trigger.SignalEdge(SignalIds.SEAT_OCCUPIED_PASSENGER, to = setOf(yes)),
            actions = listOf(write(SignalIds.SEAT_HEAT_DRIVER, SignalValue.of(2))),
        )
        val h = Harness(listOf(onArrival("first", write(SignalIds.SEAT_HEAT_DRIVER, SignalValue.of(1))), second))
        h.start(readings = seated + (SignalIds.SEAT_HEAT_DRIVER to SignalValue.of(0)) + (SignalIds.SEAT_OCCUPIED_PASSENGER to no))
        h.engine.handle(EngineEvent.NaviArrived)
        h.signal(SignalIds.SEAT_OCCUPIED_PASSENGER, yes)
        val firstRun = h.reports().single { it.routineId == "first" }.runId
        h.signal(SignalIds.SEAT_HEAT_DRIVER, SignalValue.of(1))
        assertEquals(listOf(ResultUpdate("first", 0, ActionResult.Confirmed(SignalIds.SEAT_HEAT_DRIVER), firstRun)), h.observer.results)
        h.signal(SignalIds.SEAT_HEAT_DRIVER, SignalValue.of(2))
        assertEquals(ActionResult.Confirmed(SignalIds.SEAT_HEAT_DRIVER), h.observer.results.last { it.routineId == "second" }.result)
    }

    @Test
    fun `writing the position the window already has is no change, not a wrong direction`() = runTest {
        val h = Harness(listOf(onArrival("close", write(SignalIds.WINDOW_POSITION_DRIVER, SignalValue.of(0)))))
        h.start(readings = seated + (SignalIds.WINDOW_POSITION_DRIVER to SignalValue.of(0)))
        h.engine.handle(EngineEvent.NaviArrived)
        assertEquals(listOf<ActionResult>(ActionResult.NoChange), h.resultsOf("close"))
    }

    @Test
    fun `nobody is asked to approve a write that its rate limit would skip anyway`() = runTest {
        fun on(signal: SignalId, id: String, level: Int) = Routine(
            id = id, name = id, trigger = Trigger.SignalEdge(signal, to = setOf(yes)), actions = listOf(write(SignalIds.SEAT_HEAT_DRIVER, SignalValue.of(level))),
        )
        val h = Harness(listOf(onArrival("one", write(SignalIds.SEAT_HEAT_DRIVER, SignalValue.of(1))), on(SignalIds.SEAT_OCCUPIED_PASSENGER, "two", 2), on(SignalIds.DOOR_LOCK, "three", 3)))
        h.start(readings = seated + (SignalIds.SEAT_HEAT_DRIVER to SignalValue.of(0)) + (SignalIds.SEAT_OCCUPIED_PASSENGER to no) + (SignalIds.DOOR_LOCK to no))
        h.engine.handle(EngineEvent.NaviArrived)
        h.signal(SignalIds.SEAT_OCCUPIED_PASSENGER, yes)
        h.signal(SignalIds.DOOR_LOCK, yes)
        assertEquals(0, h.confirmer.asked)
        assertEquals(listOf<ActionResult>(ActionResult.Skipped(Reason.RATE_LIMITED)), h.resultsOf("three"))
    }

    @Test
    fun `a tick arriving during the confirmation does not drop the plan`() = runTest {
        val h = Harness(listOf(onArrival("lock", write(SignalIds.DOOR_LOCK, yes))))
        h.confirmer.onAsk = { h.engine.post(EngineEvent.Tick) }
        h.start(readings = seated)
        h.engine.handle(EngineEvent.NaviArrived)
        assertEquals(listOf<Pair<SignalId, SignalValue>>(SignalIds.DOOR_LOCK to yes), h.writer.writes)
    }
}
