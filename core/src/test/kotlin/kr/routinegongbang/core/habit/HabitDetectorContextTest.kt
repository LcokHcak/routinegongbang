package kr.routinegongbang.core.habit

import kr.routinegongbang.core.model.Condition
import kr.routinegongbang.core.model.SignalIds
import kr.routinegongbang.core.model.SignalValue
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Feedback and ids per context, repeated unanswered suggestions, window edges. */
class HabitDetectorContextTest {

    private val recorder = HabitRecorder(habitCatalog, SEOUL_ZONE)
    private val detector = HabitDetector(habitCatalog, SEOUL_ZONE)
    private val heat = SignalIds.STEERING_WHEEL_HEAT
    private val heatOn = HabitPattern(heat, SignalValue.of(1))
    private val morning = RideContext(DayKind.WEEKDAY, 8 * 60)
    private val evening = RideContext(DayKind.WEEKDAY, 18 * 60)

    private fun rides(starts: List<Instant>, from: HabitLog = HabitLog(), prefix: String = "t"): HabitLog =
        starts.withIndex().fold(from) { acc, (i, start) ->
            val id = "$prefix${i + 1}"
            recorder.onUserChange(recorder.onTripStarted(acc, id, start), id, heat, SignalValue.of(1), start.plusMillis(3 * MINUTE))
        }

    private val mornings = rides(listOf(at("2026-10-26", "08:00"), at("2026-10-27", "08:00"), at("2026-10-28", "08:00")))
    private val both = rides(listOf(at("2026-10-26", "18:00"), at("2026-10-27", "18:00"), at("2026-10-28", "18:00")), mornings, prefix = "e")
    private val now = at("2026-10-28", "19:00").toEpochMilli()

    @Test
    fun `the same habit in two contexts gives two routines with different ids`() {
        val morningSuggestion = detector.suggest(both, "t3", emptyList(), now).single()
        val eveningSuggestion = detector.suggest(both, "e3", emptyList(), now).single()
        assertTrue(morningSuggestion.routine.routine.id != eveningSuggestion.routine.routine.id)
    }

    @Test
    fun `an answer only silences the context it was given in`() {
        val rejected = both.withFeedback(heatOn, morning, FeedbackKind.REJECTED, now)
        assertTrue(detector.suggest(rejected, "t3", emptyList(), now).isEmpty())
        assertEquals(1, detector.suggest(rejected, "e3", emptyList(), now).size)
    }

    @Test
    fun `a suggestion ignored three times counts as rejected`() {
        val shown = (0 until HabitDetector.MAX_UNANSWERED).fold(mornings) { log, i -> log.withFeedback(heatOn, morning, FeedbackKind.SHOWN, now + i * DAY) }
        val lastShown = now + (HabitDetector.MAX_UNANSWERED - 1) * DAY
        assertTrue(detector.suggest(shown, "t3", emptyList(), lastShown + 2 * DAY).isEmpty())
        assertEquals(1, detector.suggest(shown, "t3", emptyList(), lastShown + 30 * DAY).size)
    }

    @Test
    fun `accepted suggestions stay quiet for the retention period, then the existing routine decides`() {
        val accepted = mornings.withFeedback(heatOn, morning, FeedbackKind.ACCEPTED, now)
        assertTrue(detector.suggest(accepted, "t3", emptyList(), now + 29 * DAY).isEmpty())
        assertEquals(FeedbackKind.ACCEPTED, accepted.pruned(now + DAY, 30 * DAY).feedback.single().kind)
        assertTrue(accepted.pruned(now + 31 * DAY, 30 * DAY).feedback.isEmpty())
    }

    @Test
    fun `the generated window includes every supporting trip, edges included`() {
        val log = rides(listOf(at("2026-10-26", "09:00"), at("2026-10-27", "08:30"), at("2026-10-28", "08:00")))
        val window = (detector.suggest(log, "t3", emptyList(), now).single().routine.routine.condition as Condition.And).conditions[1] as Condition.TimeWindow
        assertEquals(Condition.TimeWindow(startMinute = 7 * 60, endMinute = 9 * 60 + 1), window)
    }

    @Test
    fun `evicted trips take their operations with them`() {
        val start = at("2026-10-26", "08:00")
        val full = (1..HabitLog.MAX_TRIPS).fold(rides(listOf(start))) { log, i -> recorder.onTripStarted(log, "x$i", start.plusMillis(i * MINUTE)) }
        assertTrue(full.operations.none { it.tripId == "t1" })
    }

    @Test
    fun `starting a trip prunes records past the retention period`() {
        val later = recorder.onTripStarted(mornings, "new", at("2026-12-01", "08:00"))
        assertEquals(listOf("new"), later.trips.map { it.tripId })
        assertTrue(later.operations.isEmpty())
    }

    @Test
    fun `a newer schema value fails the whole decode so the app can start fresh`() {
        val text = HabitLogJson.encode(mornings.withFeedback(heatOn, morning, FeedbackKind.SHOWN, 1)).replace("\"SHOWN\"", "\"SNOOZED\"")
        assertTrue(HabitLogJson.decode(text).isFailure)
        assertTrue(HabitLogJson.decode(" ".repeat(HabitLogJson.MAX_INPUT_LENGTH + 1)).isFailure)
        assertEquals(HabitLog.SCHEMA_VERSION, HabitLogJson.decode(HabitLogJson.encode(HabitLog())).getOrThrow().schemaVersion)
    }

    @Test
    fun `habit book serializes updates and reports every change`() {
        val changes = mutableListOf<HabitLog>()
        val book = HabitBook(recorder, detector, HabitLog(), onChange = { changes += it })
        val start = at("2026-10-28", "08:00")
        book.onTripStarted("t1", start)
        book.onUserChange("t1", heat, SignalValue.of(1), start.plusMillis(MINUTE))
        assertEquals(1, book.log.operations.size)
        assertEquals(2, changes.size)
        book.answer(HabitPattern(heat, SignalValue.of(1)), "t1", FeedbackKind.REJECTED, start.toEpochMilli())
        assertEquals(FeedbackKind.REJECTED, book.log.feedback.single().kind)
        assertEquals(null, book.bestSuggestion("t1", emptyList(), start.toEpochMilli()))
    }

    @Test
    fun `clearing the book forgets everything and reports it`() {
        val changes = mutableListOf<HabitLog>()
        val book = HabitBook(recorder, detector, mornings, onChange = { changes += it })
        book.clear()
        assertEquals(HabitLog(), book.log)
        assertEquals(listOf(HabitLog()), changes)
        book.clear()
        assertEquals(1, changes.size, "clearing an empty book changes nothing")
    }
}
