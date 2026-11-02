package kr.routinegongbang.app.ui.model

import kr.routinegongbang.core.engine.ActionResult
import kr.routinegongbang.core.engine.Reason
import kr.routinegongbang.core.model.SignalIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** At P after a drive, one receipt says what every routine did on this trip, done or not, and why. */
class TripReceiptTest {

    private fun run(at: Long, name: String, vararg results: ActionResult, dryRun: Boolean = false) =
        RunLogEntry(at, name, name, runId = at, results = results.toList(), dryRun = dryRun)

    @Test
    fun `the receipt lists this trip's runs and skips, oldest first`() {
        val log = listOf( // newest first, as the run log keeps it
            RunLogEntry(300, "rain", "비 오는 날", skip = Skip.Kind.COOLDOWN),
            run(200, "추운 아침", ActionResult.Confirmed(SignalIds.STEERING_WHEEL_HEAT)),
            run(50, "어제", ActionResult.NoChange),
        )
        val receipt = TripReceipt.of(log, startedAtMs = 100, endedAtMs = 400)!!
        assertEquals(listOf("추운 아침", "비 오는 날"), receipt.entries.map { it.routineName })
        assertEquals(100, receipt.startedAtMs)
        assertEquals(400, receipt.endedAtMs)
    }

    @Test
    fun `each routine is one line, in the order it first came up, with what its runs did and how often it was skipped`() {
        val log = listOf(
            RunLogEntry(400, "rain", "비 오는 날", skip = Skip.Kind.COOLDOWN),
            run(300, "추운 아침", ActionResult.Notified(Reason.SDK_NOT_OPEN)),
            RunLogEntry(250, "rain", "비 오는 날", skip = Skip.Kind.COOLDOWN),
            run(200, "추운 아침", ActionResult.Confirmed(SignalIds.STEERING_WHEEL_HEAT)),
        )
        val lines = TripReceipt.of(log, startedAtMs = 100, endedAtMs = 500)!!.lines
        assertEquals(listOf("추운 아침", "비 오는 날"), lines.map { it.routineName })
        assertEquals(listOf(Outcome.DONE, Outcome.TOLD), lines[0].outcomes)
        assertEquals(listOf(Skip.Kind.COOLDOWN, Skip.Kind.COOLDOWN), lines[1].skips)
        // Why, from its latest run that has a reason: done or not, the receipt says why.
        assertEquals(Reason.SDK_NOT_OPEN, lines[0].reason)
        assertNull(lines[1].reason)
    }

    @Test
    fun `a routine whose latest run went well shows no reason from an earlier one`() {
        val log = listOf(
            run(300, "추운 아침", ActionResult.Confirmed(SignalIds.STEERING_WHEEL_HEAT)),
            run(200, "추운 아침", ActionResult.Cancelled(Reason.CONFIRM_DECLINED)),
        )
        assertNull(TripReceipt.of(log, startedAtMs = 100, endedAtMs = 500)!!.lines.single().reason)
    }

    @Test
    fun `test runs are not part of the trip`() {
        val log = listOf(run(200, "추운 아침", ActionResult.NoChange, dryRun = true))
        assertNull(TripReceipt.of(log, startedAtMs = 100, endedAtMs = 400))
    }

    @Test
    fun `a trip where nothing happened has no receipt`() {
        assertNull(TripReceipt.of(emptyList(), startedAtMs = 100, endedAtMs = 400))
    }
}
