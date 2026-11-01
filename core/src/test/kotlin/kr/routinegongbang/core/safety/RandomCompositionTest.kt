package kr.routinegongbang.core.safety

import kotlinx.coroutines.runBlocking
import kr.routinegongbang.core.engine.ActionPlanner
import kr.routinegongbang.core.engine.ActionResult
import kr.routinegongbang.core.engine.ConfirmRequest
import kr.routinegongbang.core.engine.Confirmer
import kr.routinegongbang.core.engine.Decision
import kr.routinegongbang.core.engine.DrivingState
import kr.routinegongbang.core.engine.EngineEvent
import kr.routinegongbang.core.engine.EngineObserver
import kr.routinegongbang.core.engine.EngineStartup
import kr.routinegongbang.core.engine.Executor
import kr.routinegongbang.core.engine.Gear
import kr.routinegongbang.core.engine.InMemoryEngineStore
import kr.routinegongbang.core.engine.NotifyMessage
import kr.routinegongbang.core.engine.Notifier
import kr.routinegongbang.core.engine.ResultUpdate
import kr.routinegongbang.core.engine.RoutineEngine
import kr.routinegongbang.core.engine.TimerKind
import kr.routinegongbang.core.engine.VehicleWriter
import kr.routinegongbang.core.fake.CapabilityPreset
import kr.routinegongbang.core.fake.FakeNavigator
import kr.routinegongbang.core.fake.FakeVehicle
import kr.routinegongbang.core.fake.ManualClock
import kr.routinegongbang.core.model.Action
import kr.routinegongbang.core.model.AreaKey
import kr.routinegongbang.core.model.CompareOp
import kr.routinegongbang.core.model.Condition
import kr.routinegongbang.core.model.Limits
import kr.routinegongbang.core.model.Occupancy
import kr.routinegongbang.core.model.Policy
import kr.routinegongbang.core.model.Routine
import kr.routinegongbang.core.model.RoutineValidator
import kr.routinegongbang.core.model.SignalCatalog
import kr.routinegongbang.core.model.SignalId
import kr.routinegongbang.core.model.SignalIds
import kr.routinegongbang.core.model.SignalSpec
import kr.routinegongbang.core.model.SignalValue
import kr.routinegongbang.core.model.Trigger
import kr.routinegongbang.core.model.ValidatedRoutine
import kr.routinegongbang.core.model.ValidationIssue
import kr.routinegongbang.core.model.ValidationOutcome
import kr.routinegongbang.core.model.ValueType
import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Random saveable routine sets × random event sequences on the real engine and a fake car with
 * coupled signals, checking the invariants I1–I15 at every vehicle write. A failure names its seed; replaying
 * that seed reproduces it exactly (I10), so it can be pinned down as a scenario test.
 */
class RandomCompositionTest {

    @Test
    fun `I1-I15 H30 hold for random routine sets and random event sequences`() = runBlocking {
        val worlds = (0 until SEEDS).map { seed -> World(seed.toLong()).play() }
        val failures = worlds.flatMapIndexed { seed, world -> world.failures.map { "seed $seed $it" } }
        assertTrue(failures.isEmpty(), "${failures.size} violations, first: ${failures.take(8).joinToString("\n")}")
        // A run that never writes, asks or meets a rule would pass without testing anything.
        assertTrue(worlds.sumOf { it.writes } > 300, "writes: ${worlds.sumOf { it.writes }}")
        assertTrue(worlds.sumOf { it.asks } > 50, "confirmations: ${worlds.sumOf { it.asks }}")
        val hits = worlds.flatMap { it.hits.entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.sum() }
        val unexercised = EXERCISED_RULES.filter { (hits[it] ?: 0) == 0 }
        assertTrue(unexercised.isEmpty(), "rules never met: $unexercised; hits: $hits")
    }

    @Test
    fun `I10 the same seed gives the same decisions and writes`() = runBlocking {
        (0 until 20).forEach { seed -> assertEquals(World(seed.toLong()).play().transcript.toString(), World(seed.toLong()).play().transcript.toString(), "seed $seed") }
    }

