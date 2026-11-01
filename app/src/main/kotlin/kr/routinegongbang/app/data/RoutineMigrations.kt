package kr.routinegongbang.app.data

import kr.routinegongbang.core.model.Action
import kr.routinegongbang.core.model.Origin
import kr.routinegongbang.core.model.Routine
import kr.routinegongbang.core.model.SignalIds
import kr.routinegongbang.core.model.SignalValue
import kr.routinegongbang.core.model.Trigger

/** Brings routines saved by older versions up to date when they are loaded. Pure. */
internal object RoutineMigrations {

    /**
     * Notices whose text no longer exists. In older versions the rain template's fixed "켰어요" claimed results it could not know;
     * what a run did is now announced from its results (RunSummary).
     */
    private val RETIRED_NOTICES = setOf("routine_rain_done")

    /**
     * "비 올 때" in older versions fired on one wipe; it now needs the wiper to keep going 10 s, as the editor's rain does
     * (EditorCatalog). Saved with the old trigger, a routine would still fire on one wipe.
     */
    private val OLD_RAIN = Trigger.SignalEdge(SignalIds.WIPER_FRONT_STATE, to = setOf(SignalValue.Text("ON")))
    private const val RAIN_HOLD_SECONDS = 10

    /**
     * The old rain template also switched to recirculation, which fogs the glass while defrosting (the
     * factory defog takes outside air). Only the template's; a driver's own routine keeps its choice.
     */
    private val OLD_RAIN_RECIRCULATION = Action.Write(SignalIds.RECIRCULATION, SignalValue.Bool(true))

    fun apply(routines: List<Routine>): List<Routine> = routines.map { routine ->
        val oldRainTemplate = routine.origin == Origin.TEMPLATE && routine.trigger == OLD_RAIN
        val kept = routine.actions.filterNot { (it is Action.Notify && it.textRes in RETIRED_NOTICES) || (oldRainTemplate && it == OLD_RAIN_RECIRCULATION) }
        // Never leave a routine with nothing to do: that one is better shown with its old notice than rejected.
        val actions = if (kept.size == routine.actions.size || kept.isEmpty()) routine.actions else kept
        val trigger = if (routine.trigger == OLD_RAIN) OLD_RAIN.copy(holdSeconds = RAIN_HOLD_SECONDS) else routine.trigger
        if (actions == routine.actions && trigger == routine.trigger) routine else routine.copy(actions = actions, trigger = trigger)
    }
}
