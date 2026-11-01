package kr.routinegongbang.app.ui.model

import kr.routinegongbang.core.engine.ActionResult
import kr.routinegongbang.core.engine.ExecutionReport
import kr.routinegongbang.core.engine.ResultUpdate
import kr.routinegongbang.core.model.Action
import kr.routinegongbang.core.model.Routine

/**
 * What a finished run tells the driver, built from its results: only the settings the app
 * really sent to the car, and in the same notice what could only be told (downgraded actions).
 * Skips are shown on the card, so a run that sent and told nothing says nothing; a dry run only tells.
 */
data class RunSummary(val routineName: String, val done: List<ActionSummary.Set>, val told: List<ActionSummary> = emptyList()) {
    companion object {
        fun of(routine: Routine, report: ExecutionReport): RunSummary? {
            // The screen may already hold an edited routine when an older run reports: if its actions do
            // not line up with the results, say nothing rather than name something that was never sent.
            if (report.firstIndex + report.results.size != routine.actions.size) return null
            val pairs = report.results.zip(routine.actions.drop(report.firstIndex))
            if (pairs.any { (result, action) -> result is ActionResult.Sent && (action as? Action.Write)?.signal != result.signal }) return null
            val done = pairs
                .filter { (result, action) -> action is Action.Write && result.sent() }
                .map { (_, action) -> (action as Action.Write).let { ActionSummary.Set(it.signal, it.value) } }
            // Downgraded actions are told here, once for the run and with its name, instead of one notice per action
            // (several "직접 해 주세요" while driving would not say which routine).
            val told = pairs.filter { (result, _) -> result is ActionResult.Notified && result.reason != null }.map { (_, action) -> ActionSummary.of(action) }
            // A dry run is shown on screen, but what it could only tell must still be told: the card says it was.
            val shownDone = if (report.dryRun) emptyList() else done
            return if (shownDone.isEmpty() && told.isEmpty()) null else RunSummary(routine.name, shownDone, told)
        }

        /**
         * The actions after a wait report one by one as result updates, never in a run report: a
         * setting sent then, or an action that could only be told, is announced as it comes, with the routine.
         * Later updates of the same action (its confirmation) say nothing new.
         */
        fun ofUpdate(routine: Routine, update: ResultUpdate): RunSummary? {
            val action = routine.actions.getOrNull(update.actionIndex) ?: return null
            val result = update.result
            return when {
                result is ActionResult.Notified && result.reason != null -> RunSummary(routine.name, emptyList(), listOf(ActionSummary.of(action)))
                result is ActionResult.Sent && action is Action.Write && action.signal == result.signal ->
                    RunSummary(routine.name, listOf(ActionSummary.Set(action.signal, action.value)))
                else -> null
            }
        }

        private fun ActionResult.sent() = this is ActionResult.Sent || this is ActionResult.Confirmed || this is ActionResult.SentUnconfirmed
    }
}