    private companion object {
        const val SEEDS = 150
        const val STEPS = 200
        /** Rules the random worlds must meet at least once, or the run proves nothing about them. */
        val EXERCISED_RULES = listOf(
            "CONFLICT", "STATE_CHANGED", "CABIN_UNATTENDED", "NO_ONE_TO_CONFIRM", "VISIBILITY", "UNSAFE_DIRECTION",
            "BODY_WHILE_DRIVING", "USER_OVERRIDE", "RATE_LIMITED", "FLIP_FLOP", "CONFIRM_DECLINED",
            // Holds and waits that fall due, all-met and ride-time triggers.
            "TIMER_HOLD", "TIMER_WAIT", "ALL_OF", "RIDE_ELAPSED",
        )
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
        val PARKED = DrivingState(Gear.PARK, 0.0, false)
        val DRIVING = DrivingState(Gear.DRIVE, 50.0, true)

        /** "Max defrost": turning the defroster on also switches recirculation off (H28). */
        val CATALOG: SignalCatalog = SignalCatalog.default().let { base ->
            SignalCatalog(base.all.map { if (it.id == SignalIds.DEFROSTER_FRONT) it.copy(profile = it.profile!!.copy(coupledSignals = setOf(SignalIds.RECIRCULATION))) else it })
        }
        val COUPLINGS: Map<Pair<SignalId, SignalValue>, Map<SignalId, SignalValue>> =
            mapOf((SignalIds.DEFROSTER_FRONT to SignalValue.Bool(true)) to mapOf(SignalIds.RECIRCULATION to SignalValue.Bool(false)))
    }

    private class AppWrite(val signal: SignalId, val value: SignalValue, val atMs: Long)

    /** One random world: a car, an engine, a routine set and a script of events, all from [seed]. */
    private class World(seed: Long) {
        private val rng = Random(seed)
        private val catalog = CATALOG
        private val arbiter = Arbiter(catalog)
        private val clock = ManualClock(Instant.parse("2026-10-26T21:30:00Z")) // Tuesday 06:30 in Seoul
        private val car = FakeVehicle(catalog, clock, initialValues(), couplings = COUPLINGS)
        /** A few signals a world keeps coming back to, like a real driver, so the flip-flop check and limits are met. */
        private val favourites = catalog.all.filter { it.writable }.shuffled(rng).take(rng.nextInt(3, 6))
        private val routines: List<ValidatedRoutine> = randomRoutines()
        private var order: List<ValidatedRoutine> = routines

        val failures = mutableListOf<String>()
        val transcript = StringBuilder()
        var writes = 0
        var asks = 0
        val hits = mutableMapOf<String, Int>()
        private val arrivedDuringQuestion = mutableListOf<EngineEvent>()

        private var step = 0
        private var driving = PARKED
        private var dryRun = false
        private var echo = false
        private var nextTickAt = clock.monotonicMs() + 60_000
        private var asksThisEvent = 0
        private var timerThisEvent = false
        private val timerKinds = mutableSetOf<TimerKind>()
        private var declined = emptySet<SignalId>()
        private var approved = emptyList<Action>()
        private var writtenThisEvent = emptySet<SignalId>()
        private val appWrites = mutableListOf<AppWrite>()
        private val attempts = mutableListOf<Long>()
        private val lastAppWriteAt = mutableMapOf<SignalId, Long>()
        private val userChanged = mutableSetOf<Pair<String, SignalId>>()
        private val awaiting = mutableSetOf<Pair<Long, Int>>()

        private fun hit(rule: String) {
            hits[rule] = (hits[rule] ?: 0) + 1
        }

        private fun fail(message: String) {
            failures += "step $step: $message"
        }

        private val writer = object : VehicleWriter {
            override suspend fun write(signal: SignalId, area: AreaKey, value: SignalValue): Result<Unit> {
                check(signal, area, value)
                return car.write(signal, area, value)
            }
        }

