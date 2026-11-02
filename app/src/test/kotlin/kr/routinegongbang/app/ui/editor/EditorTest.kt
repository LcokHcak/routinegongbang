package kr.routinegongbang.app.ui.editor

import kr.routinegongbang.app.R
import kr.routinegongbang.core.model.Action
import kr.routinegongbang.core.model.CompareOp
import kr.routinegongbang.core.model.Condition
import kr.routinegongbang.core.model.Limits
import kr.routinegongbang.core.model.Policy
import kr.routinegongbang.core.model.Routine
import kr.routinegongbang.core.model.RoutineValidator
import kr.routinegongbang.core.model.SignalCatalog
import kr.routinegongbang.core.model.SignalIds
import kr.routinegongbang.core.model.SignalValue
import kr.routinegongbang.core.model.Trigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The editor: templates first, editable parameters, a limited set of choices, every save checked. */
class EditorCatalogTest {

    private val catalog = SignalCatalog.default()
    private val validator = RoutineValidator(catalog)

    @Test
    fun `rain means the wiper keeps going, and it clears the windscreen without closing the air`() {
        // One wipe is not "rain". Recirculation fogs the glass; the car itself switches
        // to fresh air when it defogs.
        val rain = EditorCatalog.trigger(TriggerChoice.RAIN) as Trigger.SignalEdge
        assertTrue(rain.holdSeconds >= 5)
        val template = EditorCatalog.template(Template.RAIN, "r", "r")
        assertTrue(template.actions.none { it is Action.Write && it.signal == SignalIds.RECIRCULATION && (it.value as SignalValue.Bool).value })
    }

    @Test
    fun `the window check fires on parking, while the driver is still inside to see it`() {
        // After locking, the driver is gone and never sees the car screen.
        val routine = EditorCatalog.template(Template.WINDOW_CHECK, "w", "w")
        assertEquals(EditorCatalog.trigger(TriggerChoice.PARKED), routine.trigger)
        assertEquals(TriggerChoice.PARKED, EditorCatalog.choiceOf(routine.trigger))
        assertTrue(SaveCheck(catalog).of(routine, emptyList()).canSave)
    }

    @Test
    fun `a template that only acts at a parked moment starts by asking first`() {
        // Asking while driving is only a notice (no speech recognition yet), so a routine that may act on the move
        // would never hear the three yeses that let it run by itself.
        val asking = Template.entries.filter { EditorCatalog.template(it, "t", "t").policy.askBeforeRun }
        assertEquals(listOf(Template.HOT_DAY, Template.COMMUTE_HOME), asking)
    }

    @Test
    fun `asking first is only the default for writes and routes at getting in, parking or getting out`() {
        val heat = Action.Write(SignalIds.STEERING_WHEEL_HEAT, SignalValue.of(1))
        val base = Routine(id = "r", name = "r", trigger = Trigger.RideStart, actions = listOf(heat))
        assertTrue(RoutineDraft.askingFirst(base).policy.askBeforeRun)
        assertTrue(RoutineDraft.askingFirst(base.copy(trigger = EditorCatalog.trigger(TriggerChoice.PARKED))).policy.askBeforeRun)
        assertFalse(RoutineDraft.askingFirst(base.copy(trigger = EditorCatalog.trigger(TriggerChoice.RAIN))).policy.askBeforeRun)
        assertFalse(RoutineDraft.askingFirst(base.copy(trigger = Trigger.RideEnd)).policy.askBeforeRun, "after getting out nobody is there to answer")
        assertFalse(RoutineDraft.askingFirst(base.copy(actions = listOf(heat, Action.Wait(600), heat))).policy.askBeforeRun, "after a wait the car may be moving")
        assertFalse(RoutineDraft.askingFirst(base.copy(actions = listOf(Action.Notify("notify.window_open")))).policy.askBeforeRun)
    }

    @Test
    fun `every template is a routine the app accepts as it is`() {
        Template.entries.forEach { template ->
            val routine = EditorCatalog.template(template, id = "t", name = "이름")
            assertEquals(emptyList(), validator.validate(routine), template.name)
        }
    }

