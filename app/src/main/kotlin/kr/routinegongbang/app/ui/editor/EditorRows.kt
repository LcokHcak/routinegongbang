package kr.routinegongbang.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kr.routinegongbang.app.R
import kr.routinegongbang.app.ui.KitSwitch
import kr.routinegongbang.app.ui.Mono
import kr.routinegongbang.app.ui.MonoLabel
import kr.routinegongbang.app.ui.Palette
import kr.routinegongbang.app.ui.QuietButton
import kr.routinegongbang.app.ui.Sizes
import kr.routinegongbang.app.ui.StepperControl
import kr.routinegongbang.app.ui.Texts
import kr.routinegongbang.app.ui.twoDigits
import kr.routinegongbang.app.ui.model.ActionSummary
import kr.routinegongbang.app.ui.model.ConditionSummary
import kr.routinegongbang.app.ui.model.TriggerSummary
import kr.routinegongbang.core.model.Action
import kr.routinegongbang.core.model.Condition
import kr.routinegongbang.core.model.Routine
import kr.routinegongbang.core.model.Trigger

@Composable
internal fun TriggerEditor(draft: Routine, texts: Texts, onChange: (Routine) -> Unit) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    val trigger = draft.trigger
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().row(accent = true), verticalAlignment = Alignment.CenterVertically) {
            MonoLabel("WHEN", Palette.Copper, Modifier.width(CODE_WIDTH))
            Text(texts.trigger(TriggerSummary.of(trigger)), style = MaterialTheme.typography.titleLarge, color = Palette.Text, modifier = Modifier.weight(1f))
            if (trigger is Trigger.TimeOfDay || trigger is Trigger.RideElapsed || trigger is Trigger.Threshold) {
                StepperControl(
                    value = null,
                    onDown = { onChange(RoutineDraft.withTrigger(draft, RoutineDraft.stepTrigger(trigger, up = false))) },
                    onUp = { onChange(RoutineDraft.withTrigger(draft, RoutineDraft.stepTrigger(trigger, up = true))) },
                )
                Spacer(Modifier.width(8.dp))
            }
            QuietButton(stringResource(R.string.editor_change), { choosing = !choosing })
        }
        if (EditorCatalog.choiceOf(trigger) == null) Note(stringResource(R.string.editor_fixed_trigger))
        if (choosing) {
            Choices(TriggerChoice.entries.map { it to stringResource(triggerLabel(it)) }) { choice ->
                onChange(RoutineDraft.withTrigger(draft, EditorCatalog.trigger(choice)))
                choosing = false
            }
        }
    }
}

