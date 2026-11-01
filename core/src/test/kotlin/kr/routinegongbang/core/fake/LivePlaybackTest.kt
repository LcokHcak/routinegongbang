package kr.routinegongbang.core.fake

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kr.routinegongbang.core.engine.ActionPlanner
import kr.routinegongbang.core.engine.ActionResult
import kr.routinegongbang.core.engine.Decision
import kr.routinegongbang.core.engine.EngineObserver
import kr.routinegongbang.core.engine.Reason
import kr.routinegongbang.core.engine.EngineEvent
import kr.routinegongbang.core.engine.EngineStartup
import kr.routinegongbang.core.engine.Executor
import kr.routinegongbang.core.engine.InMemoryEngineStore
import kr.routinegongbang.core.engine.RoutineEngine
import kr.routinegongbang.core.habit.HabitBook
import kr.routinegongbang.core.habit.HabitDetector
import kr.routinegongbang.core.habit.HabitLog
import kr.routinegongbang.core.habit.HabitRecorder
import kr.routinegongbang.core.model.RoutineValidator
import kr.routinegongbang.core.model.SignalCatalog
import kr.routinegongbang.core.model.SignalIds
import kr.routinegongbang.core.model.SignalValue
import kr.routinegongbang.core.model.ValidationOutcome
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

class OffsetClockTest {

    private val base = ManualClock(Instant.parse("2026-10-27T00:00:00Z"), monoMs = 500)

    @Test
    fun `fast forward moves wall and monotonic time together and can be reset`() {
        val clock = OffsetClock(base)
        clock.advance(3_600_000)
        assertEquals(Instant.parse("2026-10-27T01:00:00Z"), clock.now())
        assertEquals(3_600_500, clock.monotonicMs())
        assertEquals(3_600_000, clock.offsetMs)
        base.advance(1_000)
        assertEquals(3_601_500, clock.monotonicMs(), "real time keeps flowing underneath")
        clock.reset()
        assertEquals(base.now(), clock.now())
    }

    @Test
    fun `time never goes backwards`() {
        assertFailsWith<IllegalArgumentException> { OffsetClock(base).advance(-1) }
    }
}

class ScenarioRebaseTest {

    private val d4 = ScenarioJson.decode(javaClass.getResource("/scenarios/d4_habit.json")!!.readText()).getOrThrow()

    @Test
    fun `scripts move forward by whole weeks so weekdays and times of day stay the same`() {
        val now = Instant.parse("2026-11-03T10:00:00Z") // a Tuesday, after the script start (Mon 2026-10-26)
        val moved = d4.rebasedOnto(now)
        assertEquals(d4.startInstant.atZone(SEOUL).dayOfWeek, moved.startInstant.atZone(SEOUL).dayOfWeek)
        assertEquals(d4.startInstant.atZone(SEOUL).toLocalTime(), moved.startInstant.atZone(SEOUL).toLocalTime())
        val firstAt = moved.steps.filterIsInstance<ScenarioStep.At>().first()
        assertEquals("2026-11-09T08:00+09:00", firstAt.time)
        assertTrue(!moved.startInstant.isBefore(now))
    }

    @Test
    fun `scripts keep their wall clock time in the device timezone`() {
        val losAngeles = ZoneId.of("America/Los_Angeles")
        val moved = d4.rebasedOnto(Instant.parse("2026-11-03T10:00:00Z"), losAngeles)
        val firstAt = OffsetDateTime.parse(moved.steps.filterIsInstance<ScenarioStep.At>().first().time).atZoneSameInstant(losAngeles)
        assertEquals(java.time.LocalTime.of(8, 0), firstAt.toLocalTime())
        assertEquals(java.time.DayOfWeek.MONDAY, firstAt.dayOfWeek)
        assertEquals("America/Los_Angeles", moved.zone)
    }

    @Test
    fun `a script already in the future is left alone`() {
        assertEquals(d4, d4.rebasedOnto(Instant.parse("2026-01-01T00:00:00Z")))
    }
}

class LivePlaybackTest {

    private val catalog = SignalCatalog.default()

    @Test
    fun `postAndAwait returns only after the running loop processed the event`() = runTest {
        val clock = ManualClock(Instant.parse("2026-10-27T03:00:00Z"))
        val notifier = FakeNotifier()
        val engine = RoutineEngine(catalog, Executor(ActionPlanner(catalog), FakeVehicle(catalog, clock), notifier, FakeNavigator(), FakeConfirmer()),
            notifier, InMemoryEngineStore(), clock, SEOUL)
        val routine = kr.routinegongbang.core.model.RoutineValidator(catalog).validated(
            kr.routinegongbang.core.model.Routine(id = "a", name = "a", trigger = kr.routinegongbang.core.model.Trigger.NaviArrived,
                actions = listOf(kr.routinegongbang.core.model.Action.Notify("arrived"))),
        ) as kr.routinegongbang.core.model.ValidationOutcome.Valid
        engine.start(EngineStartup(CapabilityPreset.FULL.snapshot(catalog, false), listOf(routine.routine), emptyMap(), DrivingPreset.PARKED.state))
        backgroundScope.launch { engine.runLoop() }
        engine.postAndAwait(EngineEvent.NaviArrived)
        assertEquals("arrived", notifier.messages.single().textRes)
    }

