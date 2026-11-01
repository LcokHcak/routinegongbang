package kr.routinegongbang.app.service

import kr.routinegongbang.app.R
import kr.routinegongbang.app.platform.NoticeHold
import kr.routinegongbang.app.ui.AppState
import kr.routinegongbang.app.ui.RoutineCommands
import kr.routinegongbang.app.ui.RoutineItem
import kr.routinegongbang.app.ui.SuggestionItem
import kr.routinegongbang.app.ui.editor.CheckResult
import kr.routinegongbang.app.ui.editor.Problem
import kr.routinegongbang.app.ui.editor.SaveCheck
import kr.routinegongbang.app.ui.model.RunHistory
import kr.routinegongbang.app.ui.model.RunLog
import kr.routinegongbang.app.ui.model.Skip
import kr.routinegongbang.app.ui.model.heldShown
import kr.routinegongbang.core.engine.Decision
import kr.routinegongbang.core.engine.DrivingState
import kr.routinegongbang.core.engine.EngineEvent
import kr.routinegongbang.core.engine.EngineObserver
import kr.routinegongbang.core.engine.ResultUpdate
import kr.routinegongbang.core.habit.FeedbackKind
import kr.routinegongbang.core.habit.HabitBook
import kr.routinegongbang.core.model.Action
import kr.routinegongbang.core.model.Routine
import kr.routinegongbang.core.model.RoutineValidator
import kr.routinegongbang.core.model.SafetyClass
import kr.routinegongbang.core.model.SignalCatalog
import kr.routinegongbang.core.model.ValidatedRoutine
import kr.routinegongbang.core.model.ValidationOutcome

/**
 * Turns screen commands into engine events and engine results into [AppState]. Called from the UI thread
 * (commands), the engine coroutine (observer) and the AAOS callback thread (driving). Every state change, the
 * matching [AppState] update, engine post and save happen under one lock, so they are applied in one order;
 * none of them blocks (StateFlow CAS, non-blocking post, conflated save channel).
 */
