package kr.routinegongbang.app.ui.model

import kr.routinegongbang.core.engine.ActionResult
import kr.routinegongbang.core.engine.ExecutionReport
import kr.routinegongbang.core.engine.ExecutorState
import kr.routinegongbang.core.engine.Reason
import kr.routinegongbang.core.engine.WriteLedger
import kr.routinegongbang.core.engine.WriteRateLimiter
import kr.routinegongbang.core.model.Action
import kr.routinegongbang.core.model.Routine
import kr.routinegongbang.core.model.RoutineJson
import kr.routinegongbang.core.model.SignalCatalog
import kr.routinegongbang.core.model.SignalIds
import kr.routinegongbang.core.model.SignalValue
import kr.routinegongbang.core.model.Trigger
import java.io.File
import kr.routinegongbang.app.ui.editor.EditorCatalog
import kr.routinegongbang.app.ui.editor.Template
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rain template must never say "켰어요" when nothing was turned on. What a run tells the driver
 * comes from its results: only what the app really sent to the car, never a sentence fixed in advance.
 */
class RunSummaryTest {

    private val on = SignalValue.Bool(true)
    private val rain = Routine(
        id = "rain", name = "비 오는 날", trigger = Trigger.NaviArrived,
        actions = listOf(Action.Write(SignalIds.DEFROSTER_FRONT, on), Action.Write(SignalIds.RECIRCULATION, on)),
    )
    private val state = ExecutorState(WriteLedger(SignalCatalog.default()), WriteRateLimiter.default())

    private fun report(vararg results: ActionResult, dryRun: Boolean = false) = ExecutionReport("rain", results.toList(), state, dryRun)

    @Test
    fun `only the settings the app really sent are reported as done`() {
        val summary = RunSummary.of(rain, report(ActionResult.Sent(SignalIds.DEFROSTER_FRONT), ActionResult.Skipped(Reason.USER_OVERRIDE)))
        assertEquals(RunSummary("비 오는 날", listOf(ActionSummary.Set(SignalIds.DEFROSTER_FRONT, on))), summary)
    }

    @Test
    fun `a run that changed nothing and told nothing says nothing, its reasons are on the card`() {
        // Downgraded actions are told by the summary (see below); skips and settings already in place are not.
        assertNull(RunSummary.of(rain, report(ActionResult.Skipped(Reason.USER_OVERRIDE), ActionResult.NoChange)))
    }

    @Test
    fun `a run of a routine edited meanwhile says nothing rather than something wrong`() {
        // The screen may already hold the edited routine when the older run reports.
        val edited = rain.copy(actions = listOf(Action.Write(SignalIds.SEAT_HEAT_DRIVER, SignalValue.of(2)), Action.Write(SignalIds.RECIRCULATION, on)))
        assertNull(RunSummary.of(edited, report(ActionResult.Sent(SignalIds.DEFROSTER_FRONT), ActionResult.Sent(SignalIds.RECIRCULATION))))
        assertNull(RunSummary.of(rain.copy(actions = rain.actions.take(1)), report(ActionResult.Sent(SignalIds.DEFROSTER_FRONT), ActionResult.NoChange)))
    }

    @Test
    fun `a dry run is shown on screen, not announced`() {
        assertNull(RunSummary.of(rain, report(ActionResult.Sent(SignalIds.DEFROSTER_FRONT), ActionResult.Sent(SignalIds.RECIRCULATION), dryRun = true)))
    }

    @Test
    fun `no template announces a result it cannot know in advance`() {
        // A fixed notification runs whatever the writes before it did.
        val strings = File("src/main/res/values/strings.xml").readText()
        // Routines start from the editor's templates (a fresh install has none of its own).
        val templates = Template.entries.map { EditorCatalog.template(it, it.name, it.name) }
        templates.filter { r -> r.actions.any { it is Action.Write } }.forEach { routine ->
            routine.actions.filterIsInstance<Action.Notify>().forEach { notify ->
                val text = Regex("""name="${notify.textRes.replace('.', '_')}">([^<]*)<""").find(strings)?.groupValues?.get(1).orEmpty()
                assertTrue(listOf("켰어요", "껐어요", "했어요").none { it in text }, "${routine.id}: \"$text\" claims an outcome")
            }
        }
    }

