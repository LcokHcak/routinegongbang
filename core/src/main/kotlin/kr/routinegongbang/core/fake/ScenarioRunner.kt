package kr.routinegongbang.core.fake

import kr.routinegongbang.core.engine.ActionPlanner
import kr.routinegongbang.core.engine.ActionResult
import kr.routinegongbang.core.engine.Decision
import kr.routinegongbang.core.engine.EngineEvent
import kr.routinegongbang.core.engine.EngineObserver
import kr.routinegongbang.core.engine.EngineStartup
import kr.routinegongbang.core.engine.ExecutionReport
import kr.routinegongbang.core.engine.Executor
import kr.routinegongbang.core.engine.InMemoryEngineStore
import kr.routinegongbang.core.engine.ResultUpdate
import kr.routinegongbang.core.engine.RoutineEngine
import kr.routinegongbang.core.habit.FeedbackKind
import kr.routinegongbang.core.habit.HabitBook
import kr.routinegongbang.core.habit.HabitDetector
import kr.routinegongbang.core.habit.HabitLog
import kr.routinegongbang.core.habit.HabitRecorder
import kr.routinegongbang.core.model.RoutineValidator
import kr.routinegongbang.core.model.SignalCatalog
import kr.routinegongbang.core.model.SignalId
import kr.routinegongbang.core.model.SignalValue
import kr.routinegongbang.core.model.ValidatedRoutine
import kr.routinegongbang.core.model.ValidationOutcome
import java.time.Instant

/** [transcript] lists every step and every engine decision, for reading a failure. */
data class ScenarioResult(val name: String, val failures: List<String>, val transcript: List<String>) {
    val passed: Boolean get() = failures.isEmpty()
}

/**
 * Runs a [Scenario] against the real engine, executor and habit book with a [FakeVehicle] and fake ports.
 * Never throws for a bad script: problems are reported as failures with their step number.
 */
class ScenarioRunner(private val catalog: SignalCatalog = SignalCatalog.default()) {

    suspend fun run(scenario: Scenario): ScenarioResult {
        val validator = RoutineValidator(catalog)
        val routines = scenario.routines.map { routine ->
            when (val outcome = validator.validated(routine)) {
                is ValidationOutcome.Valid -> outcome.routine
                is ValidationOutcome.Invalid -> return ScenarioResult(scenario.name, listOf("routine ${routine.id} is invalid: ${outcome.issues}"), emptyList())
            }
        }
        val session = ScenarioSession(catalog, scenario, routines)
        session.start()
        scenario.steps.forEachIndexed { index, step -> session.apply(index + 1, step) }
        return ScenarioResult(scenario.name, session.failures.toList(), session.transcript.toList())
    }
}

