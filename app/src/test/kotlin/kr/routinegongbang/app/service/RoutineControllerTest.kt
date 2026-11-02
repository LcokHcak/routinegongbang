package kr.routinegongbang.app.service

import kr.routinegongbang.app.ui.AppState
import kr.routinegongbang.app.ui.UiState
import kr.routinegongbang.app.ui.model.Skip
import kr.routinegongbang.core.engine.Decision
import kr.routinegongbang.core.engine.DrivingState
import kr.routinegongbang.core.engine.ExecutionReport
import kr.routinegongbang.core.engine.EngineEvent
import kr.routinegongbang.core.engine.Gear
import kr.routinegongbang.core.habit.FeedbackKind
import kr.routinegongbang.core.habit.HabitBook
import kr.routinegongbang.core.habit.HabitDetector
import kr.routinegongbang.core.habit.HabitLog
import kr.routinegongbang.core.habit.HabitRecorder
import kr.routinegongbang.core.model.Routine
import kr.routinegongbang.core.model.SignalCatalog
import kr.routinegongbang.core.model.SignalIds
import kr.routinegongbang.core.model.SignalValue
import java.time.Instant
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoutineControllerTest {

    private val catalog = SignalCatalog.default()
    private val zone = ZoneId.of("Asia/Seoul")
    private val parked = DrivingState(Gear.PARK, 0.0, false)
    private val driving = DrivingState(Gear.DRIVE, 40.0, true)
    private var now = Instant.parse("2026-10-28T00:00:00Z").toEpochMilli() // 09:00 KST, Wednesday
    private val posted = mutableListOf<EngineEvent>()
    private val saved = mutableListOf<List<Routine>>()

    /** Three weekday mornings with the steering wheel heated by hand: a habit. */
    private fun habits(): HabitBook {
        val recorder = HabitRecorder(catalog, zone)
        val book = HabitBook(recorder, HabitDetector(catalog, zone), HabitLog())
        listOf("2026-10-26", "2026-10-27", "2026-10-28").forEachIndexed { i, day ->
            val start = Instant.parse("${day}T08:00:00+09:00")
            book.onTripStarted("t$i", start)
            book.onUserChange("t$i", SignalIds.STEERING_WHEEL_HEAT, SignalValue.of(1), start.plusSeconds(120))
        }
        return book
    }

    private fun controller(book: HabitBook) =
        RoutineController(catalog, emptyList(), book, post = { posted += it }, saveRoutines = { saved += it }, log = {}, now = { now })

    @BeforeTest
    @AfterTest
    fun resetAppState() = AppState.update { UiState() }

    @Test
    fun `a shown suggestion stays on screen when the next tick refreshes`() {
        val controller = controller(habits())
        controller.onDriving(parked)
        assertNotNull(AppState.ui.value.suggestion)
        controller.suggestionShown()
        now += 60_000
        controller.refreshSuggestion()
        assertNotNull(AppState.ui.value.suggestion, "the card the driver is reading must not vanish")
    }

    @Test
    fun `accepting adds the routine, saves it and tells the engine`() {
        val book = habits()
        val controller = controller(book)
        controller.onDriving(parked)
        controller.acceptSuggestion()
        assertNull(AppState.ui.value.suggestion)
        assertEquals(1, AppState.ui.value.routines.size)
        assertEquals(1, saved.last().size)
        assertTrue(posted.last() is EngineEvent.RoutinesChanged)
        assertEquals(FeedbackKind.ACCEPTED, book.log.feedback.single().kind)
    }

    @Test
    fun `a card whose trips were cleared away disappears on the next refresh`() {
        val book = habits()
        val controller = controller(book)
        controller.onDriving(parked)
        assertNotNull(AppState.ui.value.suggestion)
        book.clear()
        controller.refreshSuggestion()
        assertNull(AppState.ui.value.suggestion)
    }

    @Test
    fun `driving off removes the card`() {
        val controller = controller(habits())
        controller.onDriving(parked)
        controller.onDriving(driving)
        assertNull(AppState.ui.value.suggestion)
    }

    // --- Editor: save, update, delete ---

    private val rain = Routine(
        id = "rain", name = "비 오는 날", trigger = kr.routinegongbang.core.model.Trigger.SignalEdge(SignalIds.WIPER_FRONT_STATE, to = setOf(SignalValue.Text("ON"))),
        actions = listOf(kr.routinegongbang.core.model.Action.Write(SignalIds.DEFROSTER_FRONT, SignalValue.Bool(true))),
    )

    @Test
    fun `a new routine is added at the end, saved and handed to the engine`() {
        val controller = controller(HabitBook(HabitRecorder(catalog, zone), HabitDetector(catalog, zone), HabitLog()))
        controller.onDriving(parked)
        val result = controller.saveRoutine(rain)
        assertTrue(result.canSave)
        assertEquals(listOf("rain"), AppState.ui.value.routines.map { it.routine.id })
        assertEquals(listOf("rain"), saved.last().map { it.id })
        assertTrue(posted.last() is EngineEvent.RoutinesChanged)
    }

    @Test
    fun `an edited routine keeps its place in the list, which is its priority`() {
        val controller = RoutineController(catalog, listOf(rain, rain.copy(id = "second", name = "둘째", actions = listOf(kr.routinegongbang.core.model.Action.Write(SignalIds.RECIRCULATION, SignalValue.Bool(true))))), habits(), post = { posted += it }, saveRoutines = { saved += it }, log = {}, now = { now })
        controller.onDriving(parked)
        controller.saveRoutine(rain.copy(name = "비 올 때"))
        assertEquals(listOf("비 올 때", "둘째"), AppState.ui.value.routines.map { it.routine.name })
    }

    @Test
    fun `a routine that fails the check is not saved and the reasons come back`() {
        val controller = controller(habits())
        controller.onDriving(parked)
        val result = controller.saveRoutine(rain.copy(actions = emptyList()))
        assertTrue(!result.canSave && result.problems.isNotEmpty())
        assertTrue(saved.isEmpty())
        assertTrue(AppState.ui.value.routines.isEmpty())
    }

    @Test
    fun `a routine can be deleted`() {
        val controller = RoutineController(catalog, listOf(rain), habits(), post = { posted += it }, saveRoutines = { saved += it }, log = {}, now = { now })
        controller.onDriving(parked)
        controller.deleteRoutine("rain")
        assertTrue(AppState.ui.value.routines.isEmpty())
        assertTrue(saved.last().isEmpty())
    }

    @Test
    fun `nothing is saved or deleted while driving, whatever the screen sends`() {
        // The command layer must not trust the UI's parked check.
        val controller = RoutineController(catalog, listOf(rain), habits(), post = { posted += it }, saveRoutines = { saved += it }, log = {}, now = { now })
        controller.onDriving(driving)
        assertTrue(!controller.saveRoutine(rain.copy(name = "주행 중")).canSave)
        controller.deleteRoutine("rain")
        assertEquals(listOf("비 오는 날"), AppState.ui.value.routines.map { it.routine.name })
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `only comfort settings can be saved from the editor, never body parts`() {
        // The editor's choices are comfort-only, but a saved routine must be checked here too.
        val controller = controller(habits())
        controller.onDriving(parked)
        val window = rain.copy(actions = listOf(kr.routinegongbang.core.model.Action.Write(SignalIds.WINDOW_POSITION_DRIVER, SignalValue.of(0))))
        val result = controller.saveRoutine(window)
        assertTrue(!result.canSave)
        assertTrue(AppState.ui.value.routines.isEmpty())
    }

    // --- Why nothing ran, stale results, the habit card ---

    private val state = kr.routinegongbang.core.engine.ExecutorState(
        kr.routinegongbang.core.engine.WriteLedger(catalog), kr.routinegongbang.core.engine.WriteRateLimiter.default(),
    )

    private fun ran(routineId: String, vararg results: kr.routinegongbang.core.engine.ActionResult) =
        kr.routinegongbang.core.engine.Decision.Ran(kr.routinegongbang.core.engine.ExecutionReport(routineId, results.toList(), state, false, false))

    private fun item(id: String) = AppState.ui.value.routines.single { it.routine.id == id }

    @Test
    fun `a routine that did not run says why`() {
        val controller = RoutineController(catalog, listOf(rain), habits(), post = {}, saveRoutines = {}, log = {}, now = { now })
        controller.onDecision(kr.routinegongbang.core.engine.Decision.ConditionNotMet("rain", emptySet()))
        assertEquals(Skip.Kind.CONDITION, item("rain").lastSkip?.kind)
        controller.onDecision(kr.routinegongbang.core.engine.Decision.LimitBlocked("rain", kr.routinegongbang.core.engine.LimitReason.COOLDOWN))
        assertEquals(Skip.Kind.COOLDOWN, item("rain").lastSkip?.kind)
        assertEquals(now, item("rain").lastSkip?.atEpochMs)
    }

    @Test
    fun `a condition that could not be read is told apart from one that was false`() {
        val controller = RoutineController(catalog, listOf(rain), habits(), post = {}, saveRoutines = {}, log = {}, now = { now })
        controller.onDecision(kr.routinegongbang.core.engine.Decision.ConditionNotMet("rain", setOf(SignalIds.WIPER_FRONT_STATE)))
        assertEquals(Skip.Kind.NOT_READ_YET, item("rain").lastSkip?.kind)
    }

    @Test
    fun `a run after a skip clears the skip`() {
        val controller = RoutineController(catalog, listOf(rain), habits(), post = {}, saveRoutines = {}, log = {}, now = { now })
        controller.onDecision(kr.routinegongbang.core.engine.Decision.LimitBlocked("rain", kr.routinegongbang.core.engine.LimitReason.TRIP_LIMIT))
        controller.onDecision(ran("rain", kr.routinegongbang.core.engine.ActionResult.NoChange))
        assertNull(item("rain").lastSkip)
        assertNotNull(item("rain").lastRun)
    }

    @Test
    fun `changing a routine's actions forgets results that belonged to the old ones`() {
        // An old navigation failure must not show up next to the wait that replaced it.
        val controller = RoutineController(catalog, listOf(rain), habits(), post = {}, saveRoutines = {}, log = {}, now = { now })
        controller.onDriving(parked)
        controller.onDecision(ran("rain", kr.routinegongbang.core.engine.ActionResult.NoChange))
        val changed = rain.copy(actions = listOf(kr.routinegongbang.core.model.Action.Write(SignalIds.RECIRCULATION, SignalValue.Bool(true))))
        assertTrue(controller.saveRoutine(changed).canSave)
        assertNull(item("rain").lastRun)
    }

    @Test
    fun `renaming keeps the results, the actions are the same`() {
        val controller = RoutineController(catalog, listOf(rain), habits(), post = {}, saveRoutines = {}, log = {}, now = { now })
        controller.onDriving(parked)
        controller.onDecision(ran("rain", kr.routinegongbang.core.engine.ActionResult.NoChange))
        assertTrue(controller.saveRoutine(rain.copy(name = "비")).canSave)
        assertNotNull(item("rain").lastRun)
    }

    @Test
    fun `a card hidden by moving the car a little comes back when parked again on the same trip`() {
        // Re-parking must not lose the card for a day, nor count it as an unanswered showing.
        val controller = controller(habits())
        controller.onDriving(parked)
        val shown = assertNotNull(AppState.ui.value.suggestion)
        controller.suggestionShown()
        controller.onDriving(driving)
        assertNull(AppState.ui.value.suggestion)
        controller.onDriving(parked)
        assertEquals(shown.routine.id, AppState.ui.value.suggestion?.routine?.id)
    }

    @Test
    fun `the card being read is not swapped for another habit of the same trip`() {
        // Two habits ready at once: the first card must not be replaced 3 s later, already counted as shown.
        val recorder = HabitRecorder(catalog, zone)
        val book = HabitBook(recorder, HabitDetector(catalog, zone), HabitLog())
        listOf("2026-10-26", "2026-10-27", "2026-10-28").forEachIndexed { i, day ->
            val start = Instant.parse("${day}T08:00:00+09:00")
            book.onTripStarted("t$i", start)
            book.onUserChange("t$i", SignalIds.STEERING_WHEEL_HEAT, SignalValue.of(1), start.plusSeconds(120))
            book.onUserChange("t$i", SignalIds.SEAT_HEAT_DRIVER, SignalValue.of(1), start.plusSeconds(180))
        }
        val controller = controller(book)
        controller.onDriving(parked)
        val first = assertNotNull(AppState.ui.value.suggestion)
        controller.suggestionShown()
        now += 3_000
        controller.refreshSuggestion()
        assertEquals(first.routine.id, AppState.ui.value.suggestion?.routine?.id)
    }

    @Test
    fun `a result of the run clears a skip that said it was still waiting`() {
        // "앞선 실행이 아직 끝나지 않았어요" must not stay after the wait finished.
        val controller = RoutineController(catalog, listOf(rain), habits(), post = {}, saveRoutines = {}, log = {}, now = { now })
        controller.onDecision(ran("rain", kr.routinegongbang.core.engine.ActionResult.Sent(SignalIds.DEFROSTER_FRONT)))
        controller.onDecision(kr.routinegongbang.core.engine.Decision.LimitBlocked("rain", kr.routinegongbang.core.engine.LimitReason.STILL_WAITING))
        controller.onResult(kr.routinegongbang.core.engine.ResultUpdate("rain", 0, kr.routinegongbang.core.engine.ActionResult.Confirmed(SignalIds.DEFROSTER_FRONT)))
        assertNull(item("rain").lastSkip)
    }

    @Test
    fun `any saved change clears the skip, it was about the old routine`() {
        val controller = RoutineController(catalog, listOf(rain), habits(), post = {}, saveRoutines = {}, log = {}, now = { now })
        controller.onDriving(parked)
        controller.onDecision(kr.routinegongbang.core.engine.Decision.LimitBlocked("rain", kr.routinegongbang.core.engine.LimitReason.COOLDOWN))
        assertTrue(controller.saveRoutine(rain.copy(limits = kr.routinegongbang.core.model.Limits(cooldownMinutes = 5))).canSave)
        assertNull(item("rain").lastSkip)
    }

    @Test
    fun `a late report that does not fit the edited routine is not shown`() {
        // Results by position from the old actions would land on the new ones.
        val controller = RoutineController(catalog, listOf(rain), habits(), post = {}, saveRoutines = {}, log = {}, now = { now })
        controller.onDecision(ran("rain", kr.routinegongbang.core.engine.ActionResult.NoChange, kr.routinegongbang.core.engine.ActionResult.NoChange))
        assertNull(item("rain").lastRun)
    }

    @Test
    fun `while driving it is quiet, and parking releases what was held`() {
        val released = mutableListOf<List<String>>()
        val controller = RoutineController(catalog, emptyList(), habits(), post = {}, saveRoutines = {}, log = {}, now = { now }, onParkedAgain = { notices, _ -> released += notices.texts })
        controller.onDriving(driving)
        assertTrue(controller.quiet())
        controller.notices.offer("a", controller::quiet)
        controller.onDriving(parked)
        assertEquals(listOf(listOf("a")), released)
        assertTrue(!controller.quiet())
    }

    @Test
    fun `when the driving state turns unknown, what was held comes out too`() {
        // New notices go out at once, so the held ones must not wait for a parking that might never be seen.
        val released = mutableListOf<List<String>>()
        val controller = RoutineController(catalog, emptyList(), habits(), post = {}, saveRoutines = {}, log = {}, now = { now }, onParkedAgain = { notices, _ -> released += notices.texts })
        controller.onDriving(driving)
        controller.notices.offer("a", controller::quiet)
        controller.onDriving(DrivingState(null, null, null))
        assertEquals(listOf(listOf("a")), released)
    }

    @Test
    fun `a notice held while driving says so on the card, and counts as told once parked`() {
        val controller = RoutineController(catalog, listOf(rain), habits(), post = {}, saveRoutines = {}, log = {}, now = { now }, onParkedAgain = { _, _ -> })
        controller.onDriving(driving)
        controller.onDecision(ran("rain", kr.routinegongbang.core.engine.ActionResult.Notified(null, held = true)))
        assertEquals(listOf(kr.routinegongbang.app.ui.model.Outcome.IN_PROGRESS), item("rain").lastRun!!.results.map { kr.routinegongbang.app.ui.model.StatusView.of(it).outcome })
        controller.onDriving(parked)
        assertEquals(listOf(kr.routinegongbang.app.ui.model.Outcome.TOLD), item("rain").lastRun!!.results.map { kr.routinegongbang.app.ui.model.StatusView.of(it).outcome })
        assertEquals(listOf(kr.routinegongbang.app.ui.model.Outcome.TOLD), AppState.ui.value.log.single().outcomes)
    }

    @Test
    fun `a held report arriving after the release is told at once`() {
        val controller = RoutineController(catalog, listOf(rain), habits(), post = {}, saveRoutines = {}, log = {}, now = { now })
        controller.onDriving(parked)
        controller.onDecision(ran("rain", kr.routinegongbang.core.engine.ActionResult.Notified(null, held = true)))
        assertEquals(listOf(kr.routinegongbang.app.ui.model.Outcome.TOLD), item("rain").lastRun!!.results.map { kr.routinegongbang.app.ui.model.StatusView.of(it).outcome })
    }

    @Test
    fun `a new demo story does not bring the previous story's held notices`() {
        // d1 ends while driving; the next story's parking must not post d1's summary as a digest.
        val released = mutableListOf<List<String>>()
        val controller = RoutineController(catalog, emptyList(), habits(), post = {}, saveRoutines = {}, log = {}, now = { now }, onParkedAgain = { notices, _ -> released += notices.texts })
        controller.onDriving(driving)
        controller.notices.offer("d1", controller::quiet)
        controller.replaceAll(listOf(rain))
        controller.onDriving(parked)
        assertTrue(released.isEmpty())
    }

    @Test
    fun `a failing digest does not stop the parked suggestion`() {
        val controller = RoutineController(catalog, emptyList(), habits(), post = {}, saveRoutines = {}, log = {}, now = { now }, onParkedAgain = { _, _ -> error("boom") })
        controller.onDriving(driving)
        controller.notices.offer("a", controller::quiet)
        controller.onDriving(parked)
        assertNotNull(AppState.ui.value.suggestion)
    }

    @Test
    fun `an unknown driving state is not quiet, nothing would ever come out`() {
        val controller = RoutineController(catalog, emptyList(), habits(), post = {}, saveRoutines = {}, log = {}, now = { now })
        controller.onDriving(DrivingState(null, null, null))
        assertTrue(!controller.quiet())
    }

    // --- Trust the driver gives: ask first, then by itself after three yeses in a row ---

    private val asking = rain.copy(id = "ask", name = "추운 아침", policy = kr.routinegongbang.core.model.Policy(askBeforeRun = true))
    private val sent = kr.routinegongbang.core.engine.ActionResult.Sent(SignalIds.DEFROSTER_FRONT)

    @Test
    fun `three allowed runs in a row and the routine runs by itself, and the driver is told`() {
        val earned = mutableListOf<String>()
        val ledgers = mutableListOf<kr.routinegongbang.app.ui.model.TrustLedger>()
        val controller = RoutineController(catalog, listOf(asking), habits(), post = {}, saveRoutines = {}, log = {}, now = { now },
            saveTrust = { ledgers += it }, onTrustEarned = { earned += it })
        controller.onDecision(ran("ask", sent))
        controller.onDecision(ran("ask", sent))
        assertEquals(2, item("ask").trustYes)
        assertTrue(item("ask").routine.policy.askBeforeRun)
        controller.onDecision(ran("ask", sent))
        assertTrue(!item("ask").routine.policy.askBeforeRun)
        assertEquals(listOf("추운 아침"), earned)
        assertEquals(0, ledgers.last().count("ask"))
    }

    @Test
    fun `a refused question starts the count again`() {
        val controller = RoutineController(catalog, listOf(asking), habits(), post = {}, saveRoutines = {}, log = {}, now = { now })
        controller.onDecision(ran("ask", sent))
        controller.onDecision(ran("ask", kr.routinegongbang.core.engine.ActionResult.Cancelled(kr.routinegongbang.core.engine.Reason.CONFIRM_DECLINED)))
        assertEquals(0, item("ask").trustYes)
    }

    @Test
    fun `the count survives a restart`() {
        val ledger = kr.routinegongbang.app.ui.model.TrustLedger().afterYes("ask").first
        RoutineController(catalog, listOf(asking), habits(), post = {}, saveRoutines = {}, log = {}, now = { now }, initialTrust = ledger)
        assertEquals(1, item("ask").trustYes)
    }

    @Test
    fun `changing what a routine does forgets the trust it had earned`() {
        val controller = RoutineController(catalog, listOf(asking), habits(), post = {}, saveRoutines = {}, log = {}, now = { now })
        controller.onDriving(parked)
        controller.onDecision(ran("ask", sent))
        val other = kr.routinegongbang.core.model.Action.Write(SignalIds.STEERING_WHEEL_HEAT, SignalValue.of(1))
        assertTrue(controller.saveRoutine(asking.copy(actions = listOf(other))).canSave)
        assertEquals(0, item("ask").trustYes)
    }

    @Test
    fun `an adopted habit starts by asking first`() {
        val controller = controller(habits())
        controller.onDriving(parked)
        controller.refreshSuggestion()
        assertNotNull(AppState.ui.value.suggestion)
        controller.acceptSuggestion()
        assertTrue(AppState.ui.value.routines.single().routine.policy.askBeforeRun)
    }

    @Test
    fun `switching a routine off or deleting it forgets its count too, and a demo script starts all counts afresh`() {
        val other = asking.copy(id = "ask2")
        val ledgers = mutableListOf<kr.routinegongbang.app.ui.model.TrustLedger>()
        val controller = RoutineController(catalog, listOf(asking, other), habits(), post = {}, saveRoutines = {}, log = {}, now = { now },
            saveTrust = { ledgers += it })
        controller.onDriving(parked)
        controller.onDecision(ran("ask", sent))
        controller.onDecision(Decision.Ran(ExecutionReport("ask2", listOf(sent), state, false, false)))
        controller.setEnabled("ask", false)
        assertEquals(0, item("ask").trustYes)
        assertEquals(1, item("ask2").trustYes)
        controller.deleteRoutine("ask2")
        assertEquals(0, ledgers.last().count("ask2"))
        controller.onDecision(ran("ask", sent))
        controller.replaceAll(listOf(asking))
        assertEquals(0, item("ask").trustYes)
    }

    @Test
    fun `a no after a wait starts the count again`() {
        val controller = RoutineController(catalog, listOf(asking), habits(), post = {}, saveRoutines = {}, log = {}, now = { now })
        controller.onDecision(ran("ask", sent))
        controller.onResult(kr.routinegongbang.core.engine.ResultUpdate("ask", 0, kr.routinegongbang.core.engine.ActionResult.Cancelled(kr.routinegongbang.core.engine.Reason.CONFIRM_TIMEOUT), 0))
        assertEquals(0, item("ask").trustYes)
    }

    @Test
    fun `a notice that fails to show does not keep the routine asking`() {
        val controller = RoutineController(catalog, listOf(asking), habits(), post = {}, saveRoutines = {}, log = {}, now = { now },
            onTrustEarned = { error("no notification manager") })
        repeat(3) { controller.onDecision(ran("ask", sent)) }
        assertTrue(!item("ask").routine.policy.askBeforeRun)
    }

    // --- The trip receipt: at P after a drive, what every routine did ---

    private val windowCheck = rain.copy(id = "window", name = "창문 확인")

    /** A controller whose delayed work ([RoutineController] `later`) runs only when the test says so. */
    private fun receiptController(receipts: MutableList<kr.routinegongbang.app.ui.model.TripReceipt?>, later: MutableList<() -> Unit>, released: MutableList<String> = mutableListOf()) =
        RoutineController(catalog, listOf(rain, windowCheck), habits(), post = {}, saveRoutines = {}, log = {}, now = { now },
            onParkedAgain = { notices, receipt ->
                released += notices.texts
                receipts += receipt
            },
            later = { _, block -> later += block })

    @Test
    fun `a routine that starts at P is on the receipt too, which waits a moment after parking`() {
        val receipts = mutableListOf<kr.routinegongbang.app.ui.model.TripReceipt?>()
        val later = mutableListOf<() -> Unit>()
        val controller = receiptController(receipts, later)
        controller.onTripStarted("t9", Instant.ofEpochMilli(now))
        controller.onDriving(driving)
        now += 60_000
        controller.onDecision(ran("rain", sent))
        now += 60_000
        controller.onDriving(parked)
        assertTrue(receipts.isEmpty(), "not at the instant of P: a parked trigger holds a few seconds")
        now += 5_000
        controller.onDecision(ran("window", sent))
        later.single()()
        assertEquals(listOf("비 오는 날", "창문 확인"), receipts.single()!!.entries.map { it.routineName })
    }

    @Test
    fun `moving again before the receipt goes out keeps it, and the held notices, for the next stop`() {
        val receipts = mutableListOf<kr.routinegongbang.app.ui.model.TripReceipt?>()
        val later = mutableListOf<() -> Unit>()
        val released = mutableListOf<String>()
        val controller = receiptController(receipts, later, released)
        controller.onTripStarted("t9", Instant.ofEpochMilli(now))
        controller.onDriving(driving)
        controller.notices.offer("우산 챙기세요") { controller.quiet() }
        now += 60_000
        controller.onDecision(ran("rain", sent))
        controller.onDriving(parked)
        controller.onDriving(driving)
        later.removeAt(0)()
        assertTrue(receipts.isEmpty(), "nothing goes out while the car is moving")
        now += 60_000
        controller.onDecision(ran("window", sent))
        now += 60_000
        controller.onDriving(parked)
        later.single()()
        assertEquals(listOf("비 오는 날", "창문 확인"), receipts.single()!!.entries.map { it.routineName })
        assertEquals(listOf("우산 챙기세요"), released)
    }

    @Test
    fun `when the car's state turns unknown the held notices come out, and the receipt waits for P`() {
        val receipts = mutableListOf<kr.routinegongbang.app.ui.model.TripReceipt?>()
        val later = mutableListOf<() -> Unit>()
        val released = mutableListOf<String>()
        val controller = receiptController(receipts, later, released)
        controller.onTripStarted("t9", Instant.ofEpochMilli(now))
        controller.onDriving(driving)
        controller.notices.offer("우산 챙기세요") { controller.quiet() }
        now += 60_000
        controller.onDecision(ran("rain", sent))
        controller.onDriving(DrivingState(null, null, null))
        later.removeAt(0)()
        assertEquals(listOf("우산 챙기세요"), released)
        assertEquals(listOf<kr.routinegongbang.app.ui.model.TripReceipt?>(null), receipts)
        controller.onDriving(parked)
        later.single()()
        assertEquals(listOf("비 오는 날"), receipts.last()!!.entries.map { it.routineName })
    }

    @Test
    fun `with no drive there is no receipt, even after a routine ran at getting in`() {
        val receipts = mutableListOf<kr.routinegongbang.app.ui.model.TripReceipt?>()
        val later = mutableListOf<() -> Unit>()
        val controller = receiptController(receipts, later)
        controller.onTripStarted("t9", Instant.ofEpochMilli(now))
        controller.onDecision(ran("rain", sent))
        controller.onDriving(parked)
        later.forEach { it() }
        assertTrue(receipts.isEmpty())
    }

    @Test
    fun `a stop after a receipt starts the next one, and a demo script clears the card`() {
        val receipts = mutableListOf<kr.routinegongbang.app.ui.model.TripReceipt?>()
        val later = mutableListOf<() -> Unit>()
        val controller = receiptController(receipts, later)
        controller.onTripStarted("t9", Instant.ofEpochMilli(now))
        controller.onDriving(driving)
        controller.onDecision(ran("rain", sent))
        controller.onDriving(parked)
        later.removeAt(0)()
        controller.onDriving(driving)
        now += 60_000
        controller.onDecision(ran("window", sent))
        controller.onDriving(parked)
        later.removeAt(0)()
        assertEquals(listOf(listOf("비 오는 날"), listOf("창문 확인")), receipts.map { r -> r!!.entries.map { it.routineName } })
        controller.replaceAll(listOf(rain))
        assertNull(AppState.ui.value.receipt)
    }

    @Test
    fun `parking after a drive gives the trip's receipt, and the next trip clears it`() {
        val receipts = mutableListOf<kr.routinegongbang.app.ui.model.TripReceipt?>()
        val controller = RoutineController(catalog, listOf(rain), habits(), post = {}, saveRoutines = {}, log = {}, now = { now },
            onParkedAgain = { _, receipt -> receipts += receipt })
        controller.onTripStarted("t9", Instant.ofEpochMilli(now))
        controller.onDriving(driving)
        now += 60_000
        controller.onDecision(ran("rain", sent))
        now += 60_000
        controller.onDriving(parked)
        assertEquals(listOf("비 오는 날"), receipts.single()!!.entries.map { it.routineName })
        assertEquals(listOf("비 오는 날"), AppState.ui.value.receipt!!.entries.map { it.routineName })
        controller.onTripStarted("t10", Instant.ofEpochMilli(now))
        assertNull(AppState.ui.value.receipt)
    }

    @Test
    fun `a drive where nothing ran gives no receipt`() {
        val receipts = mutableListOf<kr.routinegongbang.app.ui.model.TripReceipt?>()
        val controller = RoutineController(catalog, listOf(rain), habits(), post = {}, saveRoutines = {}, log = {}, now = { now },
            onParkedAgain = { _, receipt -> receipts += receipt })
        controller.onTripStarted("t9", Instant.ofEpochMilli(now))
        controller.onDriving(driving)
        controller.onDriving(parked)
        assertTrue(receipts.isEmpty())
        assertNull(AppState.ui.value.receipt)
    }

    @Test
    fun `runs and skips go into the recent log, newest first, and a demo script starts it afresh`() {
        val controller = RoutineController(catalog, listOf(rain), habits(), post = {}, saveRoutines = {}, log = {}, now = { now })
        controller.onDecision(ran("rain", kr.routinegongbang.core.engine.ActionResult.NoChange))
        now += 1_000
        controller.onDecision(kr.routinegongbang.core.engine.Decision.ConditionNotMet("rain", emptySet()))
        assertEquals(listOf(Skip.Kind.CONDITION, null), AppState.ui.value.log.map { it.skip })
        assertEquals("비 오는 날", AppState.ui.value.log.first().routineName)
        controller.replaceAll(listOf(rain))
        assertTrue(AppState.ui.value.log.isEmpty())
    }
}