    @Test
    fun `every choice of when, if and do is valid on its own and is recognised again`() {
        TriggerChoice.entries.forEach { choice ->
            val trigger = EditorCatalog.trigger(choice)
            assertEquals(choice, EditorCatalog.choiceOf(trigger), choice.name)
            val routine = RoutineDraft.blank("r", "r").let { RoutineDraft.withTrigger(it, trigger) }.let { RoutineDraft.addAction(it, Action.Notify("notify.window_open")) }
            assertEquals(emptyList(), validator.validate(routine), choice.name)
        }
        ConditionChoice.entries.forEach { choice ->
            val condition = EditorCatalog.condition(choice)
            assertEquals(choice, EditorCatalog.choiceOf(condition), choice.name)
            val routine = RoutineDraft.withConditions(RoutineDraft.addAction(RoutineDraft.blank("r", "r"), Action.Notify("n")), listOf(condition))
            assertEquals(emptyList(), validator.validate(routine), choice.name)
        }
        ActionChoice.entries.filter { it != ActionChoice.WAIT }.forEach { choice ->
            val action = EditorCatalog.action(choice)
            assertEquals(choice, EditorCatalog.choiceOf(action), choice.name)
            val routine = RoutineDraft.addAction(RoutineDraft.blank("r", "r"), action)
            assertEquals(emptyList(), validator.validate(routine), choice.name)
        }
    }

    @Test
    fun `only comfort settings, navigation and notices can be chosen, never body or excluded signals`() {
        val written = ActionChoice.entries.mapNotNull { (EditorCatalog.action(it) as? Action.Write)?.signal }
        written.forEach { assertEquals(kr.routinegongbang.core.model.SafetyClass.COMFORT, catalog.getOrThrow(it).safetyClass, it.value) }
    }

    @Test
    fun `numbers move in the signal's own steps and stay in its range`() {
        val heat = Action.Write(SignalIds.STEERING_WHEEL_HEAT, SignalValue.of(3))
        assertEquals(heat, RoutineDraft.stepAction(heat, up = true))
        assertEquals(SignalValue.of(2), (RoutineDraft.stepAction(heat, up = false) as Action.Write).value)
        val temp = Action.Write(SignalIds.HVAC_TEMP_DRIVER, SignalValue.num(22.0))
        assertEquals(SignalValue.num(22.5), (RoutineDraft.stepAction(temp, up = true) as Action.Write).value)
        val ac = Action.Write(SignalIds.HVAC_AC, SignalValue.Bool(true))
        assertEquals(SignalValue.Bool(false), (RoutineDraft.stepAction(ac, up = true) as Action.Write).value)
        assertEquals(Action.Wait(120), RoutineDraft.stepAction(Action.Wait(60), up = true))
        assertEquals(Action.Wait(600), RoutineDraft.stepAction(Action.Wait(600), up = true))
        assertEquals(Action.Wait(60), RoutineDraft.stepAction(Action.Wait(60), up = false))
    }

    @Test
    fun `trigger and condition numbers step too, times wrap around midnight`() {
        assertEquals(Trigger.TimeOfDay(0, 0), RoutineDraft.stepTrigger(Trigger.TimeOfDay(23, 45), up = true))
        assertEquals(Trigger.RideElapsed(35), RoutineDraft.stepTrigger(Trigger.RideElapsed(30), up = true))
        assertEquals(Trigger.RideElapsed(5), RoutineDraft.stepTrigger(Trigger.RideElapsed(5), up = false))
        val cold = Condition.Compare(SignalIds.CABIN_TEMPERATURE, CompareOp.LE, SignalValue.of(5))
        assertEquals(SignalValue.of(6), (RoutineDraft.stepCondition(cold, up = true) as Condition.Compare).value)
        val window = Condition.TimeWindow(17 * 60, 20 * 60)
        assertEquals(Condition.TimeWindow(17 * 60 + 30, 20 * 60), RoutineDraft.stepCondition(window, up = true, end = false))
        assertEquals(Condition.TimeWindow(17 * 60, 20 * 60 + 30), RoutineDraft.stepCondition(window, up = true, end = true))
    }