        private val confirmer: Confirmer = object : Confirmer {
            override suspend fun confirm(request: ConfirmRequest): Boolean {
                asks++
                // One question per plan: the event's own, plus one per kind of timer that fell due in this step.
                if (++asksThisEvent > 1 + timerKinds.size) fail("I14 a second question in one plan: ${request.actions}")
                val answer = rng.nextInt(4) != 0
                if (answer) approved = approved + request.actions else declined = declined + request.actions.filterIsInstance<Action.Write>().map { it.signal }
                // Sometimes someone gets in or out while the driver decides: the engine must drop and re-plan (H31).
                if (rng.nextInt(5) == 0) {
                    val change = car.userSets(SignalIds.SEAT_OCCUPIED_PASSENGER, SignalValue.Bool(car.value(SignalIds.SEAT_OCCUPIED_PASSENGER) != SignalValue.Bool(true)))
                    engine.post(change)
                    arrivedDuringQuestion += change
                }
                return answer
            }
        }

        private val notifier = object : Notifier {
            override suspend fun notify(message: NotifyMessage) = Unit
        }

        private val observer = object : EngineObserver {
            override fun onDecision(decision: Decision) {
                transcript.append(step).append(' ').append(label(decision)).append('\n')
                // A hold or wait that falls due during any event, an echo included, runs its own plan.
                if (decision is Decision.TimerFired) {
                    timerThisEvent = true
                    timerKinds += decision.kind
                    hit("TIMER_${decision.kind}")
                }
                if (echo && !timerThisEvent && (decision is Decision.Ran || decision is Decision.ConditionNotMet || decision is Decision.LimitBlocked)) {
                    fail("I8 the car's own echo was judged against routines: ${label(decision)}")
                }
                if (decision is Decision.EventFailed) fail("I9 ${decision.event} failed: ${decision.detail}")
                if (decision !is Decision.Ran) return
                val report = decision.report
                val routine = routines.first { it.routine.id == report.routineId }.routine
                if (!report.dryRun) (routine.trigger as? Trigger.AllOf)?.let { hit("ALL_OF") } ?: (routine.trigger as? Trigger.RideElapsed)?.let { hit("RIDE_ELAPSED") }
                if (report.results.size != routine.actions.size) fail("I13 ${report.routineId} has ${report.results.size} results for ${routine.actions.size} actions")
                report.results.forEachIndexed { index, result ->
                    if (result is ActionResult.Sent || result == ActionResult.Scheduled) awaiting += report.runId to index
                    val reason = (result as? ActionResult.Skipped)?.reason ?: (result as? ActionResult.Cancelled)?.reason
                    reason?.let { hits[it.name] = (hits[it.name] ?: 0) + 1 }
                }
            }

            override fun onResult(update: ResultUpdate) {
                // A write after a wait is reported here first as sent, and settles later like any other.
                if (update.result is ActionResult.Sent) awaiting += update.runId to update.actionIndex else awaiting -= update.runId to update.actionIndex
            }
        }

        private val engine: RoutineEngine = RoutineEngine(
            catalog, Executor(ActionPlanner(catalog), writer, notifier, FakeNavigator(), confirmer), notifier,
            InMemoryEngineStore(), clock, SEOUL, observer,
        )

        suspend fun play(): World {
            engine.start(EngineStartup(CapabilityPreset.FULL.snapshot(catalog, false), routines, car.readings(), PARKED))
            repeat(STEPS) {
                step = it + 1
                randomEvent()
            }
            advance(400_000) // past every ledger deadline, every wait (at most 4 × 90 s) and a few ticks
            if (awaiting.isNotEmpty()) fail("I13 sent writes never settled: $awaiting")
            return this
        }

