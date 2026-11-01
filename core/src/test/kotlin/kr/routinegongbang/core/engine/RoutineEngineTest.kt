package kr.routinegongbang.core.engine

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kr.routinegongbang.core.model.Action
import kr.routinegongbang.core.model.CompareOp
import kr.routinegongbang.core.model.Condition
import kr.routinegongbang.core.model.Limits
import kr.routinegongbang.core.model.Routine
import kr.routinegongbang.core.model.SignalId
import kr.routinegongbang.core.model.SignalIds
import kr.routinegongbang.core.model.SignalValue
import kr.routinegongbang.core.model.Trigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoutineEngineTest {

    private val on = SignalValue.Text("ON")
    private val off = SignalValue.Text("OFF")
    private val yes = SignalValue.Bool(true)
    private val no = SignalValue.Bool(false)

    private val rain = Routine(
        id = "rain", name = "비 오면",
        trigger = Trigger.SignalEdge(SignalIds.WIPER_FRONT_STATE, to = setOf(on)),
        actions = listOf(Action.Write(SignalIds.DEFROSTER_FRONT, yes), Action.Write(SignalIds.RECIRCULATION, yes)),
    )
    private val defrostFollower = Routine(
        id = "follower", name = "성에 알림",
        trigger = Trigger.SignalEdge(SignalIds.DEFROSTER_FRONT, to = setOf(yes)),
        actions = listOf(Action.Notify("notify.defrost")),
    )
    private fun onArrival(vararg actions: Action, condition: Condition? = null) =
        Routine(id = "arrive", name = "도착", trigger = Trigger.NaviArrived, condition = condition, actions = actions.toList())

    @Test
    fun `threshold fires on a crossing from outside the app but not on the app's own write`() = runTest {
        val trigger = Trigger.Threshold(SignalIds.HVAC_TEMP_DRIVER, CompareOp.GE, SignalValue.of(24))
        val warm = Routine(id = "warm", name = "따뜻", trigger = trigger, actions = listOf(Action.Notify("warm")), limits = Limits(cooldownMinutes = 10))
        val h = Harness(listOf(warm, onArrival(Action.Write(SignalIds.HVAC_TEMP_DRIVER, SignalValue.of(26)))))
        h.start(readings = mapOf(SignalIds.HVAC_TEMP_DRIVER to SignalValue.of(20), SignalIds.SEAT_OCCUPIED_DRIVER to yes))
        h.engine.handle(EngineEvent.NaviArrived)
        h.signal(SignalIds.HVAC_TEMP_DRIVER, SignalValue.of(26))
        assertTrue(h.notifier.messages.isEmpty())
        h.signal(SignalIds.HVAC_TEMP_DRIVER, SignalValue.of(20))
        h.signal(SignalIds.HVAC_TEMP_DRIVER, SignalValue.of(25))
        assertEquals("warm", h.notifier.messages.single().textRes)
    }

    @Test
    fun `every run gets its own id so late confirmations reach the right run`() = runTest {
        val h = Harness(listOf(onArrival(Action.Notify("n"))))
        h.start()
        h.engine.handle(EngineEvent.NaviArrived)
        h.engine.handle(EngineEvent.RunNow("arrive"))
        val ids = h.reports().map { it.runId }
        assertEquals(2, ids.toSet().size)
        assertTrue(ids.all { it > 0 })
    }

    @Test
    fun `a trip restored while the car is already empty and locked ends after the usual delay`() = runTest {
        val restored = EngineSnapshot(trip = TripState(tripId = "old", startedAtEpochMs = kst("11:00:00").toEpochMilli()))
        val h = Harness(listOf(Routine(id = "bye", name = "bye", trigger = Trigger.RideEnd, actions = listOf(Action.Notify("bye")))), store = InMemoryEngineStore(restored))
        h.start(readings = mapOf(SignalIds.SEAT_OCCUPIED_DRIVER to no, SignalIds.DOOR_LOCK to yes))
        assertTrue(h.engine.snapshot.trip.endPendingSinceEpochMs != null)
        assertTrue(h.notifier.messages.isEmpty(), "startup readings never fire RideEnd")
        h.clock.advance(5 * 60_000)
        h.engine.handle(EngineEvent.Tick)
        assertEquals(TripState(), h.engine.snapshot.trip)
    }

    @Test
    fun `a trip restored while the car is moving is kept even if the seat reads empty and the doors locked`() = runTest {
        // Doors auto-lock while driving; a seat sensor read as false at that moment must not end the drive.
        val restored = EngineSnapshot(trip = TripState(tripId = "old", startedAtEpochMs = kst("11:00:00").toEpochMilli()))
        val h = Harness(emptyList(), store = InMemoryEngineStore(restored))
        h.start(readings = mapOf(SignalIds.SEAT_OCCUPIED_DRIVER to no, SignalIds.DOOR_LOCK to yes), driving = DrivingState(Gear.DRIVE, 50.0, true))
        assertEquals(null, h.engine.snapshot.trip.endPendingSinceEpochMs)
        assertEquals("old", h.engine.snapshot.trip.tripId)
    }

    @Test
    fun `H28 a change the car makes because of our write fires nothing, is no habit, and is left alone`() = runTest {
        val follower = Routine(
            id = "follower", name = "내기 알림",
            trigger = Trigger.SignalEdge(SignalIds.RECIRCULATION, to = setOf(no)),
            actions = listOf(Action.Notify("notify.recirculation")),
        )
        val h = Harness(listOf(follower, onArrival(Action.Write(SignalIds.DEFROSTER_FRONT, yes))), catalog = coupledCatalog)
        h.start(readings = mapOf(SignalIds.DEFROSTER_FRONT to no, SignalIds.RECIRCULATION to yes, SignalIds.SEAT_OCCUPIED_DRIVER to yes))
        h.engine.handle(EngineEvent.NaviArrived)
        h.signal(SignalIds.DEFROSTER_FRONT, yes)
        h.signal(SignalIds.RECIRCULATION, no)
        assertTrue(h.notifier.messages.isEmpty(), "the coupled change must not chain into another routine")
        // The car or the user changed it right after our write; either way the app leaves it alone this trip (seed 695).
        assertTrue(SignalIds.RECIRCULATION in h.engine.snapshot.trip.userOverrides)
        assertTrue(h.observer.userChanges.isEmpty(), "nor become a habit")
    }

    @Test
    fun `values read at startup never trigger`() = runTest {
        val h = Harness(listOf(rain))
        h.start(readings = mapOf(SignalIds.WIPER_FRONT_STATE to on))
        h.signal(SignalIds.WIPER_FRONT_STATE, on)
        assertTrue(h.writer.writes.isEmpty())
    }

    @Test
    fun `D1 rain turns on defrost and recirculation`() = runTest {
        val h = Harness(listOf(rain))
        h.start(readings = mapOf(SignalIds.WIPER_FRONT_STATE to off))
        h.signal(SignalIds.WIPER_FRONT_STATE, on)
        assertEquals(listOf<Pair<SignalId, SignalValue>>(SignalIds.DEFROSTER_FRONT to yes, SignalIds.RECIRCULATION to yes), h.writer.writes)
        assertEquals(1, h.reports().size)
    }

    @Test
    fun `H04 the app's own echo triggers nothing and confirms the result`() = runTest {
        val h = Harness(listOf(rain, defrostFollower))
        h.start(readings = mapOf(SignalIds.WIPER_FRONT_STATE to off, SignalIds.DEFROSTER_FRONT to no))
        h.signal(SignalIds.WIPER_FRONT_STATE, on)
        h.signal(SignalIds.DEFROSTER_FRONT, yes)
        assertTrue(h.notifier.messages.isEmpty(), "follower must not fire on the app's own write")
        assertTrue(ResultUpdate("rain", 0, ActionResult.Confirmed(SignalIds.DEFROSTER_FRONT), runId = 1) in h.observer.results)
    }

    @Test
    fun `a change made by the user does trigger`() = runTest {
        val h = Harness(listOf(defrostFollower))
        h.start(readings = mapOf(SignalIds.DEFROSTER_FRONT to no))
        h.signal(SignalIds.DEFROSTER_FRONT, yes)
        assertEquals("notify.defrost", h.notifier.messages.single().textRes)
    }

    @Test
    fun `a signal the user changed during the trip is not overwritten`() = runTest {
        val h = Harness(listOf(onArrival(Action.Write(SignalIds.STEERING_WHEEL_HEAT, SignalValue.of(2)))))
        h.start(readings = mapOf(SignalIds.SEAT_OCCUPIED_DRIVER to no, SignalIds.STEERING_WHEEL_HEAT to SignalValue.of(0)))
        h.signal(SignalIds.SEAT_OCCUPIED_DRIVER, yes)
        h.signal(SignalIds.STEERING_WHEEL_HEAT, SignalValue.of(1))
        h.engine.handle(EngineEvent.NaviArrived)
        assertTrue(h.writer.writes.isEmpty())
        assertEquals(listOf<ActionResult>(ActionResult.Skipped(Reason.USER_OVERRIDE)), h.reports().single().results)
        assertEquals(setOf(SignalIds.STEERING_WHEEL_HEAT), h.store.saved?.trip?.userOverrides)
    }

    @Test
    fun `user changes outside a trip are not overrides`() = runTest {
        val h = Harness(emptyList())
        h.start(readings = mapOf(SignalIds.STEERING_WHEEL_HEAT to SignalValue.of(0)))
        h.signal(SignalIds.STEERING_WHEEL_HEAT, SignalValue.of(1))
        assertTrue(h.engine.snapshot.trip.userOverrides.isEmpty())
    }

    @Test
    fun `cooldown blocks a second fire and the counters are persisted`() = runTest {
        val h = Harness(listOf(rain.copy(limits = Limits(cooldownMinutes = 30))))
        h.start(readings = mapOf(SignalIds.WIPER_FRONT_STATE to off))
        h.signal(SignalIds.WIPER_FRONT_STATE, on)
        h.signal(SignalIds.WIPER_FRONT_STATE, off)
        h.signal(SignalIds.WIPER_FRONT_STATE, on)
        assertEquals(1, h.reports().size)
        assertTrue(Decision.LimitBlocked("rain", LimitReason.COOLDOWN) in h.observer.decisions)
        assertEquals(1, h.store.saved?.counters?.get("rain")?.firedToday)
    }

    @Test
    fun `routine whose actions were all skipped is not counted`() = runTest {
        val h = Harness(listOf(onArrival(Action.Write(SignalIds.DOOR_LOCK, yes)).copy(limits = Limits(maxPerDay = 1))))
        h.start(driving = DRIVING)
        h.engine.handle(EngineEvent.NaviArrived)
        assertEquals(null, h.engine.snapshot.counters["arrive"])
    }

    @Test
    fun `H23 condition with a missing value does not run and says why`() = runTest {
        val h = Harness(listOf(onArrival(Action.Notify("n"), condition = Condition.Compare(SignalIds.EV_SOC, CompareOp.GE, SignalValue.of(80)))))
        h.start()
        h.engine.handle(EngineEvent.NaviArrived)
        assertTrue(h.notifier.messages.isEmpty())
        assertEquals(listOf<Decision>(Decision.ConditionNotMet("arrive", setOf(SignalIds.EV_SOC))), h.observer.decisions)
    }

    @Test
    fun `ride start fires when the driver sits down and the trip is persisted`() = runTest {
        val h = Harness(listOf(Routine(id = "hello", name = "출발", trigger = Trigger.RideStart, actions = listOf(Action.Notify("hello")))))
        h.start(readings = mapOf(SignalIds.SEAT_OCCUPIED_DRIVER to no))
        h.signal(SignalIds.SEAT_OCCUPIED_DRIVER, yes)
        assertEquals("hello", h.notifier.messages.single().textRes)
        assertEquals("trip-1", h.store.saved?.trip?.tripId)
    }

    @Test
    fun `driver already seated at startup starts a trip silently`() = runTest {
        val h = Harness(listOf(Routine(id = "hello", name = "출발", trigger = Trigger.RideStart, actions = listOf(Action.Notify("hello")))))
        h.start(readings = mapOf(SignalIds.SEAT_OCCUPIED_DRIVER to yes))
        assertTrue(h.notifier.messages.isEmpty())
        assertEquals("trip-1", h.engine.snapshot.trip.tripId)
    }

    @Test
    fun `D2 ride end fires when locking and the trip ends after five minutes`() = runTest {
        val windowOpen = Condition.Compare(SignalIds.WINDOW_POSITION_DRIVER, CompareOp.GT, SignalValue.of(0))
        val check = Routine(id = "lock", name = "창문", trigger = Trigger.RideEnd, condition = windowOpen, actions = listOf(Action.Notify("notify.window")))
        val h = Harness(listOf(check))
        h.start(readings = mapOf(SignalIds.SEAT_OCCUPIED_DRIVER to yes, SignalIds.DOOR_LOCK to no, SignalIds.WINDOW_POSITION_DRIVER to SignalValue.of(30)))
        h.signal(SignalIds.SEAT_OCCUPIED_DRIVER, no)
        h.signal(SignalIds.DOOR_LOCK, yes)
        assertEquals("notify.window", h.notifier.messages.single().textRes)
        h.clock.advance(5 * 60_000)
        h.engine.handle(EngineEvent.Tick)
        assertEquals(TripState(), h.engine.snapshot.trip)
    }

    @Test
    fun `time of day fires on the tick that crosses it`() = runTest {
        val sixPm = Routine(id = "six", name = "6시", trigger = Trigger.TimeOfDay(18, 0), actions = listOf(Action.SetDestination("home")))
        val h = Harness(listOf(sixPm), clock = FakeClock(kst("17:59:30")))
        h.start()
        h.clock.advance(40_000)
        h.engine.handle(EngineEvent.Tick)
        h.clock.advance(60_000)
        h.engine.handle(EngineEvent.Tick)
        assertEquals(listOf("home"), h.navigator.destinations)
    }

    @Test
    fun `a time that passed while the service was down is not caught up`() = runTest {
        val sixPm = Routine(id = "six", name = "6시", trigger = Trigger.TimeOfDay(18, 0), actions = listOf(Action.Notify("six")))
        val h = Harness(listOf(sixPm), clock = FakeClock(kst("18:05:00")))
        h.start()
        h.clock.advance(60_000)
        h.engine.handle(EngineEvent.Tick)
        assertTrue(h.notifier.messages.isEmpty())
    }

    @Test
    fun `remind waits for parking and survives a restart`() = runTest {
        val routines = listOf(onArrival(Action.Remind("remind.umbrella")))
        val first = Harness(routines)
        first.start(driving = DRIVING)
        first.engine.handle(EngineEvent.NaviArrived)
        assertTrue(first.notifier.messages.isEmpty())
        assertEquals(1, first.store.saved?.reminders?.size)

        val second = Harness(routines, store = first.store)
        second.start(driving = DRIVING)
        second.engine.handle(EngineEvent.DrivingChanged(PARKED))
        assertEquals("remind.umbrella", second.notifier.messages.single().textRes)
        assertTrue(second.engine.snapshot.reminders.isEmpty())
        assertTrue(Decision.ReminderShown("arrive", "remind.umbrella") in second.observer.decisions)
    }

    @Test
    fun `body action skipped while driving becomes a reminder after parking`() = runTest {
        val lock = Action.Write(SignalIds.DOOR_LOCK, yes)
        val h = Harness(listOf(onArrival(lock)))
        h.start(driving = DRIVING)
        h.engine.handle(EngineEvent.NaviArrived)
        h.engine.handle(EngineEvent.DrivingChanged(PARKED))
        val message = h.notifier.messages.single()
        assertEquals(Reason.BODY_WHILE_DRIVING, message.reason)
        assertEquals(lock, message.action)
        assertTrue(h.writer.writes.isEmpty())
    }

    @Test
    fun `run now ignores trigger, condition and limits and is not counted`() = runTest {
        val never = Condition.Compare(SignalIds.EV_SOC, CompareOp.GE, SignalValue.of(80))
        val h = Harness(listOf(onArrival(Action.Write(SignalIds.STEERING_WHEEL_HEAT, SignalValue.of(1)), condition = never)))
        h.start()
        h.engine.handle(EngineEvent.RunNow("arrive"))
        assertEquals(1, h.writer.writes.size)
        assertTrue(h.reports().single().dryRun)
        assertEquals(null, h.engine.snapshot.counters["arrive"])
    }

    @Test
    fun `ledger expiry marks writes without an echo as unconfirmed`() = runTest {
        val h = Harness(listOf(rain))
        h.start(readings = mapOf(SignalIds.WIPER_FRONT_STATE to off))
        h.signal(SignalIds.WIPER_FRONT_STATE, on)
        h.clock.advance(10_000)
        h.engine.handle(EngineEvent.Tick)
        assertEquals(
            setOf(ActionResult.SentUnconfirmed(SignalIds.DEFROSTER_FRONT), ActionResult.SentUnconfirmed(SignalIds.RECIRCULATION)),
            h.observer.results.map { it.result }.toSet(),
        )
    }

    @Test
    fun `disabled routine never fires`() = runTest {
        val h = Harness(listOf(rain.copy(enabled = false)))
        h.start(readings = mapOf(SignalIds.WIPER_FRONT_STATE to off))
        h.signal(SignalIds.WIPER_FRONT_STATE, on)
        assertTrue(h.writer.writes.isEmpty())
    }

    @Test
    fun `H31 driving off while a confirmation is open drops the plan instead of acting on the old state`() = runTest {
        val h = Harness(listOf(onArrival(Action.Write(SignalIds.DOOR_LOCK, yes))))
        h.confirmer.onAsk = { h.engine.post(EngineEvent.DrivingChanged(DRIVING)) }
        h.start(readings = mapOf(SignalIds.SEAT_OCCUPIED_DRIVER to yes))
        h.engine.handle(EngineEvent.NaviArrived)
        assertTrue(h.writer.writes.isEmpty())
        assertEquals(listOf<ActionResult>(ActionResult.Cancelled(Reason.STATE_CHANGED)), h.reports().single().results)
    }

    @Test
    fun `counters of a removed routine are kept for a day, then dropped`() = runTest {
        val h = Harness(listOf(rain))
        h.start(readings = mapOf(SignalIds.WIPER_FRONT_STATE to off))
        h.signal(SignalIds.WIPER_FRONT_STATE, on)
        h.engine.handle(EngineEvent.RoutinesChanged(listOf(validate(defrostFollower))))
        assertTrue("rain" in h.engine.snapshot.counters, "a transient partial list must not wipe limits")
        h.clock.advance(25 * 60 * 60_000L)
        h.engine.handle(EngineEvent.RoutinesChanged(listOf(validate(defrostFollower))))
        assertEquals(emptyMap(), h.engine.snapshot.counters)
    }

    @Test
    fun `a broken store does not stop the engine`() = runTest {
        val h = Harness(listOf(rain), engineStore = FailingStore())
        h.start(readings = mapOf(SignalIds.WIPER_FRONT_STATE to off))
        h.signal(SignalIds.WIPER_FRONT_STATE, on)
        assertEquals(2, h.writer.writes.size)
        assertTrue(h.observer.decisions.any { it is Decision.StoreFailed })
    }

    @Test
    fun `posted events are processed in order by the loop`() = runTest {
        val h = Harness(listOf(rain))
        h.start(readings = mapOf(SignalIds.WIPER_FRONT_STATE to off))
        val loop = launch { h.engine.runLoop() }
        h.engine.post(EngineEvent.SignalChanged(SignalIds.WIPER_FRONT_STATE, on))
        testScheduler.advanceUntilIdle()
        loop.cancel()
        assertEquals(2, h.writer.writes.size)
    }

    private val windowCheck = Routine(
        id = "lockcheck", name = "창문", trigger = Trigger.RideEnd, actions = listOf(Action.Notify("notify.window")),
    )

    @Test
    fun `H07 the app never locks once the driver has left, so its lock can never end a trip`() = runTest {
        val h = Harness(listOf(windowCheck, onArrival(Action.Write(SignalIds.DOOR_LOCK, yes))))
        h.start(readings = mapOf(SignalIds.SEAT_OCCUPIED_DRIVER to no, SignalIds.DOOR_LOCK to no))
        h.signal(SignalIds.SEAT_OCCUPIED_DRIVER, yes)
        h.signal(SignalIds.SEAT_OCCUPIED_DRIVER, no)
        h.engine.handle(EngineEvent.NaviArrived)
        assertTrue(h.writer.writes.isEmpty())
        assertTrue(h.confirmer.requests.isEmpty(), "nobody is there to answer")
        assertEquals(listOf<ActionResult>(ActionResult.Skipped(Reason.NO_ONE_TO_CONFIRM)), h.reports().single().results)
        assertEquals(null, h.engine.snapshot.trip.endPendingSinceEpochMs)
    }

    @Test
    fun `auto-lock while the driver is seated does not fire ride end`() = runTest {
        val h = Harness(listOf(windowCheck))
        h.start(readings = mapOf(SignalIds.SEAT_OCCUPIED_DRIVER to yes, SignalIds.DOOR_LOCK to no))
        h.signal(SignalIds.DOOR_LOCK, yes)
        assertTrue(h.notifier.messages.isEmpty())
    }

    @Test
    fun `H24 a small backwards clock correction does not fire the same time twice`() = runTest {
        val sixPm = Routine(id = "six", name = "6시", trigger = Trigger.TimeOfDay(18, 0), actions = listOf(Action.Notify("six")))
        val h = Harness(listOf(sixPm), clock = FakeClock(kst("17:59:30")))
        h.start()
        h.clock.now = kst("18:00:10")
        h.engine.handle(EngineEvent.Tick)
        h.clock.now = kst("17:59:50")
        h.engine.handle(EngineEvent.Tick)
        h.clock.now = kst("18:00:20")
        h.engine.handle(EngineEvent.Tick)
        assertEquals(1, h.notifier.messages.size)
    }

    @Test
    fun `an unreadable store is never overwritten and cooldowns fail safe`() = runTest {
        val store = UnreadableStore()
        val h = Harness(listOf(rain.copy(limits = Limits(cooldownMinutes = 30))), engineStore = store)
        h.start(readings = mapOf(SignalIds.WIPER_FRONT_STATE to off))
        h.signal(SignalIds.WIPER_FRONT_STATE, on)
        assertEquals(0, store.saves)
        assertTrue(h.writer.writes.isEmpty())
        assertTrue(Decision.LimitBlocked("rain", LimitReason.COOLDOWN) in h.observer.decisions)
    }

    @Test
    fun `reminders stop as soon as driving starts again`() = runTest {
        val h = Harness(listOf(onArrival(Action.Remind("remind.a"), Action.Remind("remind.b"))))
        h.notifier.onNotify = { h.engine.post(EngineEvent.DrivingChanged(DRIVING)) }
        h.start(driving = DRIVING)
        h.engine.handle(EngineEvent.NaviArrived)
        h.engine.handle(EngineEvent.DrivingChanged(PARKED))
        assertEquals(listOf("remind.a"), h.notifier.messages.map { it.textRes })
        assertEquals(listOf("remind.b"), h.engine.snapshot.reminders.map { it.textRes })
        assertEquals(listOf("remind.b"), h.store.saved?.reminders?.map { it.textRes })
    }

    @Test
    fun `the same reminder is queued only once`() = runTest {
        val h = Harness(listOf(onArrival(Action.Write(SignalIds.DOOR_LOCK, yes))))
        h.start(driving = DRIVING)
        h.engine.handle(EngineEvent.NaviArrived)
        h.engine.handle(EngineEvent.NaviArrived)
        assertEquals(1, h.engine.snapshot.reminders.size)
    }

    @Test
    fun `a run that changed nothing is not counted`() = runTest {
        val heat = Action.Write(SignalIds.STEERING_WHEEL_HEAT, SignalValue.of(1))
        val h = Harness(listOf(onArrival(heat).copy(limits = Limits(maxPerDay = 1))))
        h.start(readings = mapOf(SignalIds.STEERING_WHEEL_HEAT to SignalValue.of(1)))
        h.engine.handle(EngineEvent.NaviArrived)
        h.engine.handle(EngineEvent.NaviArrived)
        assertEquals(2, h.reports().size)
        assertEquals(null, h.engine.snapshot.counters["arrive"])
    }

    @Test
    fun `counters are saved before a confirmation is awaited and rolled back if declined`() = runTest {
        val h = Harness(listOf(onArrival(Action.Write(SignalIds.DOOR_LOCK, yes))))
        var savedWhileAsking: RoutineCounters? = null
        h.confirmer.onAsk = { savedWhileAsking = h.store.saved?.counters?.get("arrive") }
        h.confirmer.answer = false
        h.start(readings = mapOf(SignalIds.SEAT_OCCUPIED_DRIVER to yes))
        h.engine.handle(EngineEvent.NaviArrived)
        assertTrue(savedWhileAsking != null)
        assertEquals(null, h.store.saved?.counters?.get("arrive"))
    }

    @Test
    fun `the loop survives an event that throws`() = runTest {
        var thrown = false
        val failingIds = {
            if (!thrown) {
                thrown = true
                error("id source broken")
            }
            "trip-ok"
        }
        val h = Harness(listOf(onArrival(Action.Notify("n"))), newTripId = failingIds)
        h.start(readings = mapOf(SignalIds.SEAT_OCCUPIED_DRIVER to no))
        h.engine.post(EngineEvent.SignalChanged(SignalIds.SEAT_OCCUPIED_DRIVER, yes))
        val loop = launch { h.engine.runLoop() }
        h.engine.post(EngineEvent.NaviArrived)
        h.engine.post(EngineEvent.NaviArrived)
        testScheduler.advanceUntilIdle()
        loop.cancel()
        assertEquals(2, h.notifier.messages.size)
        assertTrue(h.observer.decisions.any { it is Decision.EventFailed })
    }

    @Test
    fun `a stale trip restored at startup is ended and a new one starts`() = runTest {
        val old = EngineSnapshot(
            trip = TripState(tripId = "old", startedAtEpochMs = kst("06:00:00").toEpochMilli(), userOverrides = setOf(SignalIds.STEERING_WHEEL_HEAT)),
        )
        val h = Harness(emptyList(), clock = FakeClock(kst("20:00:00")), store = InMemoryEngineStore(old))
        h.start(readings = mapOf(SignalIds.SEAT_OCCUPIED_DRIVER to yes))
        assertEquals("trip-1", h.engine.snapshot.trip.tripId)
        assertTrue(h.engine.snapshot.trip.userOverrides.isEmpty())
    }

    @Test
    fun `habit hooks see trip starts and user changes but never the app's own writes`() = runTest {
        val h = Harness(listOf(rain))
        h.start(
            readings = mapOf(
                SignalIds.SEAT_OCCUPIED_DRIVER to no, SignalIds.WIPER_FRONT_STATE to off,
                SignalIds.DEFROSTER_FRONT to no, SignalIds.STEERING_WHEEL_HEAT to SignalValue.of(0),
            ),
        )
        h.signal(SignalIds.SEAT_OCCUPIED_DRIVER, yes)
        h.signal(SignalIds.STEERING_WHEEL_HEAT, SignalValue.of(1))
        h.signal(SignalIds.WIPER_FRONT_STATE, on)
        h.signal(SignalIds.DEFROSTER_FRONT, yes)
        assertEquals(listOf("trip-1"), h.observer.tripsStarted)
        assertTrue(Triple<String?, SignalId, SignalValue>("trip-1", SignalIds.STEERING_WHEEL_HEAT, SignalValue.of(1)) in h.observer.userChanges)
        assertTrue(h.observer.userChanges.none { it.second == SignalIds.DEFROSTER_FRONT })
    }

    @Test
    fun `a trip started silently at startup is not a real ride start and is not given to habits`() = runTest {
        val h = Harness(emptyList())
        h.start(readings = mapOf(SignalIds.SEAT_OCCUPIED_DRIVER to yes))
        assertTrue(h.observer.tripsStarted.isEmpty())
    }

    private suspend fun Harness.startTrip(vararg readings: Pair<SignalId, SignalValue>) {
        start(readings = mapOf(SignalIds.SEAT_OCCUPIED_DRIVER to no, *readings))
        signal(SignalIds.SEAT_OCCUPIED_DRIVER, yes)
    }

    @Test
    fun `values that did not change or were never seen before are not habits`() = runTest {
        val h = Harness(emptyList())
        h.startTrip(SignalIds.STEERING_WHEEL_HEAT to SignalValue.of(1))
        h.signal(SignalIds.STEERING_WHEEL_HEAT, SignalValue.of(1))
        h.signal(SignalIds.SEAT_HEAT_DRIVER, SignalValue.of(2))
        assertTrue(h.observer.userChanges.none { it.second != SignalIds.SEAT_OCCUPIED_DRIVER })
    }

    @Test
    fun `a late or repeated echo of the app's own write is not a habit`() = runTest {
        val h = Harness(listOf(onArrival(Action.Write(SignalIds.STEERING_WHEEL_HEAT, SignalValue.of(2)))))
        h.startTrip(SignalIds.STEERING_WHEEL_HEAT to SignalValue.of(0))
        h.engine.handle(EngineEvent.NaviArrived)
        h.clock.advance(5_000)
        h.signal(SignalIds.STEERING_WHEEL_HEAT, SignalValue.of(2)) // late echo: past the 3 s ledger deadline
        h.signal(SignalIds.STEERING_WHEEL_HEAT, SignalValue.of(2)) // repeated echo
        assertTrue(h.engine.snapshot.trip.userOverrides.isEmpty(), "a late echo is not a user override")
        h.signal(SignalIds.STEERING_WHEEL_HEAT, SignalValue.of(0)) // the user turns it off, then on again
        h.signal(SignalIds.STEERING_WHEEL_HEAT, SignalValue.of(2))
        assertEquals(listOf<SignalValue>(SignalValue.of(0), SignalValue.of(2)), h.observer.userChanges.filter { it.second == SignalIds.STEERING_WHEEL_HEAT }.map { it.third })
    }

    @Test
    fun `changes after the driver left and locked are not habits`() = runTest {
        val h = Harness(emptyList())
        h.startTrip(SignalIds.DOOR_LOCK to no, SignalIds.STEERING_WHEEL_HEAT to SignalValue.of(1))
        h.signal(SignalIds.SEAT_OCCUPIED_DRIVER, no)
        h.signal(SignalIds.DOOR_LOCK, yes)
        h.signal(SignalIds.STEERING_WHEEL_HEAT, SignalValue.of(0))
        assertTrue(h.observer.userChanges.none { it.second == SignalIds.STEERING_WHEEL_HEAT })
    }

    @Test
    fun `a throwing habit hook breaks neither the signal nor ride start`() = runTest {
        val greeting = Routine(id = "hello", name = "출발", trigger = Trigger.RideStart, actions = listOf(Action.Notify("hello")))
        val broken = object : EngineObserver {
            override fun onTripStarted(tripId: String, at: java.time.Instant) = error("habit io")
            override fun onUserChange(tripId: String?, signal: SignalId, value: SignalValue, at: java.time.Instant) = error("habit io")
        }
        val notifier = RecordingNotifier()
        val engine = RoutineEngine(
            testCatalog, Executor(ActionPlanner(testCatalog), RecordingWriter(), notifier, RecordingNavigator(), ScriptedConfirmer()),
            notifier, InMemoryEngineStore(), FakeClock(kst("08:00:00")), SEOUL, broken, newTripId = { "trip-x" },
        )
        engine.start(EngineStartup(FULL_CONTROL, listOf(validate(greeting)), mapOf(SignalIds.SEAT_OCCUPIED_DRIVER to no), PARKED))
        engine.handle(EngineEvent.SignalChanged(SignalIds.SEAT_OCCUPIED_DRIVER, yes))
        assertEquals("hello", notifier.messages.single().textRes)
        assertEquals("trip-x", engine.snapshot.trip.tripId)
    }
}
