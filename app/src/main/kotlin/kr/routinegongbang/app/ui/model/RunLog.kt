package kr.routinegongbang.app.ui.model

import kr.routinegongbang.core.engine.ActionResult
import kr.routinegongbang.core.engine.ExecutionReport
import kr.routinegongbang.core.engine.ResultUpdate

/** One line of the recent log: a run with how its actions went, or a skip with why. */
data class RunLogEntry(
    val atEpochMs: Long,
    val routineId: String,
    val routineName: String,
    val runId: Long = 0,
    val results: List<ActionResult> = emptyList(),
    val dryRun: Boolean = false,
    val skip: Skip.Kind? = null,
) {
    val outcomes: List<Outcome> get() = results.map { StatusView.of(it).outcome }
}

/**
 * The recent runs and skips, newest first: the demo's "told, then done" story and the side
 * panel need the order of events, not only each routine's latest run. Immutable and short.
 */
class RunLog(val entries: List<RunLogEntry> = emptyList(), private val max: Int = MAX_ENTRIES) {

    fun withRun(report: ExecutionReport, routineName: String, atEpochMs: Long): RunLog =
        add(RunLogEntry(atEpochMs, report.routineId, routineName, report.runId, report.results, report.dryRun))

    fun withSkip(routineId: String, routineName: String, skip: Skip): RunLog =
        add(RunLogEntry(skip.atEpochMs, routineId, routineName, skip = skip.kind))

    /** A confirmation arriving later updates the run it belongs to, like the card does (RunHistory). */
    fun withUpdate(update: ResultUpdate): RunLog {
        val index = entries.indexOfFirst { it.routineId == update.routineId && it.runId == update.runId && it.skip == null }
        if (index < 0) return this
        val entry = entries[index]
        if (update.actionIndex !in entry.results.indices) return this
        val results = entry.results.toMutableList().also { it[update.actionIndex] = update.result }
        return RunLog(entries.toMutableList().also { it[index] = entry.copy(results = results) }, max)
    }

    /** Parked again: the notices held during the drive went out in the digest. */
    fun withHeldShown(): RunLog =
        if (entries.none { it.results.anyHeld() }) this else RunLog(entries.map { it.copy(results = it.results.heldShown()) }, max)

    private fun add(entry: RunLogEntry) = RunLog((listOf(entry) + entries).take(max), max)

    companion object {
        const val MAX_ENTRIES = 30
    }
}
