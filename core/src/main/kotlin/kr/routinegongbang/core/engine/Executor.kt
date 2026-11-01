package kr.routinegongbang.core.engine

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kr.routinegongbang.core.model.Action
import kr.routinegongbang.core.model.Policy
import kr.routinegongbang.core.model.ValidatedRoutine
import kotlin.coroutines.cancellation.CancellationException

/**
 * Engine-owned state the executor reads and returns updated. Dry runs ("지금 실행해보기", try it now) use their
 * own [dryRunLimiter] and stay out of [history], so testing in the editor never blocks a real routine.
 */
data class ExecutorState(
    val ledger: WriteLedger,
    val rateLimiter: WriteRateLimiter,
    val history: WriteHistory = WriteHistory(),
    val dryRunLimiter: WriteRateLimiter = WriteRateLimiter.default(),
    /** Dry-run writes: they count against each signal's limits, which protect the hardware. */
    val dryRunHistory: WriteHistory = WriteHistory(),
)

/** [simulated] mirrors the capability snapshot so the UI can label results as "모의" (simulated). */
data class ExecutionReport(
    val routineId: String,
    val results: List<ActionResult>,
    val state: ExecutorState,
    val dryRun: Boolean,
    val simulated: Boolean = false,
    /** Set by the engine; later [ResultUpdate]s carry it so they reach this run and not a newer one. */
    val runId: Long = 0,
    /** [results] start at this action index (a segment between waits); 0 for a whole routine. */
    val firstIndex: Int = 0,
)

/**
 * At most [totalMs] of waiting for answers per plan, however many questions it asks.
 * The clock starts at the first question. Times come from the caller's clock, so tests can drive them.
 */
class ConfirmBudget(private val totalMs: Long = Executor.CONFIRM_TIMEOUT_MS) {
    private var deadlineMs: Long? = null

    fun remaining(nowMs: Long): Long {
        val deadline = deadlineMs ?: (nowMs + totalMs).also { deadlineMs = it }
        return (deadline - nowMs).coerceAtLeast(0)
    }
}

/**
 * Runs the actions of one routine in list order.
 * Re-plans every action with a fresh context right before executing it, asks again whenever an action
 * needs a confirmation the user has not given yet, and never lets a port failure stop the remaining actions.
 * Inside a plan the engine asks once for all routines and passes the answer in; actions that
 * lost the arbitration are [suppressed] and reported as conflicts.
 */