    @Test
    fun `the parking trigger waits for P to hold and the window check does not repeat at once`() {
        // P-R-P while parking, or a stale "not parked" state right at the shift.
        val parked = EditorCatalog.trigger(TriggerChoice.PARKED) as Trigger.SignalEdge
        assertTrue(parked.holdSeconds >= 2)
        assertTrue(EditorCatalog.template(Template.WINDOW_CHECK, "w", "w").limits.cooldownMinutes >= 5)
    }
}

class RoutineDraftTest {

    @Test
    fun `a trigger compared by size gets the cooldown the safety check asks for, quietly`() {
        // L1-6: the driver never sees "cooldown"; the editor just never builds what the check would reject.
        val draft = RoutineDraft.withTrigger(RoutineDraft.blank("r", "r"), EditorCatalog.trigger(TriggerChoice.BATTERY_LOW))
        assertTrue(draft.limits.cooldownMinutes >= 10)
        val back = RoutineDraft.withTrigger(draft, Trigger.RideStart)
        assertEquals(draft.limits.cooldownMinutes, back.limits.cooldownMinutes, "never lowered behind the driver's back")
    }

    @Test
    fun `conditions are one list for the driver and an And for the engine`() {
        val r = RoutineDraft.blank("r", "r")
        val weekdays = EditorCatalog.condition(ConditionChoice.WEEKDAYS)
        val evening = EditorCatalog.condition(ConditionChoice.TIME_WINDOW)
        assertNull(RoutineDraft.withConditions(r, emptyList()).condition)
        assertEquals(weekdays, RoutineDraft.withConditions(r, listOf(weekdays)).condition)
        val both = RoutineDraft.withConditions(r, listOf(weekdays, evening))
        assertEquals(Condition.And(listOf(weekdays, evening)), both.condition)
        assertEquals(listOf(weekdays, evening), RoutineDraft.conditions(both))
    }

    @Test
    fun `actions can be added, changed, moved and removed`() {
        val heat = EditorCatalog.action(ActionChoice.STEERING_HEAT)
        val ac = EditorCatalog.action(ActionChoice.AC)
        var r = RoutineDraft.addAction(RoutineDraft.addAction(RoutineDraft.blank("r", "r"), heat), ac)
        r = RoutineDraft.moveAction(r, 1, up = true)
        assertEquals(listOf(ac, heat), r.actions)
        r = RoutineDraft.replaceAction(r, 0, Action.Write(SignalIds.HVAC_AC, SignalValue.Bool(false)))
        r = RoutineDraft.removeAction(r, 1)
        assertEquals(listOf<Action>(Action.Write(SignalIds.HVAC_AC, SignalValue.Bool(false))), r.actions)
    }

    @Test
    fun `only the battery threshold is stepped, other thresholds keep their value`() {
        val speed = Trigger.Threshold(SignalIds.VEHICLE_SPEED, CompareOp.GT, SignalValue.of(120))
        assertEquals(speed, RoutineDraft.stepTrigger(speed, up = true))
    }

    @Test
    fun `a time window never steps to an empty window`() {
        val window = Condition.TimeWindow(17 * 60, 17 * 60 + 30)
        val stepped = RoutineDraft.stepCondition(window, up = true, end = false) as Condition.TimeWindow
        assertTrue(stepped.startMinute != stepped.endMinute, stepped.toString())
    }

    @Test
    fun `run once per drive is on by default, asking first is off`() {
        val r = RoutineDraft.blank("r", "r")
        assertTrue(r.limits.oncePerTrip)
        assertFalse(r.policy.askBeforeRun)
        assertTrue(RoutineDraft.withAskBeforeRun(r, true).policy.askBeforeRun)
        assertFalse(RoutineDraft.withOncePerTrip(r, false).limits.oncePerTrip)
    }
}

class SaveCheckTest {

    private val check = SaveCheck(SignalCatalog.default())
    private fun routine(trigger: Trigger, vararg actions: Action, limits: Limits = Limits()) =
        Routine(id = "r", name = "r", trigger = trigger, actions = actions.toList(), limits = limits)

    @Test
    fun `a routine with nothing to do cannot be saved, and says why in Korean`() {
        val result = check.of(RoutineDraft.blank("r", "r"), others = emptyList())
        assertFalse(result.canSave)
        assertEquals(listOf(Problem(Problem.Level.ERROR, R.string.editor_err_no_actions)), result.problems)
    }