        /** Invariants that must hold at the moment the car is written. */
        private fun check(signal: SignalId, area: AreaKey, value: SignalValue) {
            writes++
            val spec = catalog[signal] ?: return fail("I2 unknown signal ${signal.value}")
            val profile = spec.profile ?: return fail("I2 wrote ${signal.value}, which is not writable")
            val trip = engine.snapshot.trip
            val current = car.value(signal, area)
            val now = clock.monotonicMs()
            if (spec.safetyClass.parkedOnly && !driving.isParked) fail("I1 ${signal.value} written while driving")
            if (!spec.accepts(value)) fail("I3 ${signal.value}=$value is outside the signal range")
            if (!profile.direction.allows(value, current)) fail("I3 ${signal.value} $current → $value against its safe direction")
            if (profile.visibility?.allows(value, current) == false) fail("I3 ${signal.value} made the view worse")
            val outside = profile.autoRange?.let { r -> (value as? SignalValue.Num)?.value?.let { it !in r } } ?: false
            val confirmedHere = approved.any { it is Action.Write && it.signal == signal && it.value == value }
            if ((outside || profile.confirmRequired || spec.safetyClass.parkedOnly) && !confirmedHere) fail("I3 ${signal.value}=$value written without a confirmation")
            when (val occupancy = profile.occupancy) {
                Occupancy.Any -> Unit
                is Occupancy.Occupied -> if (car.value(occupancy.seat) != SignalValue.Bool(true)) fail("I4 ${signal.value} for an empty or unknown seat")
                is Occupancy.Empty -> if (car.value(occupancy.seat) != SignalValue.Bool(false)) fail("I4 ${signal.value} next to someone")
            }
            if (profile.lifeSupport?.weakens(value, current) == true && (!trip.active || trip.endPendingSinceEpochMs != null)) fail("I15 H30 ${signal.value} weakened with nobody known to be driving")
            trip.tripId?.let { if ((it to signal) in userChanged) fail("I7 ${signal.value} overwrote the user's own change in trip $it") }
            if (signal in declined) fail("I12 ${signal.value} written after the driver declined it")
            // Signals the car changes along with this one are just as ambiguous for a few seconds (seed 695).
            arbiter.group(signal).forEach { lastAppWriteAt[it] = now }
            if (dryRun) return
            // A timer that fell due in this step ran its own plan before the event's.
            if (signal in writtenThisEvent && !timerThisEvent) fail("I11 ${signal.value} written twice in one plan")
            writtenThisEvent = writtenThisEvent + signal
            attempts += now
            if (attempts.count { now - it < 60_000 } > 10) fail("I5 more than 10 writes a minute")
            val same = appWrites.filter { it.signal == signal }
            if (same.count { now - it.atMs < 60_000 } >= profile.maxWritesPerMinute) fail("I5 ${signal.value} over its minute limit")
            if (same.count { now - it.atMs < 3_600_000 } >= profile.maxWritesPerHour) fail("I5 ${signal.value} over its hour limit")
            val recent = same.filter { now - it.atMs < 120_000 }
            val lastSame = recent.indexOfLast { it.value.sameAs(value, spec.rounding) }
            if (lastSame >= 0 && recent.drop(lastSame + 1).any { !it.value.sameAs(value, spec.rounding) }) fail("I6 ${signal.value} flipped back to $value")
            appWrites += AppWrite(signal, value, now)
        }

        private suspend fun randomEvent() {
            when (rng.nextInt(18)) {
                0, 1, 2 -> if (driving.isParked) user(SignalIds.SEAT_OCCUPIED_DRIVER, SignalValue.Bool(car.value(SignalIds.SEAT_OCCUPIED_DRIVER) != SignalValue.Bool(true)))
                3 -> user(SignalIds.SEAT_OCCUPIED_PASSENGER, SignalValue.Bool(rng.nextBoolean()))
                4 -> user(SignalIds.DOOR_LOCK, SignalValue.Bool(rng.nextBoolean()))
                5 -> {
                    driving = if (driving.isParked && car.value(SignalIds.SEAT_OCCUPIED_DRIVER) == SignalValue.Bool(true)) DRIVING else PARKED
                    send(EngineEvent.DrivingChanged(driving))
                }
                6 -> favourites.random(rng).let { user(it.id, randomValue(it)) }
                7 -> send(EngineEvent.NaviArrived)
                8, 9 -> listOf(SignalIds.WIPER_FRONT_STATE, SignalIds.EV_SOC, SignalIds.EV_CHARGING).random(rng).let { user(it, randomValue(catalog.getOrThrow(it))) }
                10, 11 -> send(EngineEvent.NaviArrived)
                12 -> send(EngineEvent.NavigationChanged(rng.nextBoolean()))
                13 -> advance(rng.nextLong(1_000, 90_000))
                14 -> advance(rng.nextLong(5, 60) * 60_000)
                15 -> {
                    val preset = if (rng.nextInt(4) != 0) CapabilityPreset.FULL else CapabilityPreset.CONTROL_CLOSED
                    send(EngineEvent.CapabilityChanged(preset.snapshot(catalog, false)))
                }
                else -> if (rng.nextBoolean()) {
                    dryRun = true
                    send(EngineEvent.RunNow(routines.random(rng).routine.id))
                    dryRun = false
                } else {
                    order = order.shuffled(rng)
                    send(EngineEvent.RoutinesChanged(order))
                }
            }
        }

