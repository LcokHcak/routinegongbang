package kr.routinegongbang.core.fake

import kr.routinegongbang.core.engine.EngineEvent
import kr.routinegongbang.core.engine.RoutineEngine
import kr.routinegongbang.core.engine.TripState
import kr.routinegongbang.core.engine.TripTracker
import kr.routinegongbang.core.model.SignalCatalog
import kr.routinegongbang.core.model.SignalId
import kr.routinegongbang.core.model.SignalIds
import kr.routinegongbang.core.model.SignalValue
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

/** Null when [value] is a valid value for [signal] in this catalog; otherwise the reason. */
fun SignalCatalog.valueProblem(signal: SignalId, value: SignalValue): String? {
    val spec = this[signal] ?: return "unknown signal ${signal.value}"
    return when {
        value.type != spec.valueType -> "${signal.value} expects ${spec.valueType}, got $value"
        !spec.accepts(value) -> "${signal.value} value $value is out of range"
        else -> null
    }
}

/** How a [ScenarioDriver] feeds an engine. */
interface EngineInput {
    suspend fun send(event: EngineEvent)
    val trip: TripState
}

/** Tests: the driver is the engine's only caller, so it calls [RoutineEngine.handle] directly. */
class DirectInput(private val engine: RoutineEngine) : EngineInput {
    override suspend fun send(event: EngineEvent) = engine.handle(event)
    override val trip: TripState get() = engine.snapshot.trip
}

/** Live service: the engine runs its own loop, so events go through its queue and the driver waits for each. */
class QueuedInput(private val engine: RoutineEngine) : EngineInput {
    override suspend fun send(event: EngineEvent) = engine.postAndAwait(event)
    override val trip: TripState get() = engine.snapshot.trip
}

/**
 * Applies the vehicle and time steps of a [Scenario] to an engine through a [FakeVehicle]: changes made outside
 * the app, time that passes (echoes delivered when due, a tick every minute, like the service) and the ride
 * macros. Expectations, confirmation answers and suggestions belong to the caller ([ScenarioRunner] in tests,
 * [ScenarioPlayback] in the debug panel). [onEvent] sees every event first (e.g. driving state for the UI).
 */
class ScenarioDriver(
    private val catalog: SignalCatalog,
    private val input: EngineInput,
    val vehicle: FakeVehicle,
    private val clock: AdjustableClock,
    private val onEvent: (EngineEvent) -> Unit = {},
) {
    private var nextTickAt = clock.monotonicMs() + TICK_MS

    val now: Instant get() = clock.now()

    /** Problems found while applying [step], or null when [step] is not a vehicle or time step. */
    suspend fun run(step: ScenarioStep): List<String>? = when (step) {
        is ScenarioStep.User -> user(step.signal, step.value)
        is ScenarioStep.Advance -> advance(step.ms)
        is ScenarioStep.At -> at(step.time)
        is ScenarioStep.Drive -> send(EngineEvent.DrivingChanged(step.state.state))
        is ScenarioStep.Navigating -> send(EngineEvent.NavigationChanged(step.value))
        ScenarioStep.NaviArrived -> send(EngineEvent.NaviArrived)
        is ScenarioStep.Capability -> send(EngineEvent.CapabilityChanged(step.preset.snapshot(catalog, step.simulated)))
        ScenarioStep.RideStart -> rideStart()
        ScenarioStep.RideEnd -> rideEnd()
        is ScenarioStep.ConfirmAnswer, is ScenarioStep.RunNow, ScenarioStep.AcceptSuggestion, is ScenarioStep.Expect -> null
    }

    suspend fun send(event: EngineEvent): List<String> {
        onEvent(event)
        input.send(event)
        deliverEchoes()
        return emptyList()
    }

    /** Sets the car's starting values as outside changes; first-seen values never trigger anything. */
    suspend fun prepare(initial: Map<String, SignalValue>): List<String> = initial.flatMap { (signal, value) -> user(signal, value) }

    /**
     * The script's own starting point, as [ScenarioRunner] starts its engine: its capability (demo D5 opens with
     * Vehicle Control closed), its driving state and the car's values, whatever an earlier script left.
     */
    suspend fun startFrom(scenario: Scenario): List<String> {
        send(EngineEvent.CapabilityChanged(scenario.capability.snapshot(catalog, scenario.simulated)))
        send(EngineEvent.DrivingChanged(scenario.driving.state))
        return prepare(scenario.initial)
    }

    /** Ends a ride an earlier script left open (parked, driver out, locked, end delay waited); nothing if none. */
    suspend fun endLeftoverTrip(): List<String> = if (input.trip.active) rideEnd() else emptyList()

    suspend fun deliverEchoes() {
        var due = vehicle.dueEchoes()
        while (due.isNotEmpty()) {
            due.forEach {
                onEvent(it)
                input.send(it)
            }
            due = vehicle.dueEchoes()
        }
    }

    private suspend fun user(signal: String, value: SignalValue): List<String> {
        val id = SignalId(signal)
        catalog.valueProblem(id, value)?.let { return listOf(it) }
        return send(vehicle.userSets(id, value))
    }

    /**
     * Delivers each echo at its due time and ticks once a minute, so ledger deadlines behave as in the car.
     * Real time may flow underneath ([OffsetClock]); the clock is never moved backwards.
     */
    private suspend fun advance(ms: Long): List<String> {
        if (ms < 0) return listOf("cannot go back in time ($ms ms)")
        val target = clock.monotonicMs() + ms
        while (true) {
            val next = listOfNotNull(vehicle.nextEchoAt(), nextTickAt).filter { it <= target }.minOrNull() ?: break
            clock.advance((next - clock.monotonicMs()).coerceAtLeast(0))
            deliverEchoes()
            if (clock.monotonicMs() >= nextTickAt) {
                nextTickAt += TICK_MS
                send(EngineEvent.Tick)
            }
        }
        clock.advance((target - clock.monotonicMs()).coerceAtLeast(0))
        deliverEchoes()
        return emptyList()
    }

    private suspend fun at(time: String): List<String> {
        val target = runCatching { OffsetDateTime.parse(time).toInstant() }.getOrElse { return listOf("bad time $time") }
        val ms = Duration.between(clock.now(), target).toMillis()
        return if (ms < 0) listOf("$time is before the scenario clock ${clock.now()}") else advance(ms)
    }

    private suspend fun rideStart(): List<String> {
        if (vehicle.value(SignalIds.SEAT_OCCUPIED_DRIVER) == null) return listOf("rideStart needs an initial seat.occupied.driver value")
        val before = input.trip.tripId
        if (vehicle.value(SignalIds.DOOR_LOCK) == SignalValue.Bool(true)) send(vehicle.userSets(SignalIds.DOOR_LOCK, SignalValue.Bool(false)))
        send(vehicle.userSets(SignalIds.SEAT_OCCUPIED_DRIVER, SignalValue.Bool(true)))
        val after = input.trip.tripId
        return if (after == null || after == before) listOf("no trip started (was a trip still active?)") else emptyList()
    }

    private suspend fun rideEnd(): List<String> {
        if (!input.trip.active) return listOf("no trip to end")
        send(EngineEvent.DrivingChanged(DrivingPreset.PARKED.state))
        send(vehicle.userSets(SignalIds.SEAT_OCCUPIED_DRIVER, SignalValue.Bool(false)))
        send(vehicle.userSets(SignalIds.DOOR_LOCK, SignalValue.Bool(true)))
        // One extra tick period: the end is decided on the first tick after the delay.
        advance(TripTracker.DEFAULT_END_DELAY_MS + TICK_MS)
        return if (input.trip.active) listOf("the trip did not end") else emptyList()
    }

    companion object {
        const val TICK_MS: Long = 60_000
    }
}

