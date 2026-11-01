package kr.routinegongbang.app.platform

import kr.routinegongbang.app.ui.model.ActionSummary
import kr.routinegongbang.core.engine.ConfirmRequest
import kr.routinegongbang.core.engine.Executor
import kr.routinegongbang.core.model.Action

/**
 * What the "실행할까요?" notification says: each action with its value ("핸들 열선 2단계"),
 * and when the run stops waiting, so the notification can count down to it.
 */
internal data class ConfirmContent(val routineName: String, val lines: List<String>, val deadlineMs: Long) {
    companion object {
        fun of(request: ConfirmRequest, nowMs: Long, describe: (ActionSummary) -> String, deadlineMs: Long = nowMs + Executor.CONFIRM_TIMEOUT_MS) = ConfirmContent(
            routineName = request.routineName,
            // A wait needs no answer of its own.
            lines = request.actions.filterNot { it is Action.Wait }.map { describe(ActionSummary.of(it)) },
            deadlineMs = deadlineMs,
        )
    }
}

/**
 * Mirrors the executor's budget (ConfirmBudget in core): one run waits at most CONFIRM_TIMEOUT_MS in total for all of its
 * questions, counted from the first. A further question of the same routine before that deadline belongs to the
 * same run and gets only what is left. If a new run of the same routine asks that soon, the countdown is
 * shorter than the run's: the question then goes away early and the run is cancelled, never the other way round.
 */
internal class ConfirmClock(private val totalMs: Long = Executor.CONFIRM_TIMEOUT_MS) {
    private var open: Pair<String, Long>? = null

    @Synchronized
    fun deadline(routineId: String, nowMs: Long): Long {
        val same = open?.takeIf { (id, deadline) -> id == routineId && nowMs < deadline }?.second
        return (same ?: (nowMs + totalMs)).also { open = routineId to it }
    }
}
