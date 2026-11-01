package kr.routinegongbang.app.data

import kr.routinegongbang.app.ui.editor.EditorCatalog
import kr.routinegongbang.app.ui.editor.TriggerChoice
import kr.routinegongbang.core.model.Action
import kr.routinegongbang.core.model.Routine
import kr.routinegongbang.core.model.SignalIds
import kr.routinegongbang.core.model.SignalValue
import kr.routinegongbang.core.model.Trigger
import kotlin.test.Test
import kotlin.test.assertEquals

/** Routines saved by older versions keep working after a wording change. */
class RoutineMigrationsTest {

    private val defrost = Action.Write(SignalIds.DEFROSTER_FRONT, SignalValue.Bool(true))

    @Test
    fun `the retired fixed rain notice is removed from saved routines`() {
        // The notice claimed results it could not know and its text is gone, so it would show as a raw key.
        val old = Routine(id = "rain", name = "비 오는 날", trigger = Trigger.NaviArrived, actions = listOf(defrost, Action.Notify("routine_rain_done")))
        assertEquals(listOf<Action>(defrost), RoutineMigrations.apply(listOf(old)).single().actions)
    }

    @Test
    fun `a rain routine saved before 10_30 waits for the wiper to keep going, like the editor's rain now`() {
        // Saved with the old single-wipe trigger, it would still fire on one wipe and the editor would no longer
        // recognise it as "비 올 때".
        val oldRain = Trigger.SignalEdge(SignalIds.WIPER_FRONT_STATE, to = setOf(SignalValue.Text("ON")))
        val saved = Routine(id = "rain", name = "비 오는 날", trigger = oldRain, actions = listOf(defrost))
        val migrated = RoutineMigrations.apply(listOf(saved)).single().trigger
        assertEquals(EditorCatalog.trigger(TriggerChoice.RAIN), migrated)
        assertEquals(TriggerChoice.RAIN, EditorCatalog.choiceOf(migrated))
    }

    @Test
    fun `the old rain template also loses its recirculation, which fogs the glass while defrosting`() {
        // The factory defog switches to outside air; recirculation makes fog worse.
        val oldRain = Trigger.SignalEdge(SignalIds.WIPER_FRONT_STATE, to = setOf(SignalValue.Text("ON")))
        val recirculate = Action.Write(SignalIds.RECIRCULATION, SignalValue.Bool(true))
        val template = Routine(id = "rain", name = "비 오는 날", trigger = oldRain, actions = listOf(defrost, recirculate), origin = kr.routinegongbang.core.model.Origin.TEMPLATE)
        assertEquals(listOf<Action>(defrost), RoutineMigrations.apply(listOf(template)).single().actions)
        // A routine the driver made keeps what the driver chose.
        val own = template.copy(origin = kr.routinegongbang.core.model.Origin.USER)
        assertEquals(listOf<Action>(defrost, recirculate), RoutineMigrations.apply(listOf(own)).single().actions)
    }

    @Test
    fun `everything else is left exactly as it was`() {
        val other = Routine(id = "w", name = "창문", trigger = Trigger.RideEnd, actions = listOf(Action.Notify("notify.window_open")))
        assertEquals(listOf(other), RoutineMigrations.apply(listOf(other)))
    }

    @Test
    fun `a routine that would be left with nothing to do keeps its notice`() {
        val onlyNotice = Routine(id = "n", name = "n", trigger = Trigger.NaviArrived, actions = listOf(Action.Notify("routine_rain_done")))
        assertEquals(listOf(onlyNotice), RoutineMigrations.apply(listOf(onlyNotice)))
    }
}