sealed interface PlaybackResult {
    val problems: List<String>

    data class Finished(override val problems: List<String>) : PlaybackResult

    /** Stopped before [stepIndex] (an `acceptSuggestion` step) so the person at the screen answers it. */
    data class Paused(val stepIndex: Int, override val problems: List<String>) : PlaybackResult
}

/**
 * Plays a scenario on a live engine for demos (debug panel, fake mode): applies the car's starting values and
 * every vehicle and time step, skips `expect` and `confirmAnswer` (a person answers instead), and pauses at
 * `acceptSuggestion` so the suggestion card is answered on the real screen.
 */
class ScenarioPlayback(private val driver: ScenarioDriver) {

    /** A script moved to after the current time, and how far it got. */
    data class Replay(val scenario: Scenario, val result: PlaybackResult)

    /**
     * Plays [scenario] from its start after whatever ran before: ends a leftover ride first, then moves the
     * script to after the current time in [zone], so its times of day and weekdays hold on this device.
     */
    suspend fun replay(scenario: Scenario, zone: ZoneId, onStep: (Int, ScenarioStep) -> Unit = { _, _ -> }): Replay {
        val leftover = driver.endLeftoverTrip().map { "leftover ride: $it" }
        val moved = scenario.rebasedOnto(driver.now, zone)
        return Replay(moved, play(moved, 0, onStep, leftover))
    }

    suspend fun play(scenario: Scenario, from: Int = 0, onStep: (Int, ScenarioStep) -> Unit = { _, _ -> }): PlaybackResult =
        play(scenario, from, onStep, emptyList())

    private suspend fun play(scenario: Scenario, from: Int, onStep: (Int, ScenarioStep) -> Unit, earlier: List<String>): PlaybackResult {
        val problems = earlier.toMutableList()
        if (from == 0) driver.startFrom(scenario).forEach { problems += "initial: $it" }
        for (index in from until scenario.steps.size) {
            val step = scenario.steps[index]
            if (step is ScenarioStep.AcceptSuggestion) return PlaybackResult.Paused(index, problems)
            onStep(index, step)
            val found = when (step) {
                is ScenarioStep.RunNow -> driver.send(EngineEvent.RunNow(step.routineId))
                else -> driver.run(step).orEmpty()
            }
            found.forEach { problems += "step ${index + 1} (${step.kind}): $it" }
        }
        return PlaybackResult.Finished(problems)
    }
}