@Composable
internal fun ConditionEditor(draft: Routine, texts: Texts, onChange: (Routine) -> Unit) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    val conditions = RoutineDraft.conditions(draft)
    fun replaced(index: Int, c: Condition) = RoutineDraft.withConditions(draft, conditions.mapIndexed { i, old -> if (i == index) c else old })
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        conditions.forEachIndexed { index, condition ->
            Row(Modifier.fillMaxWidth().row(), verticalAlignment = Alignment.CenterVertically) {
                MonoLabel(if (index == 0) "IF" else "AND", modifier = Modifier.width(CODE_WIDTH))
                Text(conditionText(condition, texts), style = MaterialTheme.typography.titleMedium, color = Palette.Text, modifier = Modifier.weight(1f))
                when (condition) {
                    is Condition.TimeWindow -> {
                        Label(stringResource(R.string.editor_from))
                        StepperControl(
                            value = null,
                            onDown = { onChange(replaced(index, RoutineDraft.stepCondition(condition, up = false))) },
                            onUp = { onChange(replaced(index, RoutineDraft.stepCondition(condition, up = true))) },
                        )
                        Label(stringResource(R.string.editor_until))
                        StepperControl(
                            value = null,
                            onDown = { onChange(replaced(index, RoutineDraft.stepCondition(condition, up = false, end = true))) },
                            onUp = { onChange(replaced(index, RoutineDraft.stepCondition(condition, up = true, end = true))) },
                        )
                    }
                    is Condition.Compare -> if (EditorCatalog.choiceOf(condition).let { it == ConditionChoice.COLD || it == ConditionChoice.HOT }) {
                        StepperControl(
                            value = null,
                            onDown = { onChange(replaced(index, RoutineDraft.stepCondition(condition, up = false))) },
                            onUp = { onChange(replaced(index, RoutineDraft.stepCondition(condition, up = true))) },
                        )
                    }
                    else -> Unit
                }
                Spacer(Modifier.width(8.dp))
                QuietButton(stringResource(R.string.editor_remove), { onChange(RoutineDraft.withConditions(draft, conditions.filterIndexed { i, _ -> i != index })) }, color = Palette.TextMuted)
            }
        }
        QuietButton(stringResource(R.string.editor_add_condition), { choosing = !choosing })
        if (choosing) {
            val present = conditions.mapNotNull { EditorCatalog.choiceOf(it) }.toSet()
            // Never offer the opposite of one already chosen: weekdays with weekends, cold with hot.
            val opposite = mapOf(
                ConditionChoice.WEEKDAYS to ConditionChoice.WEEKENDS, ConditionChoice.WEEKENDS to ConditionChoice.WEEKDAYS,
                ConditionChoice.COLD to ConditionChoice.HOT, ConditionChoice.HOT to ConditionChoice.COLD,
            )
            val excluded = present + present.mapNotNull { opposite[it] }
            Choices(ConditionChoice.entries.filter { it !in excluded }.map { it to stringResource(conditionLabel(it)) }) { choice ->
                onChange(RoutineDraft.withConditions(draft, conditions + EditorCatalog.condition(choice)))
                choosing = false
            }
        }
    }
}

@Composable
internal fun ActionEditor(draft: Routine, texts: Texts, onChange: (Routine) -> Unit) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        draft.actions.forEachIndexed { index, action ->
            Row(Modifier.fillMaxWidth().row(), verticalAlignment = Alignment.CenterVertically) {
                // The step number is the order the car will see.
                Text(twoDigits(index + 1), style = Mono.Value, color = Palette.Copper, modifier = Modifier.width(CODE_WIDTH))
                Text(texts.action(ActionSummary.of(action)), style = MaterialTheme.typography.titleMedium, color = Palette.Text, modifier = Modifier.weight(1f))
                if (action is Action.Write || action is Action.Wait) {
                    StepperControl(
                        value = null,
                        onDown = { onChange(RoutineDraft.replaceAction(draft, index, RoutineDraft.stepAction(action, up = false))) },
                        onUp = { onChange(RoutineDraft.replaceAction(draft, index, RoutineDraft.stepAction(action, up = true))) },
                    )
                    Spacer(Modifier.width(8.dp))
                }
                // A gap between neighbours, so a finger aimed at 아래로 does not land on 빼기.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuietButton(stringResource(R.string.editor_up), { onChange(RoutineDraft.moveAction(draft, index, up = true)) }, enabled = index > 0, color = Palette.TextMuted)
                    QuietButton(stringResource(R.string.editor_down), { onChange(RoutineDraft.moveAction(draft, index, up = false)) }, enabled = index < draft.actions.lastIndex, color = Palette.TextMuted)
                    QuietButton(stringResource(R.string.editor_remove), { onChange(RoutineDraft.removeAction(draft, index)) }, color = Palette.TextMuted)
                }
            }
        }
        QuietButton(stringResource(R.string.editor_add_action), { choosing = !choosing })
        if (choosing) {
            Choices(ActionChoice.entries.map { it to actionLabel(it, texts) }) { choice ->
                onChange(RoutineDraft.addAction(draft, EditorCatalog.action(choice)))
                choosing = false
            }
        }
    }
}

