package kr.routinegongbang.app.ui.editor

import kr.routinegongbang.core.model.Action
import kr.routinegongbang.core.model.CompareOp
import kr.routinegongbang.core.model.Condition
import kr.routinegongbang.core.model.Limits
import kr.routinegongbang.core.model.Origin
import kr.routinegongbang.core.model.Routine
import kr.routinegongbang.core.model.SignalIds
import kr.routinegongbang.core.model.SignalValue
import kr.routinegongbang.core.model.Trigger

/** Ready-made routines the editor starts from: templates come first. */
enum class Template { COLD_MORNING, HOT_DAY, RAIN, COMMUTE_HOME, WINDOW_CHECK }

/** The "when" a driver can pick; anything else (all-met, holds) is shown but only templates create it. */
enum class TriggerChoice { RIDE_START, PARKED, RIDE_END, RAIN, TIME, RIDE_ELAPSED, ARRIVED, BATTERY_LOW }

enum class ConditionChoice { WEEKDAYS, WEEKENDS, TIME_WINDOW, COLD, HOT, PASSENGER }

/** Comfort settings, navigation home, the window notice and waiting: nothing a routine may not write. */
enum class ActionChoice { STEERING_HEAT, SEAT_HEAT_DRIVER, SEAT_HEAT_PASSENGER, SEAT_VENT_DRIVER, AC, FAN, TEMP_DRIVER, DEFROSTER, RECIRCULATION, NAVIGATE_HOME, NOTIFY_WINDOW, WAIT }

/** The limited set of choices of the editor, each with a sensible default value. Pure. */
object EditorCatalog {

    private val ON = SignalValue.Bool(true)
    private const val HOME = "home"
    private const val WINDOW_NOTICE = "notify.window_open"

    fun trigger(choice: TriggerChoice): Trigger = when (choice) {
        TriggerChoice.RIDE_START -> Trigger.RideStart
        TriggerChoice.RIDE_END -> Trigger.RideEnd
        // Shifting to P: the driver is still inside and sees the screen, unlike after locking.
        // From a driving gear only, and P must hold a moment: P-R-P while parking or a glitch through UNKNOWN does not
        // count, and the parked state has settled when the routine plans.
        TriggerChoice.PARKED -> Trigger.SignalEdge(
            SignalIds.GEAR_SELECTION,
            from = setOf(SignalValue.Text("DRIVE"), SignalValue.Text("REVERSE"), SignalValue.Text("NEUTRAL")),
            to = setOf(SignalValue.Text("PARK")),
            holdSeconds = PARKED_HOLD_SECONDS,
        )
        // The wiper must keep going: one wipe to clear the glass is not rain.
        TriggerChoice.RAIN -> Trigger.SignalEdge(SignalIds.WIPER_FRONT_STATE, to = setOf(SignalValue.Text("ON")), holdSeconds = RAIN_HOLD_SECONDS)
        TriggerChoice.TIME -> Trigger.TimeOfDay(8, 0)
        TriggerChoice.RIDE_ELAPSED -> Trigger.RideElapsed(30)
        TriggerChoice.ARRIVED -> Trigger.NaviArrived
        TriggerChoice.BATTERY_LOW -> Trigger.Threshold(SignalIds.EV_SOC, CompareOp.LE, SignalValue.of(20))
    }

    /** Getting in, shifting to P, getting out: the moments a routine starts while the car is parked. */
    val parkedMoments: Set<Trigger> by lazy { setOf(Trigger.RideStart, Trigger.RideEnd, trigger(TriggerChoice.PARKED)) }

    fun choiceOf(trigger: Trigger): TriggerChoice? = when (trigger) {
        Trigger.RideStart -> TriggerChoice.RIDE_START
        Trigger.RideEnd -> TriggerChoice.RIDE_END
        Trigger.NaviArrived -> TriggerChoice.ARRIVED
        is Trigger.TimeOfDay -> TriggerChoice.TIME
        is Trigger.RideElapsed -> TriggerChoice.RIDE_ELAPSED
        is Trigger.SignalEdge -> listOf(TriggerChoice.RAIN, TriggerChoice.PARKED).firstOrNull { trigger == trigger(it) }
        is Trigger.Threshold ->
            TriggerChoice.BATTERY_LOW.takeIf { trigger.signal == SignalIds.EV_SOC && trigger.op == CompareOp.LE && trigger.holdSeconds == 0 }
        is Trigger.AllOf -> null
    }

    fun condition(choice: ConditionChoice): Condition = when (choice) {
        ConditionChoice.WEEKDAYS -> Condition.DayType(weekdays = true, weekends = false)
        ConditionChoice.WEEKENDS -> Condition.DayType(weekdays = false, weekends = true)
        ConditionChoice.TIME_WINDOW -> Condition.TimeWindow(17 * 60, 20 * 60)
        ConditionChoice.COLD -> Condition.Compare(SignalIds.CABIN_TEMPERATURE, CompareOp.LE, SignalValue.of(5))
        ConditionChoice.HOT -> Condition.Compare(SignalIds.CABIN_TEMPERATURE, CompareOp.GE, SignalValue.of(28))
        ConditionChoice.PASSENGER -> Condition.Compare(SignalIds.SEAT_OCCUPIED_PASSENGER, CompareOp.EQ, ON)
    }