class Executor(
    private val planner: ActionPlanner,
    private val writer: VehicleWriter,
    private val notifier: Notifier,
    private val navigator: Navigator,
    private val confirmer: Confirmer,
) {

    /** Rehearsal mode: lists what would happen without calling any port. */
    fun preview(validated: ValidatedRoutine, ctx: ExecutionContext): List<ExecutionTier> =
        validated.routine.actions.map { planner.plan(it, validated.routine.policy, ctx, nowMs = 0, limiter = null) }

    /**
     * The confirmations [validated] needs right now, by action index, leaving out [suppressed] actions. With a
     * [state], an action its rate limits or the flip-back check would skip anyway is not asked about.
     */
    fun confirmations(
        validated: ValidatedRoutine,
        ctx: ExecutionContext,
        suppressed: Set<Int> = emptySet(),
        state: ExecutorState? = null,
        nowMs: Long = 0,
        dryRun: Boolean = false,
        range: IntRange = validated.routine.actions.indices,
    ): Map<Int, ExecutionTier.Confirm> {
        val routine = validated.routine
        return routine.actions.withIndex()
            .filter { it.index in range && it.index !in suppressed }
            .associate { (index, action) ->
                index to (state?.let { planFor(action, routine.policy, ctx, nowMs, it, dryRun) } ?: planner.plan(action, routine.policy, ctx, nowMs = 0))
            }
            .filterValues { it is ExecutionTier.Confirm }
            .mapValues { it.value as ExecutionTier.Confirm }
    }

    /**
     * [dryRun] is "지금 실행해보기" (try it now): a real run started by hand, only while parked, that the caller does not
     * count toward limits. Use [preview] for a run without side effects.
     * [onState] receives every state change as it happens, so a cancelled run never loses a pending write.
     * [approved] null means this run asks its own upfront question; otherwise the plan already asked, and a
     * non-null [refused] is its answer. [changes] counts events posted meanwhile: if it moved during a question,
     * the state the answer was given for is gone, so the rest of the run is dropped.
     */
    suspend fun run(
        validated: ValidatedRoutine,
        contextProvider: () -> ExecutionContext,
        state: ExecutorState,
        nowMs: () -> Long,
        dryRun: Boolean = false,
        onState: (ExecutorState) -> Unit = {},
        suppressed: Set<Int> = emptySet(),
        approved: Map<Int, ExecutionTier.Confirm>? = null,
        refused: Reason? = null,
        budget: ConfirmBudget = ConfirmBudget(),
        changes: () -> Long = { 0 },
        range: IntRange = validated.routine.actions.indices,
    ): ExecutionReport {
        val routine = validated.routine
        val initial = contextProvider()
        fun report(results: List<ActionResult>, finalState: ExecutorState) =
            ExecutionReport(routine.id, results, finalState, dryRun, initial.capability.simulated, firstIndex = range.first)
        fun cancelledFrom(start: Int, done: List<ActionResult>, reason: Reason) =
            done + (start..range.last).map { if (it in suppressed) ActionResult.Skipped(Reason.CONFLICT) else ActionResult.Cancelled(reason) }

        if (dryRun && !initial.driving.isParked) return report(range.map { ActionResult.Skipped(Reason.NOT_PARKED) }, state)
        if (refused != null) return report(cancelledFrom(range.first, emptyList(), refused), state)

        var granted = approved ?: confirmations(validated, initial, suppressed, state, nowMs(), dryRun, range).also { upfront ->
            if (upfront.isNotEmpty()) {
                val mark = changes()
                ask(routine.id, routine.name, upfront.keys.sorted().map { routine.actions[it] }, upfront.values, budget, nowMs)
                    ?.let { refusal -> return report(cancelledFrom(range.first, emptyList(), refusal), state) }
                if (changes() != mark) return report(cancelledFrom(range.first, emptyList(), Reason.STATE_CHANGED), state)
            }
        }
        var current = state
        val results = mutableListOf<ActionResult>()
        for (index in range) {
            val action = routine.actions[index]
            if (index in suppressed) {
                results += ActionResult.Skipped(Reason.CONFLICT)
                continue
            }
            var ctx = contextProvider()
            var tier = planFor(action, routine.policy, ctx, nowMs(), current, dryRun)
            if (tier is ExecutionTier.Confirm && granted[index] != tier) {
                val mark = changes()
                ask(routine.id, routine.name, listOf(action), listOf(tier), budget, nowMs)
                    ?.let { refusal -> return report(cancelledFrom(index, results, refusal), current) }
                if (changes() != mark) return report(cancelledFrom(index, results, Reason.STATE_CHANGED), current)
                granted = granted + (index to tier)
                ctx = contextProvider()
                tier = planFor(action, routine.policy, ctx, nowMs(), current, dryRun)
            }
            val (result, next) = execute(action, tier, ctx, current, nowMs(), dryRun, onState)
            results += result
            current = next
            onState(current)
        }
        return report(results, current)
    }

    /**
     * One question covering [actions] (from one routine, or a whole plan). Null when approved; otherwise why
     * the run is cancelled. Waits at most what is left of [budget].
     */
    suspend fun ask(
        routineId: String,
        routineName: String,
        actions: List<Action>,
        tiers: Collection<ExecutionTier.Confirm>,
        budget: ConfirmBudget,
        nowMs: () -> Long,
    ): Reason? {
        val request = ConfirmRequest(routineId, routineName, voiceOnly = tiers.any { it.voiceOnly }, actions = actions)
        val timeout = budget.remaining(nowMs())
        if (timeout <= 0) return Reason.CONFIRM_TIMEOUT
        val answer = try {
            withTimeoutOrNull(timeout) { confirmer.confirm(request) }
        } catch (e: TimeoutCancellationException) {
            // The confirmer's own timeout, not ours; still fail if this run itself was cancelled.
            currentCoroutineContext().ensureActive()
            return Reason.CONFIRM_FAILED
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Reason.CONFIRM_FAILED
        }
        return when (answer) {
            null -> Reason.CONFIRM_TIMEOUT
            false -> Reason.CONFIRM_DECLINED
            true -> null
        }
    }

    /** Dry runs have their own global quota and no flip-back check; every write, dry or not, counts against each signal's limits. */
    private fun planFor(action: Action, policy: Policy, ctx: ExecutionContext, nowMs: Long, state: ExecutorState, dryRun: Boolean): ExecutionTier {
        val rates = listOf(state.history, state.dryRunHistory)
        return if (dryRun) planner.plan(action, policy, ctx, nowMs, state.dryRunLimiter, history = null, rateHistories = rates)
        else planner.plan(action, policy, ctx, nowMs, state.rateLimiter, state.history, rates)
    }

    private suspend fun execute(
        action: Action,
        tier: ExecutionTier,
        ctx: ExecutionContext,
        state: ExecutorState,
        nowMs: Long,
        dryRun: Boolean,
        onState: (ExecutorState) -> Unit,
    ): Pair<ActionResult, ExecutorState> = when (tier) {
        ExecutionTier.Auto, is ExecutionTier.Confirm -> perform(action, ctx, state, nowMs, dryRun, onState)
        is ExecutionTier.Notify -> show(NotifyMessage(NotifyMessage.DOWNGRADED, tier.reason, action)) to state
        is ExecutionTier.Skip -> ActionResult.Skipped(tier.reason) to state
    }

    private suspend fun perform(
        action: Action,
        ctx: ExecutionContext,
        state: ExecutorState,
        nowMs: Long,
        dryRun: Boolean,
        onState: (ExecutorState) -> Unit,
    ): Pair<ActionResult, ExecutorState> = when (action) {
        is Action.Write -> write(action, ctx, state, nowMs, dryRun, onState)
        is Action.SetDestination -> safely { navigator.setDestination(action.placeId) }
            .fold({ ActionResult.Requested }, { failed(it) }) to state
        is Action.Notify -> show(NotifyMessage(action.textRes, action = action)) to state
        is Action.Remind ->
            if (ctx.driving.isParked) show(NotifyMessage(action.textRes, action = action)) to state else ActionResult.Deferred to state
        // The engine runs segments between waits, so this is only reached by a caller passing a wait in its range.
        is Action.Wait -> ActionResult.Waited to state
    }

    /**
     * The pending entry is recorded and published before the write call, so an echo that arrives during
     * the call is classified APP; a failed write rolls the entry back. The rate limit counts attempts, not successes.
     */
    private suspend fun write(
        action: Action.Write,
        ctx: ExecutionContext,
        state: ExecutorState,
        nowMs: Long,
        dryRun: Boolean,
        onState: (ExecutorState) -> Unit,
    ): Pair<ActionResult, ExecutorState> {
        val spec = planner.catalog[action.signal] ?: return ActionResult.Skipped(Reason.UNSUPPORTED) to state
        if (!state.ledger.needsWrite(spec.id, ctx.values[spec.id], action.value)) return ActionResult.NoChange to state
        val area = action.area ?: spec.area
        val ledger = state.ledger.record(spec.id, action.value, nowMs, area)
        val pending = if (dryRun) {
            state.copy(ledger = ledger, dryRunLimiter = state.dryRunLimiter.record(nowMs), dryRunHistory = state.dryRunHistory.record(spec.id, action.value, nowMs))
        } else {
            state.copy(ledger = ledger, rateLimiter = state.rateLimiter.record(nowMs), history = state.history.record(spec.id, action.value, nowMs))
        }
        onState(pending)
        return safely { writer.write(spec.id, area, action.value) }.fold(
            onSuccess = { ActionResult.Sent(spec.id, action.area) to pending },
            // The history holds what the app really changed; the rate limit still counts the attempt.
            onFailure = { failed(it) to pending.copy(ledger = state.ledger, history = state.history) },
        )
    }

    private suspend fun show(message: NotifyMessage): ActionResult =
        safely { Result.success(notifier.deliver(message)) }.fold({ shown -> ActionResult.Notified(message.reason, held = !shown) }, { failed(it) })

    private fun failed(error: Throwable) = ActionResult.Failed(Reason.ACTION_FAILED, error.message)

    /** Ports may throw instead of returning a failure; only coroutine cancellation passes through. */
    private suspend fun <T> safely(block: suspend () -> Result<T>): Result<T> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    companion object {
        /**
         * How long a run waits for "실행할까요?" ("Run it?"; per run, all questions together). 10 s is too short to
         * answer. Today questions come only while parked (voice questions while driving wait for the STT SDK); events
         * queued meanwhile wait at most this long.
         */
        const val CONFIRM_TIMEOUT_MS: Long = 30_000
    }
}