        /** A change made in the car by a person. Remembered for I7 when it is clearly the user's. */
        private suspend fun user(signal: SignalId, value: SignalValue) {
            val now = clock.monotonicMs()
            val trip = engine.snapshot.trip.tripId
            val real = car.value(signal) != value
            val clearOfApp = lastAppWriteAt[signal]?.let { now - it > 30_000 } ?: true
            send(car.userSets(signal, value))
            if (real && clearOfApp && trip != null && catalog[signal]?.writable == true) userChanged += trip to signal
        }

        private suspend fun send(event: EngineEvent, fromCar: Boolean = false) {
            asksThisEvent = 0
            timerThisEvent = false
            timerKinds.clear()
            declined = emptySet()
            approved = emptyList()
            writtenThisEvent = emptySet()
            echo = fromCar
            transcript.append(step).append(if (fromCar) " echo " else " > ").append(event).appendLine()
            engine.handle(event)
            echo = false
            // What arrived during a question is handled next, as the queue would (the dropped plan's re-plan is
            // left to PlanTest: in this single-step mode the engine's own queue is never drained).
            val arrived = arrivedDuringQuestion.toList()
            arrivedDuringQuestion.clear()
            arrived.forEach { send(it) }
        }

        /** Time passes like in the service: echoes at their due time, a tick every minute. */
        private suspend fun advance(ms: Long) {
            val target = clock.monotonicMs() + ms
            while (true) {
                // The engine wakes itself when a hold or wait is due, like its own run loop does.
                val wake = engine.wakeInMs()?.let { clock.monotonicMs() + it.coerceAtLeast(1) }
                val next = listOfNotNull(car.nextEchoAt(), nextTickAt, wake).filter { it <= target }.minOrNull() ?: break
                clock.advance((next - clock.monotonicMs()).coerceAtLeast(0))
                car.dueEchoes().forEach { send(it, fromCar = true) }
                if (wake != null && clock.monotonicMs() >= wake && clock.monotonicMs() < nextTickAt) send(EngineEvent.Tick)
                if (clock.monotonicMs() >= nextTickAt) {
                    nextTickAt += 60_000
                    send(EngineEvent.Tick)
                }
            }
            clock.advance((target - clock.monotonicMs()).coerceAtLeast(0))
            car.dueEchoes().forEach { send(it, fromCar = true) }
        }

        private fun initialValues(): Map<SignalId, SignalValue> = buildMap {
            catalog.all.filter { it.readSdk != null && !it.unsupported }.forEach { put(it.id, randomValue(it)) }
            put(SignalIds.SEAT_OCCUPIED_DRIVER, SignalValue.Bool(false))
            put(SignalIds.SEAT_OCCUPIED_PASSENGER, SignalValue.Bool(false))
            put(SignalIds.DOOR_LOCK, SignalValue.Bool(true))
            put(SignalIds.WINDOW_POSITION_DRIVER, SignalValue.of(0))
        }

        private fun randomValue(spec: SignalSpec): SignalValue = when (spec.valueType) {
            ValueType.BOOLEAN -> SignalValue.Bool(rng.nextBoolean())
            ValueType.ENUM -> spec.allowedValues!!.random(rng)
            ValueType.NUMBER -> spec.range!!.let { SignalValue.of(rng.nextInt(it.start.toInt(), it.endInclusive.toInt() + 1)) }
        }

