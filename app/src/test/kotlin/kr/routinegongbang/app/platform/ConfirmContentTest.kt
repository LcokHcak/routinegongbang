package kr.routinegongbang.app.platform

import kr.routinegongbang.app.ui.model.ActionSummary
import kr.routinegongbang.core.engine.ConfirmRequest
import kr.routinegongbang.core.engine.Executor
import kr.routinegongbang.core.model.Action
import kr.routinegongbang.core.model.SignalIds
import kr.routinegongbang.core.model.SignalValue
import kotlin.test.Test
import kotlin.test.assertEquals

/** The question names the value, not only the control, and gives a sense of the 10 s limit. */
class ConfirmContentTest {

    private val heat = Action.Write(SignalIds.STEERING_WHEEL_HEAT, SignalValue.Num(2.0))
    private val describe: (ActionSummary) -> String = { summary ->
        when (summary) {
            is ActionSummary.Set -> "${summary.signal.value}=${(summary.value as SignalValue.Num).value.toInt()}"
            else -> summary.toString()
        }
    }

    @Test
    fun `the question says what will be set and to what`() {
        val request = ConfirmRequest("r1", "추운 아침", voiceOnly = false, actions = listOf(heat))
        val content = ConfirmContent.of(request, nowMs = 0, describe = describe)
        assertEquals("추운 아침", content.routineName)
        assertEquals(listOf("${SignalIds.STEERING_WHEEL_HEAT.value}=2"), content.lines)
    }

    @Test
    fun `waits are not asked about`() {
        val request = ConfirmRequest("r1", "추운 아침", voiceOnly = false, actions = listOf(heat, Action.Wait(600), heat))
        assertEquals(2, ConfirmContent.of(request, nowMs = 0, describe = describe).lines.size)
    }

    @Test
    fun `the countdown ends when the run gives up waiting`() {
        val request = ConfirmRequest("r1", "추운 아침", voiceOnly = false, actions = listOf(heat))
        assertEquals(1_000 + Executor.CONFIRM_TIMEOUT_MS, ConfirmContent.of(request, nowMs = 1_000, describe = describe).deadlineMs)
    }

    /** The executor's budget covers every question of one run, so a second question gets what is left. */
    @Test
    fun `a second question in the same run counts down to the same deadline`() {
        val clock = ConfirmClock()
        assertEquals(1_000 + Executor.CONFIRM_TIMEOUT_MS, clock.deadline("r1", nowMs = 1_000))
        assertEquals(1_000 + Executor.CONFIRM_TIMEOUT_MS, clock.deadline("r1", nowMs = 8_000))
    }

    @Test
    fun `a new run or another routine gets the full time`() {
        val clock = ConfirmClock()
        clock.deadline("r1", nowMs = 1_000)
        assertEquals(5_000 + Executor.CONFIRM_TIMEOUT_MS, clock.deadline("r2", nowMs = 5_000))
        assertEquals(20_000 + Executor.CONFIRM_TIMEOUT_MS, clock.deadline("r1", nowMs = 20_000))
    }
}
