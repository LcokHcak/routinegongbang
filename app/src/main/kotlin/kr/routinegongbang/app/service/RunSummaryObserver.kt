package kr.routinegongbang.app.service

import kr.routinegongbang.app.ui.model.RunSummary
import kr.routinegongbang.core.engine.Decision
import kr.routinegongbang.core.engine.EngineObserver
import kr.routinegongbang.core.engine.ResultUpdate
import kr.routinegongbang.core.model.Routine

/**
 * Tells the driver what a run really sent to the car ([RunSummary]; the card shows when the car confirms it), instead
 * of a sentence a template fixed in advance. Called on the engine coroutine, so [announce]
 * must only hand the text over, never block.
 */
internal class RunSummaryObserver(
    private val routineOf: (String) -> Routine?,
    private val describe: (RunSummary) -> String,
    private val announce: (String) -> Unit,
) : EngineObserver {

    override fun onDecision(decision: Decision) {
        val report = (decision as? Decision.Ran)?.report ?: return
        val routine = routineOf(report.routineId) ?: return
        RunSummary.of(routine, report)?.let { announce(describe(it)) }
    }

    override fun onResult(update: ResultUpdate) {
        val routine = routineOf(update.routineId) ?: return
        RunSummary.ofUpdate(routine, update)?.let { announce(describe(it)) }
    }
}