        /** Only routines that pass the save checks: the engine must stay safe for anything a user can save. */
        private fun randomRoutines(): List<ValidatedRoutine> {
            val validator = RoutineValidator(catalog)
            val wanted = rng.nextInt(5, 13)
            val found = mutableListOf<ValidatedRoutine>()
            var attempt = 0
            while (found.size < wanted && attempt++ < 500) {
                val routine = randomRoutine("r$attempt")
                when (val outcome = validator.validated(routine)) {
                    is ValidationOutcome.Valid -> found += outcome.routine
                    // An imported or older routine may never have met today's save checks: the run-time rules must
                    // hold on their own, so some of those are let through as they are.
                    is ValidationOutcome.Invalid ->
                        if (outcome.issues.all { it is ValidationIssue.Unsafe } && rng.nextInt(5) < 2) found += ValidatedRoutine(routine)
                }
            }
            return found
        }

        private fun randomRoutine(id: String): Routine {
            val triggerable = catalog.all.filter { it.subscribable && !it.unsupported && it.readSdk != null }
            val hold = listOf(0, 0, 0, 30, 120).random(rng)
            val trigger = when (rng.nextInt(12)) {
                0 -> Trigger.RideStart
                1, 5 -> Trigger.RideEnd
                2, 6 -> Trigger.NaviArrived
                3 -> Trigger.TimeOfDay(rng.nextInt(24), rng.nextInt(60))
                4 -> catalog.all.filter { it.subscribable && it.valueType == ValueType.NUMBER && it.readSdk != null }.random(rng)
                    .let { Trigger.Threshold(it.id, if (rng.nextBoolean()) CompareOp.GE else CompareOp.LE, randomValue(it), holdSeconds = hold) }
                // All-met, ride time.
                9, 10 -> Trigger.AllOf(List(rng.nextInt(1, 3)) { randomCompare(triggerable) }, holdSeconds = hold)
                11 -> Trigger.RideElapsed(listOf(1, 5, 30).random(rng))
                // An exact number is almost never hit by chance; numbers fire through thresholds instead.
                else -> triggerable.filter { it.valueType != ValueType.NUMBER }.random(rng).let { Trigger.SignalEdge(it.id, to = setOf(randomValue(it)), holdSeconds = hold) }
            }
            val condition = when (rng.nextInt(8)) {
                0 -> triggerable.filter { it.valueType != ValueType.NUMBER }.random(rng).let { Condition.Compare(it.id, CompareOp.EQ, randomValue(it)) }
                1 -> Condition.TimeWindow(rng.nextInt(1440), rng.nextInt(1440))
                2 -> Condition.DayType(weekdays = rng.nextBoolean(), weekends = true)
                else -> null
            }
            val actions = List(rng.nextInt(1, 5)) {
                when (rng.nextInt(7)) {
                    6 -> Action.Wait(listOf(10, 30, 90).random(rng))
                    0 -> Action.Notify("n")
                    1 -> Action.Remind("r")
                    2 -> Action.SetDestination("home")
                    else -> favourites.random(rng).let { Action.Write(it.id, randomValue(it)) }
                }
            }
            val limits = Limits(cooldownMinutes = listOf(0, 0, 10, 30).random(rng), maxPerDay = listOf(null, 3).random(rng), oncePerTrip = rng.nextBoolean())
            return Routine(id = id, name = id, trigger = trigger, condition = condition, actions = actions, limits = limits, policy = Policy(confirmWhileDriving = rng.nextInt(5) == 0, askBeforeRun = rng.nextInt(6) == 0))
        }

        /** One comparison a driver might wait for: a state equal to a value, or a number past a bound. */
        private fun randomCompare(triggerable: List<SignalSpec>): Condition.Compare = triggerable.random(rng).let { spec ->
            val op = if (spec.valueType == ValueType.NUMBER) listOf(CompareOp.GE, CompareOp.LE).random(rng) else CompareOp.EQ
            Condition.Compare(spec.id, op, randomValue(spec))
        }

        private fun label(decision: Decision): String = when (decision) {
            is Decision.Ran -> "ran ${decision.report.routineId}${if (decision.report.dryRun) " dry" else ""} ${decision.report.results}"
            else -> decision.toString()
        }
    }
}
