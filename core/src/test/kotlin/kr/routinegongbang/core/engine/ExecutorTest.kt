package kr.routinegongbang.core.engine

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kr.routinegongbang.core.model.Action
import kr.routinegongbang.core.model.AreaKey
import kr.routinegongbang.core.model.AreaKeys
import kr.routinegongbang.core.model.Policy
import kr.routinegongbang.core.model.Routine
import kr.routinegongbang.core.model.RoutineValidator
import kr.routinegongbang.core.model.SignalId
import kr.routinegongbang.core.model.SignalIds
import kr.routinegongbang.core.model.SignalValue
import kr.routinegongbang.core.model.Trigger
import kr.routinegongbang.core.model.ValidatedRoutine
import kr.routinegongbang.core.model.ValidationOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExecutorTest {

    private class FakeWriter(private val failOn: Set<SignalId> = emptySet()) : VehicleWriter {
        val writes = mutableListOf<Pair<SignalId, SignalValue>>()
        override suspend fun write(signal: SignalId, area: AreaKey, value: SignalValue): Result<Unit> {
            writes += signal to value
            return if (signal in failOn) Result.failure(IllegalStateException("boom")) else Result.success(Unit)
        }
    }

    private class FakeNotifier : Notifier {
        val messages = mutableListOf<NotifyMessage>()
        override suspend fun notify(message: NotifyMessage) {
            messages += message
        }
    }

    private class FakeNavigator : Navigator {
        val destinations = mutableListOf<String>()
        override suspend fun setDestination(placeId: String): Result<Unit> {
            destinations += placeId
            return Result.success(Unit)
        }
    }

    private class FakeConfirmer(private val answer: Boolean?, private val delayMs: Long = 0) : Confirmer {
        var asked = 0
        override suspend fun confirm(request: ConfirmRequest): Boolean {
            asked++
            delay(delayMs)
            return answer ?: run { delay(Long.MAX_VALUE); false }
        }
    }

    private val heatOn = Action.Write(SignalIds.STEERING_WHEEL_HEAT, SignalValue.of(1))
    private val defrostOn = Action.Write(SignalIds.DEFROSTER_FRONT, SignalValue.Bool(true))
    private val lockDoors = Action.Write(SignalIds.DOOR_LOCK, SignalValue.Bool(true))

    private fun validated(vararg actions: Action, policy: Policy = Policy()): ValidatedRoutine {
        val routine = Routine(id = "r1", name = "test", trigger = Trigger.RideStart, actions = actions.toList(), policy = policy)
        return (RoutineValidator(testCatalog).validated(routine) as ValidationOutcome.Valid).routine
    }

    private fun executor(writer: VehicleWriter = FakeWriter(), notifier: Notifier = FakeNotifier(), confirmer: Confirmer = FakeConfirmer(true), navigator: Navigator = FakeNavigator()) =
        Executor(ActionPlanner(testCatalog), writer, notifier, navigator, confirmer)

    private fun state() = ExecutorState(WriteLedger(testCatalog), WriteRateLimiter(maxPerWindow = 10, windowMs = 60_000))

    @Test
    fun `auto writes run in list order and are recorded in the ledger as sent`() = runTest {
        val writer = FakeWriter()
        val report = executor(writer).run(validated(heatOn, defrostOn), { context(PARKED) }, state(), nowMs = { 0 })
        assertEquals(listOf(SignalIds.STEERING_WHEEL_HEAT, SignalIds.DEFROSTER_FRONT), writer.writes.map { it.first })
        assertEquals(listOf<ActionResult>(ActionResult.Sent(SignalIds.STEERING_WHEEL_HEAT), ActionResult.Sent(SignalIds.DEFROSTER_FRONT)), report.results)
        assertEquals(2, report.state.ledger.pending.size)
    }

    @Test
    fun `value already set is not written again`() = runTest {
        val writer = FakeWriter()
        val ctx = context(PARKED, values = mapOf(SignalIds.STEERING_WHEEL_HEAT to SignalValue.of(1)))
        val report = executor(writer).run(validated(heatOn), { ctx }, state(), nowMs = { 0 })
        assertTrue(writer.writes.isEmpty())
        assertEquals(listOf<ActionResult>(ActionResult.NoChange), report.results)
    }

    @Test
    fun `failed write is reported and the next action still runs`() = runTest {
        val writer = FakeWriter(failOn = setOf(SignalIds.STEERING_WHEEL_HEAT))
        val report = executor(writer).run(validated(heatOn, defrostOn), { context(PARKED) }, state(), nowMs = { 0 })
        assertTrue(report.results[0] is ActionResult.Failed)
        assertEquals(ActionResult.Sent(SignalIds.DEFROSTER_FRONT), report.results[1])
        assertEquals(1, report.state.ledger.pending.size, "failed writes are not awaited")
    }

    @Test
    fun `closed control sdk notifies with the reason instead of writing`() = runTest {
        val writer = FakeWriter()
        val notifier = FakeNotifier()
        val report = executor(writer, notifier).run(validated(heatOn), { context(PARKED, CONTROL_CLOSED) }, state(), nowMs = { 0 })
        assertTrue(writer.writes.isEmpty())
        assertEquals(listOf<ActionResult>(ActionResult.Notified(Reason.SDK_NOT_OPEN)), report.results)
        assertEquals(Reason.SDK_NOT_OPEN, notifier.messages.single().reason)
    }

    @Test
    fun `confirmation is asked once for the routine and then actions run`() = runTest {
        val confirmer = FakeConfirmer(true)
        val writer = FakeWriter()
        executor(writer, confirmer = confirmer).run(validated(lockDoors, heatOn), { context(PARKED) }, state(), nowMs = { 0 })
        assertEquals(1, confirmer.asked)
        assertEquals(2, writer.writes.size)
    }

    @Test
    fun `declined confirmation cancels the remaining actions`() = runTest {
        val writer = FakeWriter()
        val report = executor(writer, confirmer = FakeConfirmer(false)).run(validated(lockDoors, heatOn), { context(PARKED) }, state(), nowMs = { 0 })
        assertTrue(writer.writes.isEmpty())
        assertEquals(listOf<ActionResult>(ActionResult.Cancelled(Reason.CONFIRM_DECLINED), ActionResult.Cancelled(Reason.CONFIRM_DECLINED)), report.results)
    }

    @Test
    fun `unanswered confirmation times out after half a minute`() = runTest {
        // 10 s is too short for a driver to answer.
        val report = executor(confirmer = FakeConfirmer(answer = null)).run(validated(lockDoors), { context(PARKED) }, state(), nowMs = { 0 })
        assertEquals(listOf<ActionResult>(ActionResult.Cancelled(Reason.CONFIRM_TIMEOUT)), report.results)
        assertEquals(30_000L, testScheduler.currentTime)
    }

    @Test
    fun `plan is re-checked after confirmation so a car that started driving skips body actions`() = runTest {
        var driving = PARKED
        val confirmer = object : Confirmer {
            override suspend fun confirm(request: ConfirmRequest): Boolean {
                driving = DRIVING
                return true
            }
        }
        val writer = FakeWriter()
        val report = executor(writer, confirmer = confirmer).run(validated(lockDoors), { context(driving) }, state(), nowMs = { 0 })
        assertTrue(writer.writes.isEmpty())
        assertEquals(listOf<ActionResult>(ActionResult.Skipped(Reason.BODY_WHILE_DRIVING)), report.results)
    }

    @Test
    fun `notify action and destination use their ports`() = runTest {
        val notifier = FakeNotifier()
        val navigator = FakeNavigator()
        val report = executor(notifier = notifier, navigator = navigator)
            .run(validated(Action.Notify("hello"), Action.SetDestination("home")), { context(PARKED, CONTROL_CLOSED) }, state(), nowMs = { 0 })
        assertEquals("hello", notifier.messages.single().textRes)
        assertEquals(listOf("home"), navigator.destinations)
        assertEquals(listOf(ActionResult.Notified(null), ActionResult.Requested), report.results)
    }

    @Test
    fun `rate limit applies across actions within one run`() = runTest {
        val tight = ExecutorState(WriteLedger(testCatalog), WriteRateLimiter(maxPerWindow = 1, windowMs = 60_000))
        val report = executor().run(validated(heatOn, defrostOn), { context(PARKED) }, tight, nowMs = { 0 })
        assertEquals(ActionResult.Skipped(Reason.RATE_LIMITED), report.results[1])
    }

    @Test
    fun `dry run is marked in the report`() = runTest {
        val report = executor().run(validated(heatOn), { context(PARKED) }, state(), nowMs = { 0 }, dryRun = true)
        assertTrue(report.dryRun)
    }

    @Test
    fun `H36 dry runs use their own quota and leave the routines' write history alone`() = runTest {
        val full = (0 until 10).fold(WriteRateLimiter(maxPerWindow = 10, windowMs = 60_000)) { limiter, _ -> limiter.record(nowMs = 0) }
        val busy = ExecutorState(WriteLedger(testCatalog), full)
        val report = executor().run(validated(heatOn), { context(PARKED) }, busy, nowMs = { 1 }, dryRun = true)
        assertEquals(listOf<ActionResult>(ActionResult.Sent(SignalIds.STEERING_WHEEL_HEAT)), report.results)
        assertEquals(busy.rateLimiter, report.state.rateLimiter, "testing in the editor must not use up the routines' quota")
        assertEquals(busy.history, report.state.history)
    }

    @Test
    fun `dry runs keep to each signal's own limits, which protect the hardware`() = runTest {
        val perMinute = testCatalog.getOrThrow(SignalIds.STEERING_WHEEL_HEAT).profile!!.maxWritesPerMinute
        val tested = (0 until perMinute).fold(WriteHistory()) { h, i -> h.record(SignalIds.STEERING_WHEEL_HEAT, SignalValue.of(i % 2 + 2), atMs = i.toLong()) }
        val report = executor().run(validated(heatOn), { context(PARKED) }, state().copy(dryRunHistory = tested), nowMs = { 10 }, dryRun = true)
        assertEquals(listOf<ActionResult>(ActionResult.Skipped(Reason.RATE_LIMITED)), report.results)
    }

    @Test
    fun `routine writes are kept in the history the flip-flop and per-signal limits read`() = runTest {
        val report = executor().run(validated(heatOn), { context(PARKED) }, state(), nowMs = { 5 })
        assertEquals(1, report.state.history.countSince(SignalIds.STEERING_WHEEL_HEAT, sinceMs = 0))
    }

    @Test
    fun `dry run is refused while driving`() = runTest {
        val writer = FakeWriter()
        val report = executor(writer).run(validated(heatOn), { context(DRIVING) }, state(), nowMs = { 0 }, dryRun = true)
        assertTrue(writer.writes.isEmpty())
        assertEquals(listOf<ActionResult>(ActionResult.Skipped(Reason.NOT_PARKED)), report.results)
    }

    @Test
    fun `write with an explicit area keeps that area for result tracking`() = runTest {
        val area = AreaKeys.DRIVER_SEAT
        val seatHeat = Action.Write(SignalIds.SEAT_HEAT_DRIVER, SignalValue.of(2), area = area)
        val writer = FakeWriter()
        val report = executor(writer).run(validated(seatHeat), { context(PARKED) }, state(), nowMs = { 0 })
        assertEquals(ActionResult.Sent(SignalIds.SEAT_HEAT_DRIVER, area), report.results.single())
        assertEquals(area, report.state.ledger.pending.single().area)
    }

    @Test
    fun `writer that throws is reported as failed instead of crashing the engine`() = runTest {
        val throwing = object : VehicleWriter {
            override suspend fun write(signal: SignalId, area: AreaKey, value: SignalValue): Result<Unit> = error("sdk crash")
        }
        val report = executor(throwing).run(validated(heatOn, defrostOn), { context(PARKED) }, state(), nowMs = { 0 })
        assertTrue(report.results.all { it is ActionResult.Failed })
    }

    @Test
    fun `preview lists tiers without touching any port`() {
        val writer = FakeWriter()
        val preview = executor(writer).preview(validated(heatOn, lockDoors), context(DRIVING))
        assertEquals(listOf(ExecutionTier.Auto, ExecutionTier.Skip(Reason.BODY_WHILE_DRIVING)), preview)
        assertTrue(writer.writes.isEmpty())
    }

    @Test
    fun `dry run really executes the actions, unlike preview`() = runTest {
        val writer = FakeWriter()
        val report = executor(writer).run(validated(heatOn), { context(PARKED) }, state(), nowMs = { 0 }, dryRun = true)
        assertEquals(listOf<ActionResult>(ActionResult.Sent(SignalIds.STEERING_WHEEL_HEAT)), report.results)
        assertEquals(1, writer.writes.size)
    }

    @Test
    fun `confirm request lists the actions that need approval`() = runTest {
        val requests = mutableListOf<ConfirmRequest>()
        val confirmer = object : Confirmer {
            override suspend fun confirm(request: ConfirmRequest): Boolean {
                requests += request
                return true
            }
        }
        executor(confirmer = confirmer).run(validated(heatOn, lockDoors), { context(PARKED) }, state(), nowMs = { 0 })
        assertEquals(listOf<Action>(lockDoors), requests.single().actions)
    }

    @Test
    fun `an approval does not cover a confirmation that appears later in the run`() = runTest {
        var navigating = false
        val confirmer = FakeConfirmer(true)
        val writer = object : VehicleWriter {
            override suspend fun write(signal: SignalId, area: AreaKey, value: SignalValue): Result<Unit> {
                navigating = true
                return Result.success(Unit)
            }
        }
        val navigator = FakeNavigator()
        executor(writer, confirmer = confirmer, navigator = navigator)
            .run(validated(lockDoors, Action.SetDestination("home")), { context(PARKED, navigating = navigating) }, state(), nowMs = { 0 })
        assertEquals(2, confirmer.asked)
        assertEquals(listOf("home"), navigator.destinations)
    }

    @Test
    fun `H32 a later confirmation in the same run only gets what is left of the budget`() = runTest {
        var navigating = false
        val writer = object : VehicleWriter {
            override suspend fun write(signal: SignalId, area: AreaKey, value: SignalValue): Result<Unit> {
                navigating = true
                return Result.success(Unit)
            }
        }
        val report = executor(writer, confirmer = FakeConfirmer(true, delayMs = Executor.CONFIRM_TIMEOUT_MS * 6 / 10))
            .run(validated(lockDoors, Action.SetDestination("home")), { context(PARKED, navigating = navigating) }, state(), nowMs = { testScheduler.currentTime })
        assertEquals(listOf(ActionResult.Sent(SignalIds.DOOR_LOCK), ActionResult.Cancelled(Reason.CONFIRM_TIMEOUT)), report.results)
    }

    @Test
    fun `suppressed actions are reported as conflicts and never planned or confirmed`() = runTest {
        val confirmer = FakeConfirmer(true)
        val report = executor(confirmer = confirmer).run(validated(lockDoors, heatOn), { context(PARKED) }, state(), nowMs = { 0 }, suppressed = setOf(0))
        assertEquals(listOf(ActionResult.Skipped(Reason.CONFLICT), ActionResult.Sent(SignalIds.STEERING_WHEEL_HEAT)), report.results)
        assertEquals(0, confirmer.asked)
    }

    @Test
    fun `declining a later confirmation cancels only the rest of the run`() = runTest {
        var navigating = false
        val answers = ArrayDeque(listOf(true, false))
        val confirmer = object : Confirmer {
            override suspend fun confirm(request: ConfirmRequest): Boolean = answers.removeFirst()
        }
        val writer = object : VehicleWriter {
            override suspend fun write(signal: SignalId, area: AreaKey, value: SignalValue): Result<Unit> {
                navigating = true
                return Result.success(Unit)
            }
        }
        val report = executor(writer, confirmer = confirmer)
            .run(validated(lockDoors, Action.SetDestination("home"), heatOn), { context(PARKED, navigating = navigating) }, state(), nowMs = { 0 })
        assertEquals(
            listOf(ActionResult.Sent(SignalIds.DOOR_LOCK), ActionResult.Cancelled(Reason.CONFIRM_DECLINED), ActionResult.Cancelled(Reason.CONFIRM_DECLINED)),
            report.results,
        )
    }

    @Test
    fun `a confirmer that crashes cancels the run with its own reason`() = runTest {
        val confirmer = object : Confirmer {
            override suspend fun confirm(request: ConfirmRequest): Boolean = error("ui gone")
        }
        val report = executor(confirmer = confirmer).run(validated(lockDoors), { context(PARKED) }, state(), nowMs = { 0 })
        assertEquals(listOf<ActionResult>(ActionResult.Cancelled(Reason.CONFIRM_FAILED)), report.results)
    }

    @Test
    fun `coroutine cancellation from a port is not turned into a failed result`() = runTest {
        val writer = object : VehicleWriter {
            override suspend fun write(signal: SignalId, area: AreaKey, value: SignalValue): Result<Unit> =
                throw kotlinx.coroutines.CancellationException("service stopped")
        }
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            executor(writer).run(validated(heatOn), { context(PARKED) }, state(), nowMs = { 0 })
        }
    }

    @Test
    fun `pending write is in the ledger before the write call returns`() = runTest {
        var ledgerDuringWrite: WriteLedger? = null
        var holder: ExecutorState? = null
        val writer = object : VehicleWriter {
            override suspend fun write(signal: SignalId, area: AreaKey, value: SignalValue): Result<Unit> {
                ledgerDuringWrite = holder?.ledger
                return Result.success(Unit)
            }
        }
        executor(writer).run(validated(heatOn), { context(PARKED) }, state(), nowMs = { 0 }, onState = { holder = it })
        assertEquals(1, ledgerDuringWrite?.pending?.size)
    }

    @Test
    fun `failed write rolls the pending entry back`() = runTest {
        val states = mutableListOf<ExecutorState>()
        val writer = FakeWriter(failOn = setOf(SignalIds.STEERING_WHEEL_HEAT))
        val report = executor(writer).run(validated(heatOn), { context(PARKED) }, state(), nowMs = { 0 }, onState = { states += it })
        assertTrue(report.state.ledger.pending.isEmpty())
        assertEquals(report.state, states.last())
    }

    @Test
    fun `body action with unknown driving state is skipped`() = runTest {
        val unknown = DrivingState(gear = null, speedKph = null, uxRestricted = null)
        val writer = FakeWriter()
        val report = executor(writer).run(validated(lockDoors), { context(unknown) }, state(), nowMs = { 0 })
        assertTrue(writer.writes.isEmpty())
        assertEquals(listOf<ActionResult>(ActionResult.Skipped(Reason.BODY_WHILE_DRIVING)), report.results)
    }

    @Test
    fun `remind waits until the car is parked`() = runTest {
        val notifier = FakeNotifier()
        val report = executor(notifier = notifier).run(validated(Action.Remind("remind.umbrella")), { context(DRIVING) }, state(), nowMs = { 0 })
        assertTrue(notifier.messages.isEmpty())
        assertEquals(listOf<ActionResult>(ActionResult.Deferred), report.results)
    }

    @Test
    fun `a notice the app holds until parking is reported as held, not as told`() = runTest {
        // While driving the app keeps notices for parking, so "told" is not true yet.
        val holding = object : Notifier {
            override suspend fun notify(message: NotifyMessage) = Unit
            override suspend fun deliver(message: NotifyMessage) = false
        }
        val report = executor(notifier = holding).run(validated(Action.Notify("hello")), { context(PARKED, CONTROL_CLOSED) }, state(), nowMs = { 0 })
        assertEquals(listOf<ActionResult>(ActionResult.Notified(null, held = true)), report.results)
    }

    @Test
    fun `remind while parked is shown right away`() = runTest {
        val notifier = FakeNotifier()
        val report = executor(notifier = notifier).run(validated(Action.Remind("remind.umbrella")), { context(PARKED) }, state(), nowMs = { 0 })
        assertEquals("remind.umbrella", notifier.messages.single().textRes)
        assertEquals(listOf(ActionResult.Notified(null)), report.results)
    }
}