internal class RoutineController(
    private val catalog: SignalCatalog,
    initial: List<Routine>,
    private val habits: HabitBook,
    private val post: (EngineEvent) -> Unit,
    private val saveRoutines: (List<Routine>) -> Unit,
    private val log: (String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    /** Notices held while driving, released when parked again or when the state turns unknown. */
    private val onParkedAgain: (NoticeHold.Released) -> Unit = {},
) : RoutineCommands, EngineObserver {

    /**
     * Where notices wait while driving; the notifier offers every notice here. Lock order: the hold's lock, then this
     * controller's ([quiet] is asked under the hold's lock), never the other way round, so the hold is only ever
     * touched outside [lock].
     */
    val notices = NoticeHold()

    private val validator = RoutineValidator(catalog)
    private val saveCheck = SaveCheck(catalog)
    private val lock = Any()
    private var routines: List<Routine> = distinctIds(initial)
    private var history = RunHistory()
    private var runLog = RunLog()
    private var parked = false
    private var drivingKnown = false
    /** The card hidden by driving off; parked again on the same trip, it comes back (a short move would otherwise lose it). */
    private var hidden: SuggestionItem? = null

    init {
        synchronized(lock) { publishLocked() }
    }

    /** Only routines that still pass validation reach the engine. */
    fun validated(): List<ValidatedRoutine> = synchronized(lock) { validatedLocked() }

    fun onDriving(state: DrivingState) {
        var becameParked = false
        val quietEnded = synchronized(lock) {
            val was = parked
            val wasQuiet = quietLocked()
            parked = state.isParked
            val known = state.gear != null && state.speedKph != null && state.uxRestricted != null
            drivingKnown = known
            if (!state.isParked) AppState.ui.value.suggestion?.let { hidden = it }
            AppState.update {
                it.copy(
                    status = it.status.copy(parked = state.isParked, drivingKnown = known),
                    suggestion = if (state.isParked) it.suggestion else null,
                )
            }
            becameParked = state.isParked && !was
            wasQuiet && !quietLocked()
        }
        // Parked, or no longer known to be moving (otherwise held notices would wait for a parking never seen).
        if (quietEnded) releaseHeld()
        if (becameParked) refreshSuggestion()
    }

    /** Quiet only while the car is known to be moving: an unknown state would otherwise hold notices forever. */
    fun quiet(): Boolean = synchronized(lock) { quietLocked() }

    private fun quietLocked() = drivingKnown && !parked

    private fun releaseHeld() {
        val released = notices.release()
        synchronized(lock) {
            history = history.withHeldShown()
            runLog = runLog.withHeldShown()
            publishLocked()
        }
        if (released.isEmpty) return
        // The digest must not keep the parked suggestion from showing.
        runCatching { onParkedAgain(released) }.onFailure { log("held notices not shown: ${it.message}") }
    }

    /** Suggestions appear only while parked, for the trip that just happened. */
    fun refreshSuggestion() {
        // No trips left (e.g. the habit log was cleared for a replay): no card can belong to one.
        val tripId = habits.log.trips.lastOrNull()?.tripId ?: return synchronized(lock) {
            hidden = null
            AppState.update { it.copy(suggestion = null) }
        }
        val existing = synchronized(lock) { routines }
        val best = habits.bestSuggestion(tripId, existing, now())
        synchronized(lock) {
            // Re-check under the lock: the car may have started moving, or the routine may have been adopted, meanwhile.
            val fresh = best?.takeIf { parked && routines.none { r -> r.id == it.routine.routine.id } }
                ?.let { SuggestionItem(tripId, it.pattern, it.routine.routine, it.supportingTrips) }
            // Showing a card records SHOWN, which makes the detector quiet; that must not take away the card the
            // driver is reading. Only a new trip, driving off or an answer removes it. The card already in front of the
            // driver also wins over a newer habit of the same trip, which would otherwise replace it seconds later after
            // it had been counted as shown. Decided here, not inside update {}, whose block may run twice
            // when another thread updates the state meanwhile.
            val kept = (AppState.ui.value.suggestion ?: hidden)?.takeIf { parked && it.tripId == tripId && routines.none { r -> r.id == it.routine.id } }
            if (parked) hidden = null
            AppState.update { it.copy(suggestion = kept ?: fresh) }
        }
    }

    override fun setEnabled(routineId: String, enabled: Boolean) = changeRoutines { list ->
        list.map { if (it.id == routineId) it.copy(enabled = enabled) else it }
    }

    /**
     * Checked here again, never trusting the screen: only while parked, only comfort settings, and the
     * check and the write under one lock, so nothing can change the list in between (the lock is reentrant).
     */
    override fun saveRoutine(routine: Routine): CheckResult = synchronized(lock) {
        val refusal = when {
            !parked -> Problem(Problem.Level.ERROR, R.string.editor_parked_only)
            !comfortOnly(routine) -> Problem(Problem.Level.ERROR, R.string.editor_err_excluded)
            else -> null
        }
        val result = refusal?.let { CheckResult(listOf(it)) } ?: saveCheck.of(routine, routines)
        if (!result.canSave) return result.also { log("routine ${routine.id} not saved: ${result.problems}") }
        // Results are kept per action position; once the actions change they would describe other actions.
        // Any change at all makes the last skip a statement about the old routine.
        val old = routines.firstOrNull { it.id == routine.id }
        if (old != null && old.actions != routine.actions) history = history.forget(routine.id)
        if (old != routine) history = history.withoutSkip(routine.id)
        changeRoutines { list -> if (list.any { it.id == routine.id }) list.map { if (it.id == routine.id) routine else it } else list + routine }
        result
    }

    override fun deleteRoutine(routineId: String) = synchronized(lock) {
        if (!parked) return log("routine $routineId not deleted while driving")
        changeRoutines { list -> list.filterNot { it.id == routineId } }
    }

    /** The editor writes comfort settings only; body and energy controls come later. */
    private fun comfortOnly(routine: Routine): Boolean =
        routine.actions.filterIsInstance<Action.Write>().all { catalog[it.signal]?.safetyClass == SafetyClass.COMFORT }

    /** Replaces the whole list (demo scripts bring their own routines, and start the log and held notices afresh). */
    fun replaceAll(list: List<Routine>) {
        notices.clear()
        synchronized(lock) {
            runLog = RunLog()
            changeRoutines { list }
        }
    }

    private fun nameOf(routineId: String): String = routines.firstOrNull { it.id == routineId }?.name ?: routineId

    /** Allowed for disabled routines too: trying a routine before switching it on is the point of a test run. */
    override fun runNow(routineId: String) = post(EngineEvent.RunNow(routineId))

    override fun acceptSuggestion() {
        val suggestion = takeSuggestion() ?: return
        habits.answer(suggestion.pattern, suggestion.tripId, FeedbackKind.ACCEPTED, now())
        changeRoutines { list -> if (list.any { it.id == suggestion.routine.id }) list else list + suggestion.routine }
        log("suggestion accepted: ${suggestion.routine.id}")
    }

    override fun rejectSuggestion() {
        val suggestion = takeSuggestion() ?: return
        habits.answer(suggestion.pattern, suggestion.tripId, FeedbackKind.REJECTED, now())
        log("suggestion rejected: ${suggestion.routine.id}")
    }

    /** Counted once per suggestion, also across service restarts (the habit log is persisted). */
    override fun suggestionShown() {
        val suggestion = AppState.ui.value.suggestion ?: return
        val recent = now() - SHOWN_DEDUP_MS
        val alreadyCounted = habits.log.feedback.any {
            it.pattern == suggestion.pattern && it.kind == FeedbackKind.SHOWN && it.atEpochMs > recent
        }
        if (!alreadyCounted) habits.answer(suggestion.pattern, suggestion.tripId, FeedbackKind.SHOWN, now())
    }

    override fun onDecision(decision: Decision) {
        synchronized(lock) {
            history = when (decision) {
                is Decision.Ran -> {
                    // A report that does not fit the routine's actions belongs to an edited-away version.
                    // Held notices of a report arriving after the release have gone out with it.
                    val report = decision.report.let { if (quietLocked()) it else it.copy(results = it.results.heldShown()) }
                    val actions = routines.firstOrNull { it.id == report.routineId }?.actions ?: return
                    if (report.firstIndex + report.results.size > actions.size) return
                    runLog = runLog.withRun(report, nameOf(report.routineId), now())
                    history.withReport(report, now())
                }
                // A trigger came but the routine did not run: the card says why.
                else -> {
                    val (id, skip) = Skip.of(decision, now()) ?: return
                    // A flapping trigger repeats the same skip; the card need not be rebuilt for each.
                    val last = history.skip(id)
                    if (last != null && last.kind == skip.kind && skip.atEpochMs - last.atEpochMs < SKIP_REPUBLISH_MS) return
                    runLog = runLog.withSkip(id, nameOf(id), skip)
                    history.withSkip(id, skip)
                }
            }
            publishLocked()
        }
    }

    override fun onResult(update: ResultUpdate) {
        synchronized(lock) {
            // The run moved on, so "still waiting" no longer holds.
            history = history.withUpdate(update).withoutSkip(update.routineId)
            runLog = runLog.withUpdate(update)
            publishLocked()
        }
    }

    /** Removes the suggestion from the screen and returns it, exactly once even under double taps. */
    private fun takeSuggestion(): SuggestionItem? = synchronized(lock) {
        hidden = null
        val current = AppState.ui.value.suggestion ?: return null
        AppState.update { it.copy(suggestion = null) }
        current
    }

    private fun changeRoutines(change: (List<Routine>) -> List<Routine>) = synchronized(lock) {
        routines = distinctIds(change(routines))
        saveRoutines(routines)
        post(EngineEvent.RoutinesChanged(validatedLocked()))
        publishLocked()
    }

    private fun validatedLocked(): List<ValidatedRoutine> = routines.mapNotNull { routine ->
        when (val outcome = validator.validated(routine)) {
            is ValidationOutcome.Valid -> outcome.routine
            is ValidationOutcome.Invalid -> null.also { log("routine ${routine.id} rejected: ${outcome.issues}") }
        }
    }

    private fun publishLocked() {
        val items = routines.map { RoutineItem(it, history[it.id], history.skip(it.id)) }
        AppState.update { it.copy(routines = items, log = runLog.entries) }
    }

    /** Ids key the list, the run history and the engine; a duplicate (e.g. a hand-edited file) keeps the first. */
    private fun distinctIds(list: List<Routine>): List<Routine> {
        val unique = list.distinctBy { it.id }
        if (unique.size != list.size) log("duplicate routine ids dropped: ${list.groupBy { it.id }.filterValues { it.size > 1 }.keys}")
        return unique
    }

    private companion object {
        /** One showing per suggestion per half day; the habit log's own quiet periods do the rest. */
        const val SHOWN_DEDUP_MS = 12 * 60 * 60_000L
        const val SKIP_REPUBLISH_MS = 1_000L
    }
}