    @Test
    fun `safety errors block saving with their own explanation`() {
        // L1-9: changing a body part after leaving: nobody is there to confirm.
        val lockAfterLeaving = routine(Trigger.RideEnd, Action.Write(SignalIds.DOOR_LOCK, SignalValue.Bool(true)))
        assertTrue(Problem(Problem.Level.ERROR, R.string.editor_err_nobody_left) in check.of(lockAfterLeaving, emptyList()).problems)
        val twice = routine(Trigger.RideStart, Action.Write(SignalIds.HVAC_AC, SignalValue.Bool(true)), Action.Write(SignalIds.HVAC_AC, SignalValue.Bool(false)))
        assertTrue(Problem(Problem.Level.ERROR, R.string.editor_err_written_twice) in check.of(twice, emptyList()).problems)
        val waitLast = routine(Trigger.RideStart, Action.Notify("n"), Action.Wait(60))
        assertTrue(Problem(Problem.Level.ERROR, R.string.editor_err_wait_placement) in check.of(waitLast, emptyList()).problems)
    }

    @Test
    fun `warnings explain but do not block`() {
        val hot = routine(Trigger.RideStart, Action.Write(SignalIds.SEAT_HEAT_DRIVER, SignalValue.of(3)))
        val result = check.of(hot, emptyList())
        assertTrue(result.canSave)
        assertTrue(Problem(Problem.Level.WARNING, R.string.editor_warn_will_ask) in result.problems)
    }

    @Test
    fun `a clash with an earlier routine is pointed out`() {
        val earlier = routine(Trigger.RideStart, Action.Write(SignalIds.HVAC_AC, SignalValue.Bool(true))).copy(id = "earlier")
        val later = routine(Trigger.RideStart, Action.Write(SignalIds.HVAC_AC, SignalValue.Bool(false)))
        assertTrue(Problem(Problem.Level.WARNING, R.string.editor_warn_conflict) in check.of(later, others = listOf(earlier)).problems)
    }

    @Test
    fun `every lint rule has its own message, never the generic one`() {
        // A rule falling through to the generic text would explain nothing.
        (2..14).map { "L1-$it" }.forEach { assertTrue(SaveCheck.messageFor(it) != R.string.editor_err_generic, it) }
    }

    @Test
    fun `the 51st routine cannot be saved`() {
        // L1-12 is a list-wide error the per-routine validator never sees.
        val fifty = List(50) { routine(Trigger.RideStart, Action.Notify("n")).copy(id = "r$it") }
        val result = check.of(routine(Trigger.RideStart, Action.Notify("n")).copy(id = "new"), fifty)
        assertTrue(Problem(Problem.Level.ERROR, R.string.editor_err_too_many_routines) in result.problems)
        assertFalse(result.canSave)
    }

    @Test
    fun `conditions that can never hold together cannot be saved`() {
        // Weekdays and weekends, or colder than 5 and warmer than 28, make a routine that silently never runs.
        fun with(vararg c: ConditionChoice) = RoutineDraft.withConditions(routine(Trigger.RideStart, Action.Notify("n")), c.map { EditorCatalog.condition(it) })
        listOf(with(ConditionChoice.WEEKDAYS, ConditionChoice.WEEKENDS), with(ConditionChoice.COLD, ConditionChoice.HOT)).forEach {
            assertTrue(Problem(Problem.Level.ERROR, R.string.editor_err_condition_never) in check.of(it, emptyList()).problems)
        }
        assertTrue(check.of(with(ConditionChoice.WEEKDAYS, ConditionChoice.COLD), emptyList()).canSave)
    }
}

class SentenceTest {

    @Test
    fun `the summary reads conditions, then when, then what`() {
        assertEquals(
            "평일 · 06:00–10:00 · 차에 타면 → 핸들 열선 2단계, 운전석 열선 2단계",
            Sentence.join(trigger = "차에 타면", conditions = listOf("평일", "06:00–10:00"), actions = listOf("핸들 열선 2단계", "운전석 열선 2단계")),
        )
        assertEquals("차에 타면 → (할 일 없음)", Sentence.join("차에 타면", emptyList(), emptyList(), nothing = "(할 일 없음)"))
    }
}

