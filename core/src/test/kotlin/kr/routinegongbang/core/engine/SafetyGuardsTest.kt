package kr.routinegongbang.core.engine

import kotlinx.coroutines.test.runTest
import kr.routinegongbang.core.model.Action
import kr.routinegongbang.core.model.Routine
import kr.routinegongbang.core.model.SignalIds
import kr.routinegongbang.core.model.SignalValue
import kr.routinegongbang.core.model.Trigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pausing a routine whose writes keep failing, and the reminder queue. */
class SafetyGuardsTest {

    private val yes = SignalValue.Bool(true)
    private val no = SignalValue.Bool(false)
    private val seated = mapOf(SignalIds.SEAT_OCCUPIED_DRIVER to yes)

    private fun onArrival(id: String, vararg actions: Action) = Routine(id = id, name = id, trigger = Trigger.NaviArrived, actions = actions.toList())

    @Test
    fun `a routine whose writes keep failing is paused after three runs, until it is edited`() = runTest {
        val heat = onArrival("heat", Action.Write(SignalIds.SEAT_HEAT_DRIVER, SignalValue.of(1)))
        val h = Harness(listOf(heat))
        h.writer.failing = true
        h.start(readings = seated)
        repeat(3) { h.engine.handle(EngineEvent.NaviArrived) }
        h.engine.handle(EngineEvent.NaviArrived)
        assertEquals(3, h.reports().size)
        assertTrue(Decision.LimitBlocked("heat", LimitReason.SUSPENDED) in h.observer.decisions)

        h.writer.failing = false
        h.engine.handle(EngineEvent.RoutinesChanged(listOf(validate(heat.copy(name = "heat, fixed")))))
        h.engine.handle(EngineEvent.NaviArrived)
        assertEquals(listOf(SignalIds.SEAT_HEAT_DRIVER to SignalValue.of(1)), h.writer.writes)
    }

    @Test
    fun `a routine edited while the service was down starts afresh after a restart`() = runTest {
        val heat = onArrival("heat", Action.Write(SignalIds.SEAT_HEAT_DRIVER, SignalValue.of(1)))
        val store = InMemoryEngineStore()
        val before = Harness(listOf(heat), store = store)
        before.writer.failing = true
        before.start(readings = seated)
        repeat(3) { before.engine.handle(EngineEvent.NaviArrived) }

        val unchanged = Harness(listOf(heat), store = store)
        unchanged.start(readings = seated)
        unchanged.engine.handle(EngineEvent.NaviArrived)
        assertTrue(unchanged.writer.writes.isEmpty(), "still suspended after a plain restart")

        val edited = Harness(listOf(heat.copy(name = "heat, fixed")), store = store)
        edited.start(readings = seated)
        edited.engine.handle(EngineEvent.NaviArrived)
        assertEquals(listOf(SignalIds.SEAT_HEAT_DRIVER to SignalValue.of(1)), edited.writer.writes)
    }

    @Test
    fun `only vehicle writes count - a failing notification does not pause a routine`() = runTest {
        val h = Harness(listOf(onArrival("tell", Action.Notify("n"), Action.Write(SignalIds.SEAT_HEAT_DRIVER, SignalValue.of(1)))))
        h.notifier.failing = true
        h.start(readings = seated)
        repeat(6) {
            h.engine.handle(EngineEvent.NaviArrived)
            h.signal(SignalIds.SEAT_HEAT_DRIVER, SignalValue.of(0))
        }
        assertEquals(6, h.reports().size)
    }

    @Test
    fun `downgrades and skips are not failures`() = runTest {
        val h = Harness(listOf(onArrival("heat", Action.Write(SignalIds.SEAT_HEAT_DRIVER, SignalValue.of(1)))))
        h.start(readings = seated, capability = CONTROL_CLOSED)
        repeat(4) { h.engine.handle(EngineEvent.NaviArrived) }
        assertEquals(4, h.reports().size, "notifying instead of writing is the design while Control is closed")
    }

    @Test
    fun `a body action skipped twice while driving is reminded once`() = runTest {
        val h = Harness(listOf(onArrival("a", Action.Write(SignalIds.DOOR_LOCK, yes)), Routine(
            id = "b", name = "b", trigger = Trigger.SignalEdge(SignalIds.SEAT_OCCUPIED_PASSENGER, to = setOf(yes)),
            actions = listOf(Action.Write(SignalIds.DOOR_LOCK, yes)),
        )))
        h.start(readings = seated + (SignalIds.SEAT_OCCUPIED_PASSENGER to no) + (SignalIds.DOOR_LOCK to no), driving = DRIVING)
        h.engine.handle(EngineEvent.NaviArrived)
        h.signal(SignalIds.SEAT_OCCUPIED_PASSENGER, yes)
        assertEquals(1, h.engine.snapshot.reminders.size)
    }

    @Test
    fun `a reminder whose goal was reached meanwhile is dropped instead of shown`() = runTest {
        val h = Harness(listOf(onArrival("lock", Action.Write(SignalIds.DOOR_LOCK, yes))))
        h.start(readings = seated + (SignalIds.DOOR_LOCK to no), driving = DRIVING)
        h.engine.handle(EngineEvent.NaviArrived)
        h.signal(SignalIds.DOOR_LOCK, yes)
        h.engine.handle(EngineEvent.DrivingChanged(PARKED))
        assertTrue(h.notifier.messages.isEmpty())
        assertTrue(h.engine.snapshot.reminders.isEmpty())
    }
}
