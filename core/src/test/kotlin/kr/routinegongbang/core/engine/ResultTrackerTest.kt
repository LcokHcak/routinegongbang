package kr.routinegongbang.core.engine

import kr.routinegongbang.core.model.AreaKeys
import kr.routinegongbang.core.model.SignalIds
import kr.routinegongbang.core.model.SignalValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResultTrackerTest {

    private val heat = SignalIds.STEERING_WHEEL_HEAT

    @Test
    fun `sent write becomes confirmed when the app echo arrives`() {
        val tracker = ResultTracker().track("r1", actionIndex = 0, signal = heat, area = AreaKeys.GLOBAL)
        val (updates, next) = tracker.onAppEcho(heat, AreaKeys.GLOBAL)
        assertEquals(listOf(ResultUpdate("r1", 0, ActionResult.Confirmed(heat))), updates)
        assertTrue(next.awaiting.isEmpty())
    }

    @Test
    fun `sent write becomes unconfirmed when the ledger entry expires`() {
        val tracker = ResultTracker().track("r1", actionIndex = 2, signal = heat, area = AreaKeys.GLOBAL)
        val expired = listOf(PendingWrite(heat, AreaKeys.GLOBAL, SignalValue.of(1), deadlineMs = 0))
        val (updates, next) = tracker.onExpired(expired)
        assertEquals(listOf(ResultUpdate("r1", 2, ActionResult.SentUnconfirmed(heat))), updates)
        assertTrue(next.awaiting.isEmpty())
    }

    @Test
    fun `echo for an untracked signal changes nothing`() {
        val tracker = ResultTracker().track("r1", actionIndex = 0, signal = heat, area = AreaKeys.GLOBAL)
        val (updates, next) = tracker.onAppEcho(SignalIds.DEFROSTER_FRONT, AreaKeys.FRONT_WINDSHIELD)
        assertTrue(updates.isEmpty())
        assertEquals(1, next.awaiting.size)
    }

    @Test
    fun `tracking all sent results of a report`() {
        val report = ExecutionReport(
            routineId = "r1",
            results = listOf(ActionResult.Sent(heat), ActionResult.NoChange, ActionResult.Sent(SignalIds.DEFROSTER_FRONT)),
            state = ExecutorState(WriteLedger(testCatalog), WriteRateLimiter(10, 60_000)),
            dryRun = false,
        )
        val tracker = ResultTracker().trackReport(report, testCatalog)
        assertEquals(listOf(0, 2), tracker.awaiting.map { it.actionIndex })
    }

    @Test
    fun `user change while pending settles the result as unconfirmed`() {
        val tracker = ResultTracker().track("r1", actionIndex = 0, signal = heat, area = AreaKeys.GLOBAL)
        val (updates, next) = tracker.onUserChange(heat, AreaKeys.GLOBAL)
        assertEquals(listOf(ResultUpdate("r1", 0, ActionResult.SentUnconfirmed(heat))), updates)
        assertTrue(next.awaiting.isEmpty())
    }

    @Test
    fun `echo confirms only the newest write to the same signal, older ones are unconfirmed`() {
        val tracker = ResultTracker()
            .track("r1", actionIndex = 0, signal = heat, area = AreaKeys.GLOBAL)
            .track("r2", actionIndex = 1, signal = heat, area = AreaKeys.GLOBAL)
        val (updates, next) = tracker.onAppEcho(heat, AreaKeys.GLOBAL)
        assertEquals(
            listOf(ResultUpdate("r1", 0, ActionResult.SentUnconfirmed(heat)), ResultUpdate("r2", 1, ActionResult.Confirmed(heat))),
            updates,
        )
        assertTrue(next.awaiting.isEmpty())
    }

    @Test
    fun `updates name the run they belong to`() {
        val tracker = ResultTracker()
            .track("r1", actionIndex = 0, signal = heat, area = AreaKeys.GLOBAL, runId = 1)
            .track("r1", actionIndex = 0, signal = heat, area = AreaKeys.GLOBAL, runId = 2)
        val (updates, _) = tracker.onAppEcho(heat, AreaKeys.GLOBAL)
        assertEquals(
            listOf(ResultUpdate("r1", 0, ActionResult.SentUnconfirmed(heat), runId = 1), ResultUpdate("r1", 0, ActionResult.Confirmed(heat), runId = 2)),
            updates,
        )
    }
}
