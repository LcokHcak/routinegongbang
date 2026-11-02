package kr.routinegongbang.app.ui.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kr.routinegongbang.core.engine.ActionResult
import kr.routinegongbang.core.engine.ExecutionReport
import kr.routinegongbang.core.engine.Reason
import kr.routinegongbang.core.model.Action
import kr.routinegongbang.core.model.Routine

/**
 * Trust the driver gives, on top of what the SDK allows: a routine that asks first and hears yes [EARNED_AFTER] times in
 * a row does the job by itself from then on. Only the answers to the routine's own question count.
 */
object Trust {
    const val EARNED_AFTER = 3

    enum class Answer { YES, NO }

    /**
     * A routine that waits keeps asking: switching its asking off would change it under its waiting run, and the engine
     * cancels the rest of a run whose routine changed.
     */
    fun canEarn(routine: Routine): Boolean = routine.actions.none { it is Action.Wait }

    /** What the driver answered in this run, or null when nothing was asked (a test run, a notice, a routine that no longer asks). */
    fun answer(routine: Routine, report: ExecutionReport): Answer? {
        if (report.dryRun || !routine.policy.askBeforeRun || !canEarn(routine)) return null
        return when {
            report.results.any(::refused) -> Answer.NO
            report.results.any(::allowed) -> Answer.YES
            else -> null
        }
    }

    /** A no, no answer in time, or a question that could not be shown: none of them is trust. */
    fun refused(result: ActionResult): Boolean = result is ActionResult.Cancelled && result.reason in REFUSALS

    /**
     * With asking first on, a setting is only sent (or found already set), and a route only requested, after the driver
     * said yes. A failure is not here: a notice that could not be shown fails too, and it asked nothing.
     */
    private fun allowed(result: ActionResult): Boolean = result is ActionResult.Sent || result is ActionResult.Confirmed ||
        result is ActionResult.SentUnconfirmed || result is ActionResult.Requested || result is ActionResult.NoChange

    private val REFUSALS = setOf(Reason.CONFIRM_DECLINED, Reason.CONFIRM_TIMEOUT, Reason.CONFIRM_FAILED)
}

/** The yeses in a row per routine id; immutable, persisted as a small JSON object. */
data class TrustLedger(private val counts: Map<String, Int> = emptyMap()) {

    fun count(routineId: String): Int = counts[routineId] ?: 0

    /** The next ledger, and whether this yes earned running by itself (the count is then spent). */
    fun afterYes(routineId: String): Pair<TrustLedger, Boolean> {
        val next = count(routineId) + 1
        return if (next >= Trust.EARNED_AFTER) forget(routineId) to true else TrustLedger(counts + (routineId to next)) to false
    }

    fun afterNo(routineId: String): TrustLedger = forget(routineId)

    /** An edited, switched or deleted routine is not the one the driver said yes to. */
    fun forget(routineId: String): TrustLedger = if (routineId in counts) TrustLedger(counts - routineId) else this

    fun encode(): String = JsonObject(counts.mapValues { JsonPrimitive(it.value) }).toString()

    companion object {
        /** An unreadable file starts the counts afresh: asking a few more times is the safe side. */
        fun decode(text: String): TrustLedger = decodeOrNull(text) ?: TrustLedger()

        fun decodeOrNull(text: String): TrustLedger? = try {
            val counts = Json.parseToJsonElement(text).jsonObject.mapValues { it.value.jsonPrimitive.int }
            TrustLedger(counts.filterValues { it in 1 until Trust.EARNED_AFTER })
        } catch (e: IllegalArgumentException) {
            // SerializationException and NumberFormatException are both IllegalArgumentExceptions.
            null
        }
    }
}