    fun choiceOf(condition: Condition): ConditionChoice? = when (condition) {
        is Condition.DayType -> when {
            condition.weekdays && !condition.weekends -> ConditionChoice.WEEKDAYS
            condition.weekends && !condition.weekdays -> ConditionChoice.WEEKENDS
            else -> null
        }
        is Condition.TimeWindow -> ConditionChoice.TIME_WINDOW
        is Condition.Compare -> when {
            condition.signal == SignalIds.CABIN_TEMPERATURE && condition.op == CompareOp.LE -> ConditionChoice.COLD
            condition.signal == SignalIds.CABIN_TEMPERATURE && condition.op == CompareOp.GE -> ConditionChoice.HOT
            condition == condition(ConditionChoice.PASSENGER) -> ConditionChoice.PASSENGER
            else -> null
        }
        is Condition.And, is Condition.Or, is Condition.Not -> null
    }

    fun action(choice: ActionChoice): Action = when (choice) {
        ActionChoice.STEERING_HEAT -> Action.Write(SignalIds.STEERING_WHEEL_HEAT, SignalValue.of(1))
        ActionChoice.SEAT_HEAT_DRIVER -> Action.Write(SignalIds.SEAT_HEAT_DRIVER, SignalValue.of(1))
        ActionChoice.SEAT_HEAT_PASSENGER -> Action.Write(SignalIds.SEAT_HEAT_PASSENGER, SignalValue.of(1))
        ActionChoice.SEAT_VENT_DRIVER -> Action.Write(SignalIds.SEAT_VENT_DRIVER, SignalValue.of(1))
        ActionChoice.AC -> Action.Write(SignalIds.HVAC_AC, ON)
        ActionChoice.FAN -> Action.Write(SignalIds.HVAC_FAN_SPEED, SignalValue.of(3))
        ActionChoice.TEMP_DRIVER -> Action.Write(SignalIds.HVAC_TEMP_DRIVER, SignalValue.num(22.0))
        ActionChoice.DEFROSTER -> Action.Write(SignalIds.DEFROSTER_FRONT, ON)
        ActionChoice.RECIRCULATION -> Action.Write(SignalIds.RECIRCULATION, ON)
        ActionChoice.NAVIGATE_HOME -> Action.SetDestination(HOME)
        ActionChoice.NOTIFY_WINDOW -> Action.Notify(WINDOW_NOTICE)
        ActionChoice.WAIT -> Action.Wait(300)
    }

    fun choiceOf(action: Action): ActionChoice? = when (action) {
        is Action.Write -> WRITES[action.signal]
        is Action.SetDestination -> ActionChoice.NAVIGATE_HOME.takeIf { action.placeId == HOME }
        is Action.Notify -> ActionChoice.NOTIFY_WINDOW.takeIf { action.textRes == WINDOW_NOTICE }
        is Action.Wait -> ActionChoice.WAIT
        is Action.Remind -> null
    }

    private val WRITES = ActionChoice.entries.mapNotNull { choice -> (action(choice) as? Action.Write)?.let { it.signal to choice } }.toMap()

    /** [name] comes from the screen, in the driver's language. */
    fun template(template: Template, id: String, name: String): Routine {
        fun write(choice: ActionChoice, value: SignalValue) = (action(choice) as Action.Write).copy(value = value)
        val once = Limits(oncePerTrip = true)
        return when (template) {
            // Warm up quickly, then step down so it never stays on high.
            Template.COLD_MORNING -> Routine(
                id = id, name = name, trigger = Trigger.RideStart, condition = condition(ConditionChoice.COLD), limits = once,
                actions = listOf(
                    write(ActionChoice.STEERING_HEAT, SignalValue.of(2)), write(ActionChoice.SEAT_HEAT_DRIVER, SignalValue.of(2)),
                    Action.Wait(600),
                    write(ActionChoice.STEERING_HEAT, SignalValue.of(1)), write(ActionChoice.SEAT_HEAT_DRIVER, SignalValue.of(1)),
                ),
            )
            Template.HOT_DAY -> Routine(
                id = id, name = name, trigger = Trigger.RideStart, condition = condition(ConditionChoice.HOT), limits = once,
                actions = listOf(action(ActionChoice.AC), write(ActionChoice.SEAT_VENT_DRIVER, SignalValue.of(2))),
            )
            // Only the defroster: recirculation is coupled with it in some cars.
            Template.RAIN -> Routine(
                id = id, name = name, trigger = trigger(TriggerChoice.RAIN), limits = Limits(cooldownMinutes = 30),
                actions = listOf(action(ActionChoice.DEFROSTER)),
            )
            Template.COMMUTE_HOME -> Routine(
                id = id, name = name, trigger = Trigger.RideStart, limits = once,
                condition = Condition.And(listOf(condition(ConditionChoice.WEEKDAYS), condition(ConditionChoice.TIME_WINDOW))),
                actions = listOf(action(ActionChoice.NAVIGATE_HOME)),
            )
            Template.WINDOW_CHECK -> Routine(
                id = id, name = name, trigger = trigger(TriggerChoice.PARKED),
                condition = Condition.Compare(SignalIds.WINDOW_POSITION_DRIVER, CompareOp.GT, SignalValue.of(0)),
                actions = listOf(action(ActionChoice.NOTIFY_WINDOW)),
                // Parking at a light and again at home within minutes need not say it twice.
                limits = Limits(cooldownMinutes = WINDOW_CHECK_COOLDOWN_MINUTES),
            )
        }.copy(origin = Origin.TEMPLATE).let(RoutineDraft::askingFirst)
    }
}

private const val PARKED_HOLD_SECONDS = 3
private const val RAIN_HOLD_SECONDS = 10
private const val WINDOW_CHECK_COOLDOWN_MINUTES = 10