    @Test
    fun `D4 played through a running engine leaves a habit suggestion and pauses before accepting it`() = runTest {
        val scenario = ScenarioJson.decode(javaClass.getResource("/scenarios/d4_habit.json")!!.readText()).getOrThrow()
        val clock = ManualClock(scenario.startInstant)
        val vehicle = FakeVehicle(catalog, clock)
        val notifier = FakeNotifier()
        val habits = HabitBook(HabitRecorder(catalog, SEOUL), HabitDetector(catalog, SEOUL), HabitLog())
        val engine = RoutineEngine(catalog, Executor(ActionPlanner(catalog), vehicle, notifier, FakeNavigator(), FakeConfirmer()),
            notifier, InMemoryEngineStore(), clock, SEOUL, observer = habits)
        engine.start(EngineStartup(CapabilityPreset.FULL.snapshot(catalog, false), emptyList(), emptyMap(), DrivingPreset.PARKED.state))
        backgroundScope.launch { engine.runLoop() }

        val driver = ScenarioDriver(catalog, QueuedInput(engine), vehicle, clock)
        val progress = mutableListOf<Int>()
        val result = ScenarioPlayback(driver).play(scenario) { index, _ -> progress += index }
        assertTrue(result.problems.isEmpty(), result.problems.joinToString())
        ScenarioPlayback(driver).play(scenario, from = (result as PlaybackResult.Paused).stepIndex + 1)

        val pausedAt = assertNotNull(result as? PlaybackResult.Paused)
        assertTrue(scenario.steps[pausedAt.stepIndex] is ScenarioStep.AcceptSuggestion)
        assertTrue(result.problems.isEmpty(), result.problems.joinToString())
        assertTrue(progress.isNotEmpty())
        // Replaying straight away must work too: D4 ends mid-ride, so playback first ends that trip.
        habits.clear()
        val again = ScenarioPlayback(driver).play(scenario.rebasedOnto(clock.now()))
        assertTrue(again is PlaybackResult.Paused && again.problems.isEmpty(), again.problems.joinToString())

        val tripId = habits.log.trips.last().tripId
        val suggestion = assertNotNull(habits.bestSuggestion(tripId, emptyList(), clock.now().toEpochMilli()))
        assertEquals(SignalIds.STEERING_WHEEL_HEAT, suggestion.pattern.signal)
        assertEquals(SignalValue.of(1), suggestion.pattern.value)
    }

    @Test
    fun `every demo script replays straight after any other on one live engine`() = runTest {
        val scripts = listOf("d1_rain", "d2_lock_check", "d3_charge_done", "d4_habit", "d5_capability", "d6_commute_home")
            .map { ScenarioJson.decode(javaClass.getResource("/scenarios/$it.json")!!.readText()).getOrThrow() }
        val clock = ManualClock(scripts.first().startInstant)
        val vehicle = FakeVehicle(catalog, clock)
        val notifier = FakeNotifier()
        val decisions = mutableListOf<Decision>()
        val recorder = object : EngineObserver {
            override fun onDecision(decision: Decision) { decisions += decision }
        }
        val engine = RoutineEngine(catalog, Executor(ActionPlanner(catalog), vehicle, notifier, FakeNavigator(), FakeConfirmer()),
            notifier, InMemoryEngineStore(), clock, SEOUL, observer = recorder)
        engine.start(EngineStartup(CapabilityPreset.FULL.snapshot(catalog, true), emptyList(), emptyMap(), DrivingPreset.PARKED.state))
        backgroundScope.launch { engine.runLoop() }
        val playback = ScenarioPlayback(ScenarioDriver(catalog, QueuedInput(engine), vehicle, clock))
        val validator = RoutineValidator(catalog)

        repeat(2) { round ->
            for (script in scripts) {
                engine.postAndAwait(EngineEvent.RoutinesChanged(script.routines.map { (validator.validated(it) as ValidationOutcome.Valid).routine }))
                decisions.clear()
                val replay = playback.replay(script, SEOUL)
                val problems = replay.result.problems.toMutableList()
                var result = replay.result
                while (result is PlaybackResult.Paused) {
                    result = playback.play(replay.scenario, from = result.stepIndex + 1)
                    problems += result.problems
                }
                assertTrue(problems.isEmpty(), "round $round, ${script.name}: ${problems.joinToString()}")
                if (script.name.startsWith("D5")) {
                    // D5 opens with Vehicle Control closed (its own capability), whatever the script before it left.
                    val first = decisions.filterIsInstance<Decision.Ran>().first().report
                    assertEquals(ActionResult.Notified(Reason.SDK_NOT_OPEN), first.results.single(), "round $round")
                }
            }
        }
    }
}
