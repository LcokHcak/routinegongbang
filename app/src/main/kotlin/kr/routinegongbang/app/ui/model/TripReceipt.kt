package kr.routinegongbang.app.ui.model

import kr.routinegongbang.core.engine.Reason

/**
 * At P after a drive, what every routine did on this trip, done or not, and why: the recent log's runs and skips
 * between [startedAtMs] and [endedAtMs], oldest first. Test runs are not part of the trip.
 */
data class TripReceipt(val startedAtMs: Long, val endedAtMs: Long, val entries: List<RunLogEntry>) {

    /**
     * One routine of the receipt: the outcomes of all its runs on the trip, every skip with its reason, and why its
     * latest run went as it did when that run was not simply done.
     */
    data class Line(val routineId: String, val routineName: String, val outcomes: List<Outcome>, val skips: List<Skip.Kind>, val reason: Reason? = null)

    /** One line per routine, in the order each first came up; a trigger that kept coming is one line, not ten. */
    val lines: List<Line> = entries.groupBy { it.routineId }.map { (id, runs) ->
        // Why only when its latest run did not go well: a reason from an earlier run would read as the latest one's.
        val latest = runs.lastOrNull { it.skip == null }?.takeIf { run -> run.outcomes.any { it != Outcome.DONE } }
        val reason = latest?.results?.firstNotNullOfOrNull { StatusView.of(it).reason }
        Line(id, runs.last().routineName, runs.flatMap { it.outcomes }, runs.mapNotNull { it.skip }, reason)
    }

    companion object {
        /** [log] is newest first, as [RunLog] keeps it; null when nothing happened on the trip. */
        fun of(log: List<RunLogEntry>, startedAtMs: Long, endedAtMs: Long): TripReceipt? {
            val entries = log.filter { !it.dryRun && it.atEpochMs in startedAtMs..endedAtMs }.asReversed()
            return if (entries.isEmpty()) null else TripReceipt(startedAtMs, endedAtMs, entries.toList())
        }
    }
}
