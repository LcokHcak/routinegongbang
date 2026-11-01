package kr.routinegongbang.core.engine

import kr.routinegongbang.core.model.AreaKey
import kr.routinegongbang.core.model.AreaKeys
import kr.routinegongbang.core.model.SignalCatalog
import kr.routinegongbang.core.model.SignalId

data class AwaitingResult(val routineId: String, val actionIndex: Int, val signal: SignalId, val area: AreaKey, val runId: Long = 0)

/** Final state for one action of a past run, pushed to the log and UI. */
data class ResultUpdate(val routineId: String, val actionIndex: Int, val result: ActionResult, val runId: Long = 0)

/**
 * Turns [ActionResult.Sent] into CONFIRMED or SENT_UNCONFIRMED, driven by the
 * WriteLedger: an APP-classified echo confirms, an expired pending write leaves it unconfirmed. Immutable.
 */
class ResultTracker(val awaiting: List<AwaitingResult> = emptyList()) {

    fun track(routineId: String, actionIndex: Int, signal: SignalId, area: AreaKey, runId: Long = 0): ResultTracker =
        ResultTracker(awaiting + AwaitingResult(routineId, actionIndex, signal, area, runId))

    fun trackReport(report: ExecutionReport, catalog: SignalCatalog): ResultTracker =
        report.results.foldIndexed(this) { index, tracker, result ->
            if (result is ActionResult.Sent) {
                val area = result.area ?: catalog[result.signal]?.area ?: AreaKeys.GLOBAL
                tracker.track(report.routineId, report.firstIndex + index, result.signal, area, report.runId)
            } else {
                tracker
            }
        }

    /**
     * The ledger matched an APP echo that settles the oldest [settled] writes to that signal and area: the last of
     * them produced it and is confirmed, the older ones were overtaken and stay unconfirmed; newer writes keep
     * waiting for their own echo. Without a count, every waiting write is settled.
     */
    fun onAppEcho(signal: SignalId, area: AreaKey, settled: Int = Int.MAX_VALUE): Pair<List<ResultUpdate>, ResultTracker> {
        val matching = awaiting.filter { it.signal == signal && it.area == area }.take(settled)
        val updates = matching.mapIndexed { index, waiting ->
            val result = if (index == matching.lastIndex) ActionResult.Confirmed(signal) else ActionResult.SentUnconfirmed(signal)
            ResultUpdate(waiting.routineId, waiting.actionIndex, result, waiting.runId)
        }
        return updates to ResultTracker(awaiting - matching.toSet())
    }

    /** The ledger classified a change as USER while a write was pending (late or overridden). */
    fun onUserChange(signal: SignalId, area: AreaKey): Pair<List<ResultUpdate>, ResultTracker> =
        unconfirmed { it.signal == signal && it.area == area }

    /** One expired write settles one waiting result of its signal and area, the oldest; newer ones keep waiting. */
    fun onExpired(expired: List<PendingWrite>): Pair<List<ResultUpdate>, ResultTracker> {
        val done = expired.groupBy { it.signal to it.area }.flatMap { (key, entries) ->
            awaiting.filter { it.signal == key.first && it.area == key.second }.take(entries.size)
        }.toSet()
        return done.map { ResultUpdate(it.routineId, it.actionIndex, ActionResult.SentUnconfirmed(it.signal), it.runId) } to ResultTracker(awaiting - done)
    }

    private fun unconfirmed(matches: (AwaitingResult) -> Boolean): Pair<List<ResultUpdate>, ResultTracker> {
        val (done, rest) = awaiting.partition(matches)
        return done.map { ResultUpdate(it.routineId, it.actionIndex, ActionResult.SentUnconfirmed(it.signal), it.runId) } to ResultTracker(rest)
    }
}
