package kr.routinegongbang.core.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kr.routinegongbang.core.safety.Arbiter
import kr.routinegongbang.core.model.Action
import kr.routinegongbang.core.model.AreaKey
import kr.routinegongbang.core.model.AreaKeys
import kr.routinegongbang.core.model.Condition
import kr.routinegongbang.core.model.Held
import kr.routinegongbang.core.model.Rounding
import kr.routinegongbang.core.model.Routine
import kr.routinegongbang.core.model.RoutineJson
import kr.routinegongbang.core.model.Segment
import kr.routinegongbang.core.model.SignalCatalog
import kr.routinegongbang.core.model.SignalId
import kr.routinegongbang.core.model.SignalIds
import kr.routinegongbang.core.model.SignalValue
import kr.routinegongbang.core.model.Trigger
import kr.routinegongbang.core.model.ValidatedRoutine
import kr.routinegongbang.core.model.segments
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

/**
 * The single-threaded routine engine: edge detection → limits → condition →
 * safety and tiers → executor → result tracking, plus trips, reminders and persistence.
 *
 * All state below is confined to one coroutine: call [start], then either [runLoop] or [handle], never both.
 * Only [post] and [close] may be called from other threads. [post] also updates the live driving and
 * navigation state at once, so the executor's re-check after a confirmation sees changes still queued.
 */