    @Test
    fun `a fresh install starts with no routines of its own`() {
        // The driver builds their own from the templates; nothing runs that they did not choose.
        val seed = RoutineJson.decodeList(File("src/main/assets/routines_seed.json").readText()).getOrThrow()
        assertTrue(seed.isEmpty(), "seed has ${seed.size} routines")
    }

    // --- One notice per run, with the routine's name, instead of one per action ---

    @Test
    fun `settings that could only be told are listed once, with the routine`() {
        val summary = RunSummary.of(rain, report(ActionResult.Notified(Reason.SDK_NOT_OPEN), ActionResult.Notified(Reason.SDK_NOT_OPEN)))
        assertEquals("비 오는 날", summary?.routineName)
        assertEquals(emptyList(), summary?.done)
        assertEquals(2, summary?.told?.size)
    }

    @Test
    fun `a run that sent some and told others says both`() {
        val summary = RunSummary.of(rain, report(ActionResult.Sent(SignalIds.DEFROSTER_FRONT), ActionResult.Notified(Reason.SDK_NOT_OPEN)))
        assertEquals(1, summary?.done?.size)
        assertEquals(1, summary?.told?.size)
    }

    @Test
    fun `per-action downgrade notices are left to the run summary`() {
        assertEquals(false, kr.routinegongbang.app.platform.NotifyRouting.showsOnItsOwn(kr.routinegongbang.core.engine.NotifyMessage(kr.routinegongbang.core.engine.NotifyMessage.DOWNGRADED, Reason.SDK_NOT_OPEN)))
        assertEquals(true, kr.routinegongbang.app.platform.NotifyRouting.showsOnItsOwn(kr.routinegongbang.core.engine.NotifyMessage("notify.window_open")))
    }

    @Test
    fun `a dry run still tells what it could only tell, or the card would claim a notice nobody saw`() {
        val summary = RunSummary.of(rain, report(ActionResult.Sent(SignalIds.DEFROSTER_FRONT), ActionResult.Notified(Reason.SDK_NOT_OPEN), dryRun = true))
        assertEquals(emptyList(), summary?.done)
        assertEquals(1, summary?.told?.size)
    }

    // --- Actions after a wait report through result updates, never through a run report ---

    @Test
    fun `a downgrade after a wait is told on its own, with the routine`() {
        val update = kr.routinegongbang.core.engine.ResultUpdate("rain", 1, ActionResult.Notified(Reason.SDK_NOT_OPEN))
        val summary = RunSummary.ofUpdate(rain, update)
        assertEquals("비 오는 날", summary?.routineName)
        assertEquals(listOf<ActionSummary>(ActionSummary.Set(SignalIds.RECIRCULATION, on)), summary?.told)
    }

    @Test
    fun `a setting sent after a wait is reported, its confirmation is not reported again`() {
        val sent = kr.routinegongbang.core.engine.ResultUpdate("rain", 1, ActionResult.Sent(SignalIds.RECIRCULATION))
        assertEquals(listOf(ActionSummary.Set(SignalIds.RECIRCULATION, on)), RunSummary.ofUpdate(rain, sent)?.done)
        val confirmed = kr.routinegongbang.core.engine.ResultUpdate("rain", 1, ActionResult.Confirmed(SignalIds.RECIRCULATION))
        assertNull(RunSummary.ofUpdate(rain, confirmed))
    }

    @Test
    fun `an update for an action the routine no longer has says nothing`() {
        assertNull(RunSummary.ofUpdate(rain, kr.routinegongbang.core.engine.ResultUpdate("rain", 5, ActionResult.Notified(Reason.SDK_NOT_OPEN))))
    }

    @Test
    fun `a reminder kept for parking is shown, only run downgrades wait for the summary`() {
        // A body action skipped while driving is reminded once parked, with the downgrade text.
        val reminder = kr.routinegongbang.core.engine.NotifyMessage(kr.routinegongbang.core.engine.NotifyMessage.DOWNGRADED, Reason.BODY_WHILE_DRIVING)
        assertEquals(true, kr.routinegongbang.app.platform.NotifyRouting.showsOnItsOwn(reminder))
    }
}