@Composable
internal fun OptionsEditor(draft: Routine, onChange: (Routine) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Toggle(stringResource(R.string.editor_once_per_trip), null, draft.limits.oncePerTrip) { onChange(RoutineDraft.withOncePerTrip(draft, it)) }
        Toggle(stringResource(R.string.editor_ask_first), stringResource(R.string.editor_ask_first_note), draft.policy.askBeforeRun) {
            onChange(RoutineDraft.withAskBeforeRun(draft, it))
        }
    }
}

@Composable
private fun Toggle(label: String, note: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().row(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = Palette.Text)
            note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.TextMuted) }
        }
        KitSwitch(checked, onChange, label)
    }
}

/** One editable line: a hairline box at touch height; the trigger's is tinted, it is what starts everything. */
private fun Modifier.row(accent: Boolean = false): Modifier {
    val shape = RoundedCornerShape(Sizes.ControlRadius)
    return heightIn(min = ROW_HEIGHT)
        .clip(shape)
        .background(if (accent) Palette.CopperDim else Palette.Surface)
        .border(Sizes.Hairline, if (accent) Palette.Copper.copy(alpha = 0.45f) else Palette.Outline, shape)
        .padding(horizontal = 20.dp, vertical = 6.dp)
}

@Composable
private fun Label(text: String) {
    Spacer(Modifier.width(12.dp))
    Text(text, style = MaterialTheme.typography.labelMedium, color = Palette.TextMuted)
    Spacer(Modifier.width(8.dp))
}

@Composable
internal fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.TextMuted)
}

/** The choices of a list, as keys on a panel. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Choices(options: List<Pair<T, String>>, onPick: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        options.forEach { (value, label) ->
            OutlinedButton(
                onClick = { onPick(value) },
                shape = RoundedCornerShape(Sizes.ControlRadius),
                border = BorderStroke(Sizes.Hairline, Palette.OutlineStrong),
                contentPadding = PaddingValues(horizontal = 22.dp),
                modifier = Modifier.heightIn(min = Sizes.Touch),
            ) { Text(label, style = MaterialTheme.typography.labelLarge, color = Palette.Text) }
        }
    }
}

private fun conditionText(condition: Condition, texts: Texts): String =
    ConditionSummary.of(condition).joinToString(" · ") { texts.condition(it) }

private fun triggerLabel(choice: TriggerChoice): Int = when (choice) {
    TriggerChoice.RIDE_START -> R.string.choice_ride_start
    TriggerChoice.RIDE_END -> R.string.choice_ride_end
    TriggerChoice.PARKED -> R.string.choice_parked
    TriggerChoice.RAIN -> R.string.choice_rain
    TriggerChoice.TIME -> R.string.choice_time
    TriggerChoice.RIDE_ELAPSED -> R.string.choice_ride_elapsed
    TriggerChoice.ARRIVED -> R.string.choice_arrived
    TriggerChoice.BATTERY_LOW -> R.string.choice_battery_low
}

private fun conditionLabel(choice: ConditionChoice): Int = when (choice) {
    ConditionChoice.WEEKDAYS -> R.string.choice_weekdays
    ConditionChoice.WEEKENDS -> R.string.choice_weekends
    ConditionChoice.TIME_WINDOW -> R.string.choice_time_window
    ConditionChoice.COLD -> R.string.choice_cold
    ConditionChoice.HOT -> R.string.choice_hot
    ConditionChoice.PASSENGER -> R.string.choice_passenger
}

@Composable
private fun actionLabel(choice: ActionChoice, texts: Texts): String = when (val action = EditorCatalog.action(choice)) {
    is Action.Write -> texts.signal(action.signal)
    else -> stringResource(
        when (choice) {
            ActionChoice.NAVIGATE_HOME -> R.string.choice_navigate_home
            ActionChoice.NOTIFY_WINDOW -> R.string.choice_notify_window
            else -> R.string.choice_wait
        },
    )
}

private val CODE_WIDTH = 64.dp
private val ROW_HEIGHT = 76.dp