/** Mutable state of one scenario run; confined to the coroutine calling [ScenarioRunner.run]. */
private class ScenarioSession(
    private val catalog: SignalCatalog,
    private val scenario: Scenario,
    private var routines: List<ValidatedRoutine>,
) : EngineObserver {
    val failures = mutableListOf<String>()
    val transcript = mutableListOf<String>()

    private val clock = ManualClock(scenario.startInstant)
    private val vehicle = FakeVehicle(catalog, clock, initialValues(), scenario.echoDelayMs, failingSignals())
    private val notifier = FakeNotifier()
    private val navigator = FakeNavigator()
    private val confirmer = FakeConfirmer()
    private val habits = HabitBook(HabitRecorder(catalog, scenario.zoneId), HabitDetector(catalog, scenario.zoneId), HabitLog())
    private val reports = mutableListOf<ExecutionReport>()
    private val confirmed = mutableListOf<SignalId>()
    private val decisions = mutableListOf<String>()
    private var lastTripId: String? = null
    private var tripCount = 0
    private var marks = Marks(0, 0, 0, 0, 0, 0, 0)

    private val engine = RoutineEngine(
        catalog = catalog,
        executor = Executor(ActionPlanner(catalog), vehicle, notifier, navigator, confirmer),
        notifier = notifier,
        store = InMemoryEngineStore(),
        clock = clock,
        zone = scenario.zoneId,
        observer = this,
        newTripId = { "trip-${++tripCount}" },
    )
    private val driver = ScenarioDriver(catalog, DirectInput(engine), vehicle, clock)

    /** Where the previous expect step stopped looking in each log. */
    private data class Marks(
        val writes: Int,
        val notifications: Int,
        val destinations: Int,
        val requests: Int,
        val confirmed: Int,
        val reports: Int,
        val decisions: Int,
    )

    suspend fun start() {
        val capability = scenario.capability.snapshot(catalog, scenario.simulated)
        engine.start(EngineStartup(capability, routines, vehicle.readings(), scenario.driving.state))
    }

    suspend fun apply(index: Int, step: ScenarioStep) {
        val label = "step $index (${step.kind})"
        transcript += "$label $step"
        run(step).forEach { failures += "$label: $it" }
    }

    private suspend fun run(step: ScenarioStep): List<String> = driver.run(step) ?: when (step) {
        is ScenarioStep.ConfirmAnswer -> emptyList<String>().also { confirmer.answer = step.value }
        is ScenarioStep.RunNow ->
            if (routines.none { it.routine.id == step.routineId }) listOf("unknown routine ${step.routineId}") else driver.send(EngineEvent.RunNow(step.routineId))
        ScenarioStep.AcceptSuggestion -> acceptSuggestion()
        is ScenarioStep.Expect -> ScenarioChecks(step).check().also { mark() }
        else -> emptyList()
    }

    private suspend fun acceptSuggestion(): List<String> {
        val tripId = lastTripId ?: return listOf("no trip yet, nothing to accept")
        val nowMs = clock.now().toEpochMilli()
        val suggestion = habits.bestSuggestion(tripId, routines.map { it.routine }, nowMs) ?: return listOf("no suggestion to accept")
        habits.answer(suggestion.pattern, tripId, FeedbackKind.ACCEPTED, nowMs)
        routines = routines + suggestion.routine
        return driver.send(EngineEvent.RoutinesChanged(routines))
    }

    private fun mark() {
        marks = Marks(
            vehicle.writes.size, notifier.messages.size, navigator.destinations.size,
            confirmer.requests.size, confirmed.size, reports.size, decisions.size,
        )
    }

    /** Compares one expect step with what happened since the previous one. */
    private inner class ScenarioChecks(private val expect: ScenarioStep.Expect) {
        private val errors = mutableListOf<String>()

        fun check(): List<String> {
            expect.writes?.let { compare("writes", it, vehicle.writes.drop(marks.writes).map { w -> ExpectedWrite(w.signal.value, w.value) }) }
            expect.notifications?.let { checkNotifications(it) }
            expect.destinations?.let { compare("destinations", it, navigator.destinations.drop(marks.destinations)) }
            expect.confirmations?.let { compare("confirmations", it, confirmer.requests.size - marks.requests) }
            expect.confirmed?.let { compare("confirmed", it.toSet(), confirmed.drop(marks.confirmed).map { s -> s.value }.toSet()) }
            expect.values?.forEach { (signal, value) -> compare("value of $signal", value, vehicle.value(SignalId(signal))) }
            checkSuggestion()
            expect.simulated?.let { checkSimulated(it) }
            expect.decisions?.let { compare("decisions", it, decisions.drop(marks.decisions)) }
            return errors
        }

        private fun <T> compare(what: String, expected: T, actual: T) {
            if (expected != actual) errors += "$what expected $expected but was $actual"
        }

        private fun checkNotifications(expected: List<ExpectedNotification>) {
            val actual = notifier.messages.drop(marks.notifications)
            val matches = expected.size == actual.size && expected.zip(actual).all { (e, a) -> e.text == a.textRes && (e.reason == null || e.reason == a.reason) }
            if (!matches) errors += "notifications expected $expected but was ${actual.map { ExpectedNotification(it.textRes, it.reason) }}"
        }

        private fun checkSuggestion() {
            if (expect.suggestion == null && !expect.noSuggestion) return
            val tripId = lastTripId ?: return run { errors += "suggestion: no trip yet to suggest for" }
            val current = habits.bestSuggestion(tripId, routines.map { r -> r.routine }, clock.now().toEpochMilli())
            val actual = current?.let { ExpectedWrite(it.pattern.signal.value, it.pattern.value) }
            compare("suggestion", expect.suggestion, actual)
        }

        private fun checkSimulated(expected: Boolean) {
            val runs = reports.drop(marks.reports)
            if (runs.isEmpty()) errors += "simulated expected $expected but no routine ran"
            runs.filter { it.simulated != expected }.forEach { errors += "routine ${it.routineId} simulated=${it.simulated}, expected $expected" }
        }
    }

    // EngineObserver: transcript, logs for expectations, and the habit book.

    override fun onDecision(decision: Decision) {
        transcript += "    " + when (decision) {
            is Decision.Ran -> "ran ${decision.report.routineId}${if (decision.report.dryRun) " (dry run)" else ""}: ${decision.report.results}"
            else -> decision.toString()
        }
        if (decision is Decision.Ran) reports += decision.report
        label(decision)?.let { decisions += it }
    }

    private fun label(decision: Decision): String? = when (decision) {
        is Decision.LimitBlocked -> "${decision.routineId}:LimitBlocked(${decision.reason})"
        is Decision.ConditionNotMet -> "${decision.routineId}:ConditionNotMet"
        is Decision.Ran -> {
            val kind = if (decision.report.dryRun) "DryRun" else "Ran"
            "${decision.report.routineId}:$kind(${decision.report.results.joinToString(",") { label(it) }})"
        }
        else -> null
    }

    private fun label(result: ActionResult): String = when (result) {
        is ActionResult.Sent -> "Sent"
        ActionResult.NoChange -> "NoChange"
        is ActionResult.Failed -> "Failed"
        is ActionResult.Notified -> result.reason?.let { "Notified:$it" } ?: "Notified"
        is ActionResult.Cancelled -> "Cancelled:${result.reason}"
        is ActionResult.Skipped -> "Skipped:${result.reason}"
        ActionResult.Deferred -> "Deferred"
        ActionResult.Requested -> "Requested"
        is ActionResult.Confirmed -> "Confirmed"
        is ActionResult.SentUnconfirmed -> "SentUnconfirmed"
        ActionResult.Scheduled -> "Scheduled"
        ActionResult.Waited -> "Waited"
    }

    override fun onResult(update: ResultUpdate) {
        transcript += "    result ${update.routineId}#${update.actionIndex}: ${update.result}"
        (update.result as? ActionResult.Confirmed)?.let { confirmed += it.signal }
    }

    override fun onTripStarted(tripId: String, at: Instant, values: Map<SignalId, SignalValue>) {
        transcript += "    trip started $tripId"
        lastTripId = tripId
        // With the values known at the start, like the app's habit book (cabin temperature).
        habits.onTripStarted(tripId, at, values)
    }

    override fun onUserChange(tripId: String?, signal: SignalId, value: SignalValue, at: Instant) {
        habits.onUserChange(tripId, signal, value, at)
    }

    private fun initialValues(): Map<SignalId, SignalValue> = scenario.initial.mapNotNull { (signal, value) ->
        val id = SignalId(signal)
        val problem = catalog.valueProblem(id, value)
        if (problem != null) {
            failures += "initial: $problem"
            null
        } else {
            id to value
        }
    }.toMap()

    private fun failingSignals(): Set<SignalId> = scenario.failing.mapNotNull { signal ->
        SignalId(signal).takeIf { catalog[it] != null } ?: run {
            failures += "failing: unknown signal $signal"
            null
        }
    }.toSet()

}
