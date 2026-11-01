package kr.routinegongbang.app.platform

import kr.routinegongbang.core.engine.NotifyMessage
import kr.routinegongbang.core.engine.Reason

/**
 * Which engine notifications are shown as they come. A downgrade ("자동으로 못 했어요") is not: the run summary lists
 * every downgraded action of a run in one notice with the routine's name (RunSummary).
 */
internal object NotifyRouting {
    fun showsOnItsOwn(message: NotifyMessage): Boolean =
        // A body action skipped while driving comes back as a reminder once parked, with the downgrade text but no
        // run summary behind it: that one is shown.
        message.textRes != NotifyMessage.DOWNGRADED || message.reason == Reason.BODY_WHILE_DRIVING
}