/** Combinations that would pass with "문제없어요" but need explaining. */
class SaveCheckUserTest {

    private val check = SaveCheck(SignalCatalog.default())
    private fun routine(id: String, trigger: Trigger, vararg actions: Action, policy: Policy = Policy()) =
        Routine(id = id, name = id, trigger = trigger, actions = actions.toList(), policy = policy)
    private val heat = Action.Write(SignalIds.SEAT_HEAT_DRIVER, SignalValue.of(1))
    private val home = Action.SetDestination("home")

    @Test
    fun `the same routine twice is refused`() {
        val first = routine("a", Trigger.RideStart, heat)
        val copy = first.copy(id = "b", name = "다른 이름")
        val result = check.of(copy, listOf(first))
        assertFalse(result.canSave)
        assertTrue(Problem(Problem.Level.ERROR, R.string.editor_err_duplicate) in result.problems)
    }

    @Test
    fun `editing a routine is not a duplicate of itself`() {
        val first = routine("a", Trigger.RideStart, heat)
        assertTrue(Problem(Problem.Level.ERROR, R.string.editor_err_duplicate) !in check.of(first.copy(name = "x"), listOf(first)).problems)
    }

    @Test
    fun `comfort settings after leaving the car warn that nobody is inside`() {
        val result = check.of(routine("a", Trigger.RideEnd, heat), emptyList())
        assertTrue(result.canSave)
        assertTrue(Problem(Problem.Level.WARNING, R.string.editor_warn_nobody_inside) in result.problems)
    }

    @Test
    fun `switching things off or lights after leaving is not called heating an empty car`() {
        // The warning must not say "켜져요" for anything comfort-class.
        val nobody = Problem(Problem.Level.WARNING, R.string.editor_warn_nobody_inside)
        val off = routine("a", Trigger.RideEnd, Action.Write(SignalIds.HVAC_AC, SignalValue.Bool(false)))
        val light = routine("b", Trigger.RideEnd, Action.Write(SignalIds.AMBIENT_LIGHT, SignalValue.Bool(true)))
        // Passenger heat runs only with someone in that seat (Occupancy.Occupied), so never in an empty car.
        val passenger = routine("c", Trigger.RideEnd, Action.Write(SignalIds.SEAT_HEAT_PASSENGER, SignalValue.of(1)))
        listOf(off, light, passenger).forEach { assertTrue(nobody !in check.of(it, emptyList()).problems, it.id) }
    }

    @Test
    fun `a switched-off copy or one with other limits is not a duplicate`() {
        // Only an identical routine that would run alongside doubles the actions.
        val first = routine("a", Trigger.RideStart, heat)
        val duplicate = Problem(Problem.Level.ERROR, R.string.editor_err_duplicate)
        assertTrue(duplicate !in check.of(first.copy(id = "b", enabled = false), listOf(first)).problems)
        assertTrue(duplicate !in check.of(first.copy(id = "b", limits = Limits(cooldownMinutes = 60)), listOf(first)).problems)
    }

    @Test
    fun `navigation says the place must be registered`() {
        val result = check.of(routine("a", Trigger.RideStart, home), emptyList())
        assertTrue(Problem(Problem.Level.WARNING, R.string.editor_warn_navi_place) in result.problems)
    }

    @Test
    fun `navigation that can start while driving says so`() {
        val result = check.of(routine("a", Trigger.RideElapsed(5), home), emptyList())
        assertTrue(Problem(Problem.Level.WARNING, R.string.editor_warn_navi_driving) in result.problems)
        // On getting in the car is before driving off: no such warning.
        assertTrue(Problem(Problem.Level.WARNING, R.string.editor_warn_navi_driving) !in check.of(routine("b", Trigger.RideStart, home), emptyList()).problems)
    }

    @Test
    fun `navigation on parking is not warned about as if it came while driving`() {
        val result = check.of(routine("a", EditorCatalog.trigger(TriggerChoice.PARKED), home), emptyList())
        assertTrue(Problem(Problem.Level.WARNING, R.string.editor_warn_navi_driving) !in result.problems)
    }
}
