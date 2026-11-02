package kr.routinegongbang.app.ui.model

import kr.routinegongbang.core.engine.ActionResult
import kr.routinegongbang.core.engine.ExecutionReport
import kr.routinegongbang.core.engine.ExecutorState
import kr.routinegongbang.core.engine.Reason
import kr.routinegongbang.core.engine.WriteLedger
import kr.routinegongbang.core.engine.WriteRateLimiter
import kr.routinegongbang.core.model.Action
import kr.routinegongbang.core.model.Policy
import kr.routinegongbang.core.model.Routine
import kr.routinegongbang.core.model.SignalCatalog
import kr.routinegongbang.core.model.SignalIds
import kr.routinegongbang.core.model.SignalValue
import kr.routinegongbang.core.model.Trigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Trust the driver gives, not only what the SDK allows: a routine starts by asking first, and after the driver says yes
 * three times in a row it does the job by itself. A no, or no answer, starts the count again.
 */
class TrustTest {

    private val state = ExecutorState(WriteLedger(SignalCatalog.default()), WriteRateLimiter.default())
    private val heat = Action.Write(SignalIds.STEERING_WHEEL_HEAT, SignalValue.of(1))
    private val asking = Routine(id = "r", name = "추운 아침", trigger = Trigger.RideStart, actions = listOf(heat), policy = Policy(askBeforeRun = true))
    private fun report(vararg results: ActionResult, dryRun: Boolean = false) = ExecutionReport("r", results.toList(), state, dryRun, false)

    @Test
    fun `a run the driver allowed is a yes`() {
        assertEquals(Trust.Answer.YES, Trust.answer(asking, report(ActionResult.Sent(SignalIds.STEERING_WHEEL_HEAT))))
        assertEquals(Trust.Answer.YES, Trust.answer(asking, report(ActionResult.Requested)))
    }

    @Test
    fun `a refusal or no answer is a no`() {
        assertEquals(Trust.Answer.NO, Trust.answer(asking, report(ActionResult.Cancelled(Reason.CONFIRM_DECLINED))))
        assertEquals(Trust.Answer.NO, Trust.answer(asking, report(ActionResult.Cancelled(Reason.CONFIRM_TIMEOUT))))
    }

    @Test
    fun `a yes for a setting that was already so still counts, one the car could not carry out does not`() {
        // Asking first, "already set" is only reached after the driver said yes.
        assertEquals(Trust.Answer.YES, Trust.answer(asking, report(ActionResult.NoChange)))
        // A failure may also be a notice that could not be shown, asked nothing: neither a yes nor a no.
        assertNull(Trust.answer(asking, report(ActionResult.Failed(Reason.ACTION_FAILED))))
    }

    @Test
    fun `one refusal among allowed actions is a no`() {
        assertEquals(Trust.Answer.NO, Trust.answer(asking, report(ActionResult.Sent(SignalIds.STEERING_WHEEL_HEAT), ActionResult.Cancelled(Reason.CONFIRM_TIMEOUT))))
    }

    @Test
    fun `a routine that waits never runs by itself on trust`() {
        // Switching its asking off would change the routine under its waiting run, and the engine would cancel the rest.
        val waiting = asking.copy(actions = listOf(heat, Action.Wait(600), heat))
        assertFalse(Trust.canEarn(waiting))
        assertNull(Trust.answer(waiting, report(ActionResult.Sent(SignalIds.STEERING_WHEEL_HEAT))))
    }

    @Test
    fun `nothing asked, nothing counted`() {
        // Only a notification (control not open), a test run, or a routine that already runs by itself.
        assertNull(Trust.answer(asking, report(ActionResult.Notified(Reason.SDK_NOT_OPEN))))
        assertNull(Trust.answer(asking, report(ActionResult.Sent(SignalIds.STEERING_WHEEL_HEAT), dryRun = true)))
        assertNull(Trust.answer(asking.copy(policy = Policy()), report(ActionResult.Sent(SignalIds.STEERING_WHEEL_HEAT))))
    }

    @Test
    fun `three yeses in a row earn doing it by itself`() {
        var ledger = TrustLedger()
        repeat(2) {
            val (next, earned) = ledger.afterYes("r")
            assertFalse(earned)
            ledger = next
        }
        assertEquals(2, ledger.count("r"))
        val (third, earned) = ledger.afterYes("r")
        assertTrue(earned)
        assertEquals(0, third.count("r"), "the count is spent once the routine runs by itself")
    }

    @Test
    fun `a no starts the count again, and a forgotten routine has none`() {
        val ledger = TrustLedger().afterYes("r").first.afterYes("r").first
        assertEquals(0, ledger.afterNo("r").count("r"))
        assertEquals(0, ledger.forget("r").count("r"))
        assertEquals(2, ledger.count("r"))
    }

    @Test
    fun `the ledger survives a restart as JSON`() {
        val ledger = TrustLedger().afterYes("r").first.afterYes("x").first
        assertEquals(ledger, TrustLedger.decode(ledger.encode()))
        assertEquals(TrustLedger(), TrustLedger.decode("not json"))
        assertEquals(TrustLedger(), TrustLedger.decode("[1, 2]"))
        assertNull(TrustLedger.decodeOrNull("""{"r": [1]}"""))
        // A count out of range is no count at all.
        assertEquals(TrustLedger().afterYes("x").first, TrustLedger.decode("""{"r": -1, "x": 1, "y": 7}"""))
    }
}