class RoutineEngine(
    private val catalog: SignalCatalog,
    private val executor: Executor,
    private val notifier: Notifier,
    private val store: EngineStore,
    private val clock: EngineClock,
    private val zone: ZoneId,
    private val observer: EngineObserver = EngineObserver.NONE,
    private val newTripId: () -> String = { UUID.randomUUID().toString() },
    private val tripTracker: TripTracker = TripTracker(),
) {
    /** An event with the time it was posted, so queueing delay never skews ledger deadlines. */
    private class Stamped(val event: EngineEvent, val monoMs: Long, val now: Instant)

    private val conditions = ConditionEvaluator(catalog, zone)
    private val limitGuard = LimitGuard(zone)
    private val events = Channel<Stamped>(Channel.UNLIMITED)
    private val postLock = Any()

    @Volatile private var liveDriving = DrivingState(gear = null, speedKph = null, uxRestricted = null)
    @Volatile private var liveNavigating = false
    /** Posted changes that can make a pending answer wrong; moves while a confirmation waits if one arrived. */
    private val postedChanges = AtomicLong(0)
    /** Signals the plan in progress writes (with their couplings): a user change of one of them voids the answer. */
    @Volatile private var watched: Set<SignalId> = emptySet()
    private val arbiter = Arbiter(catalog)

    private var routines: List<ValidatedRoutine> = emptyList()
    private var capability = CapabilitySnapshot(openSdks = emptySet(), access = emptyMap())
    private var detector = EdgeDetector(catalog)
    private var values: Map<SignalId, SignalValue> = emptyMap()
    private var executorState = ExecutorState(WriteLedger(catalog), WriteRateLimiter.default())
    private var tracker = ResultTracker()
    private var lastTick: Instant? = null
    private var persisted: EngineSnapshot? = null
    private var runCounter = 0L
    /** Recent successful writes per signal/area, for the late-echo grace. */
    private var recentWrites: Map<Pair<SignalId, AreaKey>, Pair<SignalValue, Long>> = emptyMap()
    /** False after a failed load: the stored file may be recoverable, so it is never overwritten. */
    private var storeWritable = true
    /**
     * Routines whose trigger fired with a hold time, by id, with the monotonic time the hold ends.
     * Not saved: after a restart the hold starts again with the next edge.
     */
    private var holds: Map<String, Long> = emptyMap()

    /** Current persisted part of the state (trip, counters, reminders). Readable from any thread. */
    @Volatile var snapshot: EngineSnapshot = EngineSnapshot()
        private set

    /** Restore, take the capability snapshot, prime values without triggering, then accept events. */
    suspend fun start(startup: EngineStartup) {
        val now = clock.now()
        routines = startup.routines
        snapshot = loadSnapshot(now)
        persisted = snapshot
        capability = startup.capability
        liveDriving = startup.driving
        liveNavigating = startup.navigating
        startup.readings.forEach { (signal, value) ->
            detector = detector.observe(signal, value, priming = true).second
            remember(signal, value, area = null)
        }
        val restored = tripTracker.onStartup(snapshot.trip, now.toEpochMilli())
        val seated = startup.readings[SignalIds.SEAT_OCCUPIED_DRIVER] == SignalValue.Bool(true)
        // The driver may have left and locked while the service was down: the restored trip then starts ending now.
        // Only when parked: doors auto-lock while driving. Startup readings never fire RideEnd.
        val leftAndLocked = startup.driving.isParked &&
            startup.readings[SignalIds.SEAT_OCCUPIED_DRIVER] == SignalValue.Bool(false) &&
            startup.readings[SignalIds.DOOR_LOCK] == SignalValue.Bool(true)
        val trip = when {
            seated -> tripTracker.onDriverSeat(restored, occupied = true, now.toEpochMilli(), newTripId).first
            leftAndLocked -> tripTracker.onLock(restored, locked = true, now.toEpochMilli(), driverSeated = false).first
            else -> restored
        }
        snapshot = snapshot.copy(trip = trip)
        restoreContinuations(now)
        lastTick = now
        pruneCounters(now)
        flushReminders(now)
        persist()
    }

    /** Thread-safe. Live state and queue order stay consistent across posting threads. False once closed. */
    fun post(event: EngineEvent): Boolean = synchronized(postLock) {
        if (voidsAnswer(event)) postedChanges.incrementAndGet()
        applyLive(event)
        events.trySend(Stamped(event, clock.monotonicMs(), clock.now())).isSuccess
    }

    /**
     * An answer holds for the state it was given in. Who sits where, the locks, parked or not,
     * navigation, the capability, the routine list and the plan's own signals decide it; a speed sample, an
     * unrelated signal or a new trigger does not (counting every event would drop every voice answer).
     */
    private fun voidsAnswer(event: EngineEvent): Boolean = when (event) {
        is EngineEvent.SignalChanged -> event.signal in ANSWER_SIGNALS || event.signal in watched
        is EngineEvent.DrivingChanged -> event.state.isParked != liveDriving.isParked
        is EngineEvent.NavigationChanged -> event.navigating != liveNavigating
        is EngineEvent.CapabilityChanged, is EngineEvent.RoutinesChanged -> true
        else -> false
    }

    /**
     * Posts [event] and suspends until the running loop has handled it (and everything posted before it).
     * Lets a driver outside the engine coroutine (debug playback) act step by step without racing the loop.
     */
    suspend fun postAndAwait(event: EngineEvent) {
        if (!post(event)) return
        val done = CompletableDeferred<Unit>()
        if (post(EngineEvent.Sync(done))) done.await()
    }

    fun close() {
        events.close()
    }

    /**
     * Processes posted events in order until closed or cancelled. One failing event never stops the loop.
     * A hold or wait due before the next event wakes the loop by itself; `select` either
     * receives an event or times out, so no event is lost to the timeout.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun runLoop() {
        while (true) {
            val wait = wakeInMs()
            val stamped = select {
                events.onReceiveCatching { it.getOrNull() ?: CLOSED }
                if (wait != null) onTimeout(wait.coerceAtLeast(1)) { Stamped(EngineEvent.Tick, clock.monotonicMs(), clock.now()) }
            }
            if (stamped === CLOSED) return
            try {
                process(stamped)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit { observer.onDecision(Decision.EventFailed(stamped.event::class.simpleName, e.message)) }
            }
        }
    }

    /**
     * Milliseconds until the next hold, wait or ride time is due, null when nothing is pending. For the engine's own
     * loop, tests and the debug panel; call it on the engine coroutine.
     */
    fun wakeInMs(): Long? {
        val mono = clock.monotonicMs()
        val nowMs = clock.now().toEpochMilli()
        // A mark not yet crossed by a tick is due, even if a long event carried the clock past it.
        val since = lastTick?.toEpochMilli() ?: nowMs
        val rideMarks = rideElapsedMarks().filter { it > since }.map { it - nowMs }
        val due = holds.values.map { it - mono } + snapshot.continuations.map { it.dueEpochMs - nowMs } + rideMarks
        return due.minOrNull()?.coerceAtLeast(0)
    }

    /** Handles one event immediately on the caller's coroutine (tests and single-step debugging). */
    suspend fun handle(event: EngineEvent) {
        applyLive(event)
        process(Stamped(event, clock.monotonicMs(), clock.now()))
    }

    private fun applyLive(event: EngineEvent) {
        when (event) {
            is EngineEvent.DrivingChanged -> liveDriving = event.state
            is EngineEvent.NavigationChanged -> liveNavigating = event.navigating
            else -> Unit
        }
    }

    private suspend fun process(stamped: Stamped) {
        // What fell due before this event happened before it, judged on the state before it.
        runDue(stamped)
        when (val event = stamped.event) {
            is EngineEvent.SignalChanged -> onSignal(event, stamped)
            is EngineEvent.DrivingChanged -> flushReminders(stamped.now)
            is EngineEvent.NavigationChanged -> Unit
            is EngineEvent.CapabilityChanged -> capability = event.snapshot
            is EngineEvent.RoutinesChanged -> {
                clearSuspensions(routines, event.routines)
                dropHolds(routines, event.routines)
                routines = event.routines
                pruneCounters(stamped.now)
            }
            EngineEvent.NaviArrived -> firePlan(stamped) { it is Trigger.NaviArrived }
            EngineEvent.Tick -> onTick(stamped)
            is EngineEvent.RunNow -> routines.firstOrNull { it.routine.id == event.routineId }?.let { dryRun(it, stamped) }
            is EngineEvent.Sync -> event.done.complete(Unit)
            is EngineEvent.Replan -> runPlan(stamped, routines.filter { it.routine.enabled && it.routine.id in event.routineIds }, event.attempt)
        }
        persist()
    }

    private suspend fun onSignal(event: EngineEvent.SignalChanged, stamped: Stamped) {
        val area = event.area ?: homeArea(event.signal)
        val origin = classify(event, area, stamped.monoMs)
        remember(event.signal, event.value, area)
        val (transition, nextDetector) = detector.observe(event.signal, event.value, priming = false, area = area)
        detector = nextDetector
        if (transition == null) return
        // Any change, ours included, can end a hold; only one from outside the app starts one.
        val home = area == homeArea(event.signal)
        if (home) breakHolds(event.signal)
        // Trip state follows every change; only real changes made outside the app fire routines or become habits.
        val tripEvent = onTripSignal(transition, stamped)
        if (origin == ChangeOrigin.COUPLED) noteUserChange(event.signal)
        if (origin != ChangeOrigin.USER || transition.previous == null) return
        noteUserChange(event.signal)
        if (area == homeArea(event.signal) && snapshot.trip.endPendingSinceEpochMs == null) {
            emit { observer.onUserChange(snapshot.trip.tripId, event.signal, event.value, stamped.now) }
        }
        // One plan for everything this change triggers: the trip edge and the signal edge.
        firePlan(stamped, holdable = home) {
            (tripEvent is TripEvent.Started && it is Trigger.RideStart) ||
                (tripEvent is TripEvent.EndPending && it is Trigger.RideEnd) ||
                (it is Trigger.SignalEdge && it.firesOn(transition, roundingOf(it.signal))) ||
                (it is Trigger.Threshold && it.firesOn(transition, roundingOf(it.signal))) ||
                (it is Trigger.AllOf && home && allOfFires(it, transition, stamped.now))
        }
    }

    /**
     * Every comparison holds now and did not all hold before this change (a value unknown
     * before or after never fires). Judged on the signals' catalog areas.
     */
    private fun allOfFires(trigger: Trigger.AllOf, transition: Transition, now: Instant): Boolean {
        if (trigger.conditions.none { it.signal == transition.signal }) return false
        val previous = transition.previous ?: return false
        val all = Condition.And(trigger.conditions)
        val before = conditions.evaluate(all, values + (transition.signal to previous), now).met
        return !before && conditions.evaluate(all, values, now).met
    }

    /** Whether a trigger's state still holds on the current values (for holds). */
    private fun stateHolds(trigger: Trigger, now: Instant): Boolean = when (trigger) {
        is Trigger.SignalEdge -> values[trigger.signal]?.let { v -> trigger.to.any { v.sameAs(it, roundingOf(trigger.signal)) } } == true
        is Trigger.Threshold -> values[trigger.signal]?.satisfies(trigger.op, trigger.value, roundingOf(trigger.signal)) == true
        is Trigger.AllOf -> conditions.evaluate(Condition.And(trigger.conditions), values, now).met
        else -> false
    }

    private fun Trigger.listensTo(signal: SignalId): Boolean = when (this) {
        is Trigger.SignalEdge -> this.signal == signal
        is Trigger.Threshold -> this.signal == signal
        is Trigger.AllOf -> conditions.any { it.signal == signal }
        else -> false
    }

    /** A hold ends as soon as its state goes away, whoever changed it. */
    private fun breakHolds(signal: SignalId) {
        if (holds.isEmpty()) return
        val now = clock.now()
        val broken = holds.keys.filter { id ->
            val trigger = routines.firstOrNull { it.routine.id == id }?.routine?.trigger
            trigger == null || (trigger.listensTo(signal) && !stateHolds(trigger, now))
        }
        holds = holds - broken.toSet()
    }

    /** An edited, disabled or removed routine forgets its hold. */
    private fun dropHolds(previous: List<ValidatedRoutine>, next: List<ValidatedRoutine>) {
        val before = previous.associateBy { it.routine.id }
        val kept = next.filter { it.routine.enabled && before[it.routine.id] == it }.map { it.routine.id }.toSet()
        holds = holds.filterKeys { it in kept }
    }

    /**
     * Ledger classification plus the late-echo grace: the same value arriving after the
     * ledger deadline but within [LATE_ECHO_GRACE_MS] of our own write is still the app's echo, so it
     * neither triggers routines, nor counts as a user override, nor becomes a habit.
     * Overrides and habits are noted by the caller, only for values that really changed.
     */
    private fun classify(event: EngineEvent.SignalChanged, area: AreaKey, monoMs: Long): ChangeOrigin {
        val classified = executorState.ledger.classifyDetailed(event.signal, event.value, monoMs, area)
        val ledgerOrigin = classified.origin
        executorState = executorState.copy(ledger = classified.ledger)
        val key = event.signal to area
        val recent = recentWrites[key]
        val lateEcho = ledgerOrigin == ChangeOrigin.USER && recent != null &&
            monoMs - recent.second in 0..LATE_ECHO_GRACE_MS && event.value.sameAs(recent.first, roundingOf(event.signal))
        // Writes found past their deadline settle exactly as at a tick, unless this is their late echo.
        if (!lateEcho) {
            classified.expired.filter { it.lastObserved != null }.forEach { noteUserChange(it.signal) }
            settle(tracker.onExpired(classified.expired))
        }
        if (ledgerOrigin != ChangeOrigin.UNCERTAIN) recentWrites = recentWrites - key
        val origin = if (lateEcho) ChangeOrigin.APP else ledgerOrigin
        when (origin) {
            ChangeOrigin.APP -> settle(if (lateEcho) tracker.onAppEcho(event.signal, area) else tracker.onAppEcho(event.signal, area, classified.confirmed))
            ChangeOrigin.USER -> settle(tracker.onUserChange(event.signal, area))
            ChangeOrigin.UNCERTAIN, ChangeOrigin.COUPLED -> Unit
        }
        return origin
    }

    private fun onTripSignal(transition: Transition, stamped: Stamped): TripEvent? {
        val on = transition.current == SignalValue.Bool(true)
        val nowMs = stamped.now.toEpochMilli()
        val driverSeated = values[SignalIds.SEAT_OCCUPIED_DRIVER] == SignalValue.Bool(true)
        val (trip, event) = when (transition.signal) {
            SignalIds.SEAT_OCCUPIED_DRIVER -> tripTracker.onDriverSeat(snapshot.trip, on, nowMs, newTripId)
            SignalIds.DOOR_LOCK -> tripTracker.onLock(snapshot.trip, on, nowMs, driverSeated)
            else -> return null
        }
        snapshot = snapshot.copy(trip = trip)
        if (event is TripEvent.Started) {
            val known = values
            emit { observer.onTripStarted(event.tripId, stamped.now, known) }
        }
        return event
    }

    private suspend fun onTick(stamped: Stamped) {
        val (expired, ledger) = executorState.ledger.expire(stamped.monoMs)
        executorState = executorState.copy(ledger = ledger)
        // An entry that saw a different value before expiring was most likely changed by the user.
        expired.filter { it.lastObserved != null }.forEach { noteUserChange(it.signal) }
        settle(tracker.onExpired(expired))
        recentWrites = recentWrites.filterValues { (_, at) -> stamped.monoMs - at <= LATE_ECHO_GRACE_MS }
        snapshot = snapshot.copy(trip = tripTracker.onTick(snapshot.trip, stamped.now.toEpochMilli()).first)
        val since = lastTick
        lastTick = nextTickReference(since, stamped.now)
        if (since != null) {
            firePlan(stamped) {
                (it is Trigger.TimeOfDay && it.crossed(since, stamped.now, zone)) ||
                    (it is Trigger.RideElapsed && rideMark(it)?.let { mark -> mark > since.toEpochMilli() && mark <= stamped.now.toEpochMilli() } == true)
            }
        }
        flushReminders(stamped.now)
    }

    /**
     * When [trigger] is due in the current trip, as epoch ms. None while the trip is ending: nobody is in the car
     * any more.
     */
    private fun rideMark(trigger: Trigger.RideElapsed): Long? {
        val trip = snapshot.trip
        if (!trip.active || trip.endPendingSinceEpochMs != null) return null
        return trip.startedAtEpochMs?.let { it + trigger.minutes * 60_000L }
    }

    private fun rideElapsedMarks(): List<Long> =
        routines.mapNotNull { (it.routine.trigger as? Trigger.RideElapsed)?.takeIf { _ -> it.routine.enabled } }.mapNotNull { rideMark(it) }

    /** A small backwards correction keeps the old reference so the same time cannot fire twice. */
    private fun nextTickReference(since: Instant?, now: Instant): Instant {
        if (since == null || !now.isBefore(since)) return now
        return if (Duration.between(now, since) < MAX_BACKWARD_CORRECTION) since else now
    }

    /**
     * Everything one event triggers runs as one plan. Limits and conditions are judged on the
     * state at the event; writes are arbitrated in list order; the plan asks one question for all of its
     * confirmations, and drops everything if something else happened while the driver was deciding.
     * Counters are recorded and saved before the question, so a crash meanwhile cannot re-fire a routine.
     */
    private suspend fun firePlan(stamped: Stamped, holdable: Boolean = true, matches: (Trigger) -> Boolean) {
        val matched = routines.filter { it.routine.enabled && matches(it.routine.trigger) }
        // A trigger with a hold time starts its hold here and runs when it ends (see runDue).
        val (held, now) = matched.partition { ((it.routine.trigger as? Held)?.holdSeconds ?: 0) > 0 }
        if (holdable) {
            held.filter { it.routine.id !in holds }.forEach { validated ->
                val seconds = (validated.routine.trigger as Held).holdSeconds
                holds = holds + (validated.routine.id to stamped.monoMs + seconds * 1_000L)
            }
        }
        runPlan(stamped, now, attempt = 0)
    }

    /**
     * Timers due at this step, in deadline order, before the event that is being processed. Waits
     * resume first (they finish runs already decided); holds whose state lasted run together as one plan.
     */
    private suspend fun runDue(stamped: Stamped) {
        // A failing timer must not take the event that is being processed down with it.
        try {
            runTimers(stamped)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit { observer.onDecision(Decision.EventFailed("Timer", e.message)) }
        }
    }

    private suspend fun runTimers(stamped: Stamped) {
        val nowMs = stamped.now.toEpochMilli()
        val waits = snapshot.continuations.filter { it.dueEpochMs <= nowMs }.sortedBy { it.dueEpochMs }
        if (waits.isNotEmpty()) resume(waits, stamped)
        val due = holds.filterValues { it <= stamped.monoMs }.keys
        if (due.isEmpty()) return
        holds = holds - due
        val lasted = routines.filter { it.routine.enabled && it.routine.id in due && stateHolds(it.routine.trigger, stamped.now) }
        lasted.forEach { emit { observer.onDecision(Decision.TimerFired(it.routine.id, TimerKind.HOLD)) } }
        runPlan(stamped, lasted, attempt = 0)
    }

    /**
     * A plan dropped because the state changed during its question is planned once more, after the change has
     * been handled ([EngineEvent.Replan]): limits and conditions are judged again on the new state.
     */
    private suspend fun runPlan(stamped: Stamped, candidates: List<ValidatedRoutine>, attempt: Int) {
        val runnable = candidates.filter { admitted(it, stamped) }
        if (runnable.isEmpty()) return
        // Confirmations, arbitration and watching cover the first segment only, up to a wait.
        val firsts = runnable.map { it.routine.segments().first() }
        val suppressed = arbiter.suppressed(runnable.map { it.routine }, firsts.map { it.actions })
        val before = runnable.map { snapshot.counters[it.routine.id] }
        runnable.forEach { recordFired(it.routine.id, stamped) }
        persist()
        watched = runnable.indices.flatMap { written(runnable[it], firsts[it]) }.toSet()
        try {
            val budget = ConfirmBudget()
            val (needed, answer) = askOnce(runnable, firsts, suppressed, budget)
            val dropped = answer == Reason.STATE_CHANGED
            runnable.forEachIndexed { i, validated ->
                val refused = if (dropped) answer else answer?.takeIf { needed[i].isNotEmpty() }
                execute(validated, stamped, firsts[i], before = before[i], suppressed = suppressed[i], approved = needed[i], refused = refused, budget = budget)
            }
            if (dropped && attempt < MAX_REPLANS) post(EngineEvent.Replan(runnable.map { it.routine.id }, attempt + 1))
        } finally {
            watched = emptySet()
        }
    }

    /**
     * One question for everything [routines] need confirmed in their [segments]. Returns what each
     * needs and the answer: null when approved or nothing was asked, the refusal otherwise, and STATE_CHANGED when
     * something happened while the driver was deciding.
     */
    private suspend fun askOnce(
        routines: List<ValidatedRoutine>,
        segments: List<Segment>,
        suppressed: List<Set<Int>>,
        budget: ConfirmBudget = ConfirmBudget(),
    ): Pair<List<Map<Int, ExecutionTier.Confirm>>, Reason?> {
        val ctx = context()
        val needed = routines.mapIndexed { i, validated ->
            executor.confirmations(validated, ctx, suppressed[i], executorState, clock.monotonicMs(), range = segments[i].actions)
        }
        val asking = routines.indices.filter { needed[it].isNotEmpty() }
        if (asking.isEmpty()) return needed to null
        val mark = postedChanges.get()
        val refusal = executor.ask(
            routineId = routines[asking.first()].routine.id,
            routineName = asking.joinToString(" · ") { routines[it].routine.name },
            actions = asking.flatMap { i -> needed[i].keys.sorted().map { routines[i].routine.actions[it] } },
            tiers = asking.flatMap { needed[it].values },
            budget = budget,
            nowMs = clock::monotonicMs,
        )
        return needed to if (postedChanges.get() != mark) Reason.STATE_CHANGED else refusal
    }

    private suspend fun dryRun(validated: ValidatedRoutine, stamped: Stamped) {
        // One run at a time per routine, dry or not.
        if (waiting(validated.routine.id)) {
            emit { observer.onDecision(Decision.LimitBlocked(validated.routine.id, LimitReason.STILL_WAITING)) }
            return
        }
        // A dry run does not wait: every action runs now, each still judged by every safety rule.
        val all = Segment(validated.routine.actions.indices, waitAfter = null)
        watched = written(validated, all)
        try {
            execute(validated, stamped, all, dryRun = true)
        } finally {
            watched = emptySet()
        }
    }

    private fun written(validated: ValidatedRoutine, segment: Segment): Set<SignalId> =
        segment.actions.map { validated.routine.actions[it] }.filterIsInstance<Action.Write>().flatMap { arbiter.group(it.signal) }.toSet()

    private fun waiting(routineId: String): Boolean = snapshot.continuations.any { it.routineId == routineId }

    /** Limits, then the condition, both on the state at the event; a refusal is logged with its reason. */
    private fun admitted(validated: ValidatedRoutine, stamped: Stamped): Boolean {
        val routine = validated.routine
        if (waiting(routine.id)) return false.also { emit { observer.onDecision(Decision.LimitBlocked(routine.id, LimitReason.STILL_WAITING)) } }
        val counters = snapshot.counters[routine.id] ?: RoutineCounters()
        if (counters.failStreak >= SUSPEND_AFTER_FAILURES && counters.failedDefinition == definitionOf(routine)) {
            return false.also { emit { observer.onDecision(Decision.LimitBlocked(routine.id, LimitReason.SUSPENDED)) } }
        }
        val limit = limitGuard.check(routine.limits, counters, stamped.now, snapshot.trip.tripId)
        if (limit is LimitDecision.Blocked) return false.also { emit { observer.onDecision(Decision.LimitBlocked(routine.id, limit.reason)) } }
        val condition = conditions.evaluate(routine.condition, values, stamped.now)
        if (!condition.met) return false.also { emit { observer.onDecision(Decision.ConditionNotMet(routine.id, condition.missing)) } }
        return true
    }

    private fun recordFired(id: String, stamped: Stamped) {
        val recorded = limitGuard.record(snapshot.counters[id] ?: RoutineCounters(), stamped.now, snapshot.trip.tripId)
        snapshot = snapshot.copy(counters = snapshot.counters + (id to recorded))
    }

    /**
     * Runs the first segment of one routine of a plan, or of a dry run (which never counts and asks its own
     * question). Counters recorded by [firePlan] are rolled back to [before] when nothing was done; a run that
     * reached a wait counts. Actions after a wait are reported as scheduled and resume later.
     */
    private suspend fun execute(
        validated: ValidatedRoutine,
        stamped: Stamped,
        segment: Segment,
        dryRun: Boolean = false,
        before: RoutineCounters? = null,
        suppressed: Set<Int> = emptySet(),
        approved: Map<Int, ExecutionTier.Confirm>? = null,
        refused: Reason? = null,
        budget: ConfirmBudget = ConfirmBudget(),
    ) {
        val routine = validated.routine
        val report = executor.run(
            validated, ::context, executorState, clock::monotonicMs, dryRun, onState = { executorState = it },
            suppressed = suppressed, approved = approved, refused = refused, budget = budget, changes = postedChanges::get,
            range = segment.actions,
        ).copy(runId = ++runCounter)
        settleSegment(validated, report, stamped)
        // A refused or dropped first segment ends the run: nothing after a wait may run without its own question.
        val waitAfter = segment.waitAfter
        val goesOn = waitAfter != null && refused == null && report.results.none { it is ActionResult.Cancelled }
        val later = if (waitAfter == null) emptyList() else List(routine.actions.size - waitAfter) {
            if (goesOn) ActionResult.Scheduled else ActionResult.Cancelled(refused ?: Reason.STATE_CHANGED)
        }
        val published = report.copy(results = report.results + later)
        if (!dryRun && published.results.none { it.counts() }) {
            snapshot = snapshot.copy(counters = if (before == null) snapshot.counters - routine.id else snapshot.counters + (routine.id to before))
        }
        val (attempts, failures) = writeOutcome(validated, report)
        if (goesOn) {
            schedule(validated, report.runId, next = 1, stamped, attempts, failures)
            persist()
        } else if (!dryRun) {
            trackFailures(validated, attempts, failures)
        }
        emit { observer.onDecision(Decision.Ran(published)) }
    }

    /** Ledger, echo tracking, late-echo grace and reminders for the results of one segment. */
    private fun settleSegment(validated: ValidatedRoutine, report: ExecutionReport, stamped: Stamped) {
        executorState = report.state
        tracker = tracker.trackReport(report, catalog)
        rememberWrites(validated, report)
        queueReminders(validated, report, stamped.now)
    }

    private fun schedule(validated: ValidatedRoutine, runId: Long, next: Int, stamped: Stamped, attempts: Int, failures: Int) {
        val routine = validated.routine
        val waitIndex = routine.segments()[next - 1].waitAfter ?: return
        val wait = routine.actions[waitIndex] as Action.Wait
        val continuation = PendingContinuation(
            routineId = routine.id, definition = definitionOf(routine), runId = runId, segment = next,
            waitIndex = waitIndex, actionCount = routine.actions.size,
            dueEpochMs = stamped.now.toEpochMilli() + wait.seconds * 1_000L, tripId = snapshot.trip.tripId,
            writeAttempts = attempts, writeFailures = failures,
        )
        snapshot = snapshot.copy(continuations = snapshot.continuations.filterNot { it.routineId == routine.id } + continuation)
    }

    /** The failure streak for a run cancelled during its wait: the failures of its earlier segments still count. */
    private fun closeFailures(c: PendingContinuation) {
        routines.firstOrNull { it.routine.id == c.routineId && definitionOf(it.routine) == c.definition }
            ?.let { trackFailures(it, c.writeAttempts, c.writeFailures) }
    }

    /**
     * Runs the segments whose wait is over. Only a changed routine, another trip or a trip that
     * is ending cancels the rest; the condition and the trigger are not judged again (cancelling a step down
     * would leave the stronger setting), but every action passes every safety rule again and asks its own question.
     * Continuations due together are arbitrated against each other; results go to the run they belong to.
     */
    private suspend fun resume(due: List<PendingContinuation>, stamped: Stamped) {
        snapshot = snapshot.copy(continuations = snapshot.continuations - due.toSet())
        // Saved as taken before anything is written: a crash meanwhile must not repeat it (rather skip than repeat).
        persist()
        val resumed = due.mapNotNull { c -> resumable(c)?.let { it to c } }
        due.filter { c -> resumed.none { it.second == c } }.forEach { c ->
            cancelRest(c)
            closeFailures(c)
        }
        val segments = resumed.map { (validated, c) -> validated.routine.segments()[c.segment] }
        val suppressed = arbiter.suppressed(resumed.map { it.first.routine }, segments.map { it.actions })
        resumed.forEach { (_, c) ->
            emit { observer.onDecision(Decision.TimerFired(c.routineId, TimerKind.WAIT)) }
            emitResult(c, c.waitIndex, ActionResult.Waited)
        }
        watched = resumed.indices.flatMap { written(resumed[it].first, segments[it]) }.toSet()
        // One question for every wait that ended together, never the answer given before the wait.
        val (needed, answer) = try {
            askOnce(resumed.map { it.first }, segments, suppressed)
        } finally {
            watched = emptySet()
        }
        resumed.forEachIndexed { i, (validated, c) ->
            val segment = segments[i]
            watched = written(validated, segment)
            val report = try {
                executor.run(
                    validated, ::context, executorState, clock::monotonicMs, onState = { executorState = it },
                    suppressed = suppressed[i], approved = needed[i],
                    refused = if (answer == Reason.STATE_CHANGED) answer else answer?.takeIf { needed[i].isNotEmpty() },
                    changes = postedChanges::get, range = segment.actions,
                ).copy(runId = c.runId)
            } finally {
                watched = emptySet()
            }
            settleSegment(validated, report, stamped)
            report.results.forEachIndexed { k, result -> emitResult(c, report.firstIndex + k, result) }
            val (attempts, failures) = writeOutcome(validated, report)
            val total = c.copy(writeAttempts = c.writeAttempts + attempts, writeFailures = c.writeFailures + failures)
            val cancelled = report.results.any { it is ActionResult.Cancelled }
            if (segment.waitAfter != null && !cancelled) {
                schedule(validated, c.runId, c.segment + 1, stamped, total.writeAttempts, total.writeFailures)
                persist()
            } else {
                segment.waitAfter?.let { cancelRest(total.copy(segment = c.segment + 1, waitIndex = it)) }
                trackFailures(validated, total.writeAttempts, total.writeFailures)
            }
        }
    }

    /** The routine a continuation belongs to, if it may still go on. */
    private fun resumable(c: PendingContinuation): ValidatedRoutine? {
        val validated = routines.firstOrNull { it.routine.id == c.routineId } ?: return null
        val trip = snapshot.trip
        val same = validated.routine.enabled && definitionOf(validated.routine) == c.definition
        return validated.takeIf { same && trip.tripId == c.tripId && trip.endPendingSinceEpochMs == null }
    }

    /** Reports the wait in front of [c]'s segment and everything after it as cancelled. */
    private fun cancelRest(c: PendingContinuation, reason: Reason = Reason.STATE_CHANGED) {
        (c.waitIndex until c.actionCount).forEach { emitResult(c, it, ActionResult.Cancelled(reason)) }
    }

    private fun emitResult(c: PendingContinuation, index: Int, result: ActionResult) =
        emit { observer.onResult(ResultUpdate(c.routineId, index, result, c.runId)) }

    /**
     * After a restart, a wait of the same trip and the same routine goes on if it was due at most
     * [RESUME_GRACE_MS] ago; anything else is dropped. Run ids continue after the saved ones.
     */
    private fun restoreContinuations(now: Instant) {
        val (kept, dropped) = snapshot.continuations.partition { resumable(it) != null && now.toEpochMilli() - it.dueEpochMs <= RESUME_GRACE_MS }
        dropped.forEach { closeFailures(it) }
        snapshot = snapshot.copy(continuations = kept)
        runCounter = maxOf(runCounter, kept.maxOfOrNull { it.runId } ?: 0)
    }

    /** Vehicle write attempts and failures of one segment, for the failure streak. */
    private fun writeOutcome(validated: ValidatedRoutine, report: ExecutionReport): Pair<Int, Int> {
        // Only vehicle writes: a failing notification or navigation says nothing about the car.
        val attempts = report.results.zip(validated.routine.actions.drop(report.firstIndex))
            .filter { (result, action) -> action is Action.Write && (result is ActionResult.Sent || result is ActionResult.Failed) }
        return attempts.size to attempts.count { it.first is ActionResult.Failed }
    }

    /** Only real write failures count toward suspension; downgrades, skips and permission errors are the design working. */
    private fun trackFailures(validated: ValidatedRoutine, attempts: Int, failures: Int) {
        val id = validated.routine.id
        val counters = snapshot.counters[id] ?: return
        val streak = when {
            attempts == 0 -> return
            failures == attempts -> counters.failStreak + 1
            else -> 0
        }
        val updated = counters.copy(failStreak = streak, failedDefinition = definitionOf(validated.routine))
        snapshot = snapshot.copy(counters = snapshot.counters + (id to updated))
    }

    /** Stable across restarts (enum hash codes are not), so an edit made while the service was down is seen. */
    private fun definitionOf(routine: Routine): Int = RoutineJson.encode(routine).hashCode()

    /** An edited routine (a changed definition, also re-enabling it) gets a fresh start after a suspension. */
    private fun clearSuspensions(previous: List<ValidatedRoutine>, next: List<ValidatedRoutine>) {
        val before = previous.associateBy { it.routine.id }
        val edited = next.filter { before[it.routine.id] != it }.map { it.routine.id }.toSet()
        snapshot = snapshot.copy(counters = snapshot.counters.mapValues { (id, c) -> if (id in edited && c.failStreak > 0) c.copy(failStreak = 0) else c })
    }

    private fun rememberWrites(validated: ValidatedRoutine, report: ExecutionReport) {
        val nowMs = clock.monotonicMs()
        report.results.zip(validated.routine.actions.drop(report.firstIndex)).forEach { (result, action) ->
            if (result is ActionResult.Sent && action is Action.Write) {
                recentWrites = recentWrites + ((action.signal to (action.area ?: homeArea(action.signal))) to (action.value to nowMs))
            }
        }
    }

    /** A write lost to an earlier routine still counts: otherwise a `oncePerTrip` loser could win later and flip it. */
    private fun ActionResult.counts(): Boolean = when (this) {
        // A run that reached a wait has happened, even if its first part changed nothing.
        is ActionResult.Sent, is ActionResult.Notified, ActionResult.Requested, is ActionResult.Failed, ActionResult.Deferred, ActionResult.Scheduled -> true
        is ActionResult.Skipped -> reason == Reason.CONFLICT
        ActionResult.NoChange, is ActionResult.Cancelled, is ActionResult.Confirmed, is ActionResult.SentUnconfirmed, ActionResult.Waited -> false
    }

    /** Remind actions held while driving, and body actions skipped while driving, wait for parking. */
    private fun queueReminders(validated: ValidatedRoutine, report: ExecutionReport, now: Instant) {
        val id = validated.routine.id
        val nowMs = now.toEpochMilli()
        val queued = report.results.zip(validated.routine.actions.drop(report.firstIndex)).mapNotNull { (result, action) ->
            when {
                result is ActionResult.Deferred && action is Action.Remind -> PendingReminder(id, action.textRes, nowMs)
                result == ActionResult.Skipped(Reason.BODY_WHILE_DRIVING) ->
                    PendingReminder(id, NotifyMessage.DOWNGRADED, nowMs, Reason.BODY_WHILE_DRIVING, action)
                else -> null
            }
        }.filterNot { new -> snapshot.reminders.any { it.sameAs(new) } }
        if (queued.isEmpty()) return
        // One reminder per signal: the newest says what to do now (two opposite ones would only confuse).
        val replaced = snapshot.reminders.filterNot { old -> queued.any { new -> sameTarget(old, new) } }
        snapshot = snapshot.withReminders(replaced + queued, nowMs)
    }

    private fun PendingReminder.sameAs(other: PendingReminder) =
        routineId == other.routineId && textRes == other.textRes && reason == other.reason && action == other.action

    private fun sameTarget(a: PendingReminder, b: PendingReminder): Boolean {
        val first = a.action as? Action.Write ?: return false
        val second = b.action as? Action.Write ?: return false
        return first.signal == second.signal && (first.area ?: homeArea(first.signal)) == (second.area ?: homeArea(second.signal))
    }

    /** A reminder to do something the car already shows is noise. */
    private fun achieved(reminder: PendingReminder): Boolean {
        val write = reminder.action as? Action.Write ?: return false
        return values[write.signal]?.sameAs(write.value, roundingOf(write.signal)) == true
    }

    /**
     * Shows waiting reminders while parked, re-checking before each one. Each shown reminder is removed and
     * saved at once, so neither a cancellation nor a restart repeats it. Failed ones wait until they expire.
     */
    private suspend fun flushReminders(now: Instant) {
        snapshot = snapshot.withReminders(snapshot.reminders, now.toEpochMilli())
        for (reminder in snapshot.reminders) {
            if (!liveDriving.isParked) return
            if (achieved(reminder)) {
                snapshot = snapshot.copy(reminders = snapshot.reminders - reminder)
                persist()
                continue
            }
            if (!notifySafely(NotifyMessage(reminder.textRes, reminder.reason, reminder.action))) continue
            snapshot = snapshot.copy(reminders = snapshot.reminders - reminder)
            persist()
            emit { observer.onDecision(Decision.ReminderShown(reminder.routineId, reminder.textRes)) }
        }
    }

    private suspend fun notifySafely(message: NotifyMessage): Boolean = try {
        notifier.notify(message)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /**
     * What the car is heading to: a write still waiting for its echo counts as done, and a signal the car is
     * changing by itself (a coupling) as unknown. Directions and "no change" are judged against this, never against
     * a value our own write already replaced (see RandomCompositionTest: a stale value would reopen a window).
     */
    private fun headingTo(): Map<SignalId, SignalValue> {
        val now = clock.monotonicMs()
        val pending = executorState.ledger.pending.filter { it.area == homeArea(it.signal) && now <= it.deadlineMs }
        val changing = pending.filter { it.coupled }.map { it.signal }.toSet()
        // The newest live write wins; a signal the car may still change by itself (even one we also wrote) has no
        // value we can rely on. Writes past their deadline no longer say anything.
        val heading = pending.filter { !it.coupled && it.signal !in changing }.associate { it.signal to it.expected }
        return values - changing + heading
    }

    private fun context() = ExecutionContext(
        driving = liveDriving,
        capability = capability,
        values = headingTo(),
        userOverrides = snapshot.trip.userOverrides,
        navigating = liveNavigating,
        tripActive = snapshot.trip.active,
        tripEnding = snapshot.trip.endPendingSinceEpochMs != null,
    )

    /** User overrides: remembered only during a trip and only for signals a routine could write. */
    private fun noteUserChange(signal: SignalId) {
        if (!snapshot.trip.active || catalog[signal]?.writable != true) return
        snapshot = snapshot.copy(trip = snapshot.trip.copy(userOverrides = snapshot.trip.userOverrides + signal))
    }

    /** Conditions and the executor read one value per signal: the one on its catalog area. */
    private fun remember(signal: SignalId, value: SignalValue, area: AreaKey?) {
        if (area == null || area == homeArea(signal)) values = values + (signal to value)
    }

    private fun homeArea(signal: SignalId): AreaKey = catalog[signal]?.area ?: AreaKeys.GLOBAL

    private fun settle(outcome: Pair<List<ResultUpdate>, ResultTracker>) {
        tracker = outcome.second
        outcome.first.forEach { update -> emit { observer.onResult(update) } }
    }

    /** Drops counters of routines that are gone and idle for a day, so a partial routine list cannot wipe limits. */
    private fun pruneCounters(now: Instant) {
        val ids = routines.map { it.routine.id }.toSet()
        val cutoff = now.toEpochMilli() - COUNTER_RETENTION_MS
        snapshot = snapshot.copy(
            counters = snapshot.counters.filter { (id, counters) -> id in ids || (counters.lastFiredAtEpochMs ?: Long.MIN_VALUE) > cutoff },
        )
    }

    private fun roundingOf(signal: SignalId): Rounding = catalog[signal]?.rounding ?: Rounding.None

    /** On failure: keep the stored file untouched and fail safe by starting every cooldown now. */
    private suspend fun loadSnapshot(now: Instant): EngineSnapshot = try {
        store.load()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        storeWritable = false
        emit { observer.onDecision(Decision.StoreFailed(e.message)) }
        EngineSnapshot(counters = routines.associate { it.routine.id to RoutineCounters(lastFiredAtEpochMs = now.toEpochMilli()) })
    }

    /** Saves only when something changed. On failure the next change retries. */
    private suspend fun persist() {
        if (!storeWritable || snapshot == persisted) return
        try {
            store.save(snapshot)
            persisted = snapshot
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit { observer.onDecision(Decision.StoreFailed(e.message)) }
        }
    }

    /** Observers (debug panel, habit book) must never break the engine; a failing one is ignored. */
    private inline fun emit(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The observer itself is broken; there is nowhere left to report to.
        }
    }

    private companion object {
        val MAX_BACKWARD_CORRECTION: Duration = Duration.ofHours(1)
        const val LATE_ECHO_GRACE_MS: Long = 30_000
        const val COUNTER_RETENTION_MS: Long = 24 * 60 * 60_000
        const val MAX_REPLANS: Int = 1
        /** A wait due at most this long ago still resumes after a restart. */
        const val RESUME_GRACE_MS: Long = 5 * 60_000
        /** Marks the end of the event channel in [runLoop]. */
        val CLOSED = Stamped(EngineEvent.Tick, 0, Instant.EPOCH)
        val ANSWER_SIGNALS: Set<SignalId> = setOf(SignalIds.SEAT_OCCUPIED_DRIVER, SignalIds.SEAT_OCCUPIED_PASSENGER, SignalIds.DOOR_LOCK)
    }
}
