package kr.routinegongbang.core.engine

import kr.routinegongbang.core.model.AreaKey
import kr.routinegongbang.core.model.SignalId

/** Per-action outcome. A finished routine does not mean every action succeeded. */
sealed interface ActionResult {
    /** Write call returned without error; [ResultTracker] later turns it into [Confirmed] or [SentUnconfirmed]. */
    data class Sent(val signal: SignalId, val area: AreaKey? = null) : ActionResult
    /** Target value was already set, so nothing was written. */
    data object NoChange : ActionResult
    /** [detail] is for the debug log only; never shown raw to the user. */
    data class Failed(val reason: Reason, val detail: String? = null) : ActionResult
    /**
     * A notification was shown; [reason] is set when it replaces a downgraded action. [held]: the app keeps it until the
     * car is parked, so it has not been seen yet.
     */
    data class Notified(val reason: Reason?, val held: Boolean = false) : ActionResult
    data class Cancelled(val reason: Reason) : ActionResult
    data class Skipped(val reason: Reason) : ActionResult
    /** Remind held until the car is parked; the engine queues it. */
    data object Deferred : ActionResult
    /** Destination handed to navigation. */
    data object Requested : ActionResult
    data class Confirmed(val signal: SignalId) : ActionResult
    data class SentUnconfirmed(val signal: SignalId) : ActionResult
    /** A wait not over yet, or an action after it; the engine reports the outcome on the same run later. */
    data object Scheduled : ActionResult
    /** The wait is over and the rest of the routine ran (each action reports its own result). */
    data object Waited : ActionResult
}
