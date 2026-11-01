package kr.routinegongbang.app.ui.model

import kr.routinegongbang.core.engine.ActionResult
import kr.routinegongbang.core.engine.ExecutionReport
import kr.routinegongbang.core.engine.ExecutorState
import kr.routinegongbang.core.engine.Reason
import kr.routinegongbang.core.engine.ResultUpdate
import kr.routinegongbang.core.engine.WriteLedger
import kr.routinegongbang.core.engine.WriteRateLimiter
import kr.routinegongbang.core.model.SignalCatalog
import kr.routinegongbang.core.model.SignalIds
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The demo's "before and after" (told, then done once control opens) needs more than the last run, and the side
 * panel needs something to show. A short log of recent runs and skips, newest first, shows the story in order.
 */
class RunLogTest {

    private val state = ExecutorState(WriteLedger(SignalCatalog.default()), WriteRateLimiter.default())
    private fun report(vararg results: ActionResult, runId: Long = 1) = ExecutionReport("w", results.toList(), state, false, false, runId)

    @Test
    fun `runs are listed newest first, each with its outcomes`() {
        val log = RunLog()
            .withRun(report(ActionResult.Notified(Reason.SDK_NOT_OPEN), runId = 1), "겨울 출근", atEpochMs = 1)
            .withRun(report(ActionResult.Confirmed(SignalIds.STEERING_WHEEL_HEAT), runId = 2), "겨울 출근", atEpochMs = 2)
        assertEquals(listOf(listOf(Outcome.DONE), listOf(Outcome.TOLD)), log.entries.map { it.outcomes })
    }

    @Test
    fun `once parked, held notices count as told`() {
        val log = RunLog()
            .withRun(report(ActionResult.Notified(null, held = true), ActionResult.NoChange), "출근", atEpochMs = 1)
            .withHeldShown()
        assertEquals(listOf(Outcome.TOLD, Outcome.DONE), log.entries.single().outcomes)
    }

    @Test
    fun `a confirmation updates the run it belongs to`() {
        val log = RunLog()
            .withRun(report(ActionResult.Sent(SignalIds.STEERING_WHEEL_HEAT), runId = 7), "겨울 출근", atEpochMs = 1)
            .withUpdate(ResultUpdate("w", 0, ActionResult.Confirmed(SignalIds.STEERING_WHEEL_HEAT), runId = 7))
        assertEquals(listOf(Outcome.DONE), log.entries.single().outcomes)
    }

    @Test
    fun `skips are listed too, with why`() {
        val log = RunLog().withSkip("w", "겨울 출근", Skip(Skip.Kind.CONDITION, 5))
        assertEquals(Skip.Kind.CONDITION, log.entries.single().skip)
    }

    @Test
    fun `the log stays short`() {
        var log = RunLog(max = 3)
        repeat(5) { log = log.withSkip("w", "r$it", Skip(Skip.Kind.CONDITION, it.toLong())) }
        assertEquals(listOf("r4", "r3", "r2"), log.entries.map { it.routineName })
    }
}
