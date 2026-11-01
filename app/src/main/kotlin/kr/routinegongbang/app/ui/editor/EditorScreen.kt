package kr.routinegongbang.app.ui.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kr.routinegongbang.app.R
import kr.routinegongbang.app.ui.GhostButton
import kr.routinegongbang.app.ui.Led
import kr.routinegongbang.app.ui.Mono
import kr.routinegongbang.app.ui.MonoLabel
import kr.routinegongbang.app.ui.Palette
import kr.routinegongbang.app.ui.Panel
import kr.routinegongbang.app.ui.PrimaryButton
import kr.routinegongbang.app.ui.QuietButton
import kr.routinegongbang.app.ui.RoutineCommands
import kr.routinegongbang.app.ui.SectionHeader
import kr.routinegongbang.app.ui.Sizes
import kr.routinegongbang.app.ui.Texts
import kr.routinegongbang.app.ui.UiState
import kr.routinegongbang.app.ui.dashedBorder
import kr.routinegongbang.app.ui.DotGrid
import kr.routinegongbang.app.ui.twoDigits
import kr.routinegongbang.core.model.Routine
import kr.routinegongbang.core.model.RoutineJson
import kr.routinegongbang.core.model.SignalCatalog

/** What the editor was opened for: a new routine, or an existing one by id. */
sealed interface EditorTarget {
    data object New : EditorTarget
    data class Existing(val routine: Routine) : EditorTarget
}

/** AAOS recreates the activity on day/night switches; the draft survives as JSON. */
private val DraftSaver = Saver<Routine?, String>(
    save = { it?.let(RoutineJson::encode) ?: "" },
    restore = { text -> text.takeIf { it.isNotEmpty() }?.let { RoutineJson.decode(it).getOrNull() } },
)

/**
 * The editor, parked only (the activity is hidden while driving anyway): templates first, then one page of
 * 언제 / 조건 / 할 일 / 옵션 with a one-line summary and the save check in Korean, recomputed on every change.
 */
@Composable
internal fun EditorScreen(state: UiState, target: EditorTarget, commands: RoutineCommands?, texts: Texts, onClose: () -> Unit) {
    var draft by rememberSaveable(target, stateSaver = DraftSaver) { mutableStateOf((target as? EditorTarget.Existing)?.routine) }
    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        DotGrid()
        val current = draft
        when {
            !state.status.parked -> ParkedOnly(onClose)
            current == null -> TemplatePicker(texts, onPick = { draft = it }, onClose = onClose)
            else -> EditorForm(state, current, isNew = target is EditorTarget.New, commands, texts, onChange = { draft = it }, onClose = onClose)
        }
    }
}

@Composable
private fun ParkedOnly(onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(Sizes.Gutter), contentAlignment = Alignment.Center) {
        Panel(spacing = 20.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Led(Palette.Pending)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.editor_parked_only), style = MaterialTheme.typography.headlineMedium, color = Palette.Text)
            }
            QuietButton(stringResource(R.string.editor_cancel), onClose)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TemplatePicker(texts: Texts, onPick: (Routine) -> Unit, onClose: () -> Unit) {
    val names = Template.entries.associateWith { stringResource(templateName(it)) }
    val blankName = stringResource(R.string.editor_default_name)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Sizes.Gutter), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item { Header(stringResource(R.string.editor_title_new), onClose) }
        item { Text(stringResource(R.string.editor_start_from), style = MaterialTheme.typography.titleMedium, color = Palette.TextMuted) }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Template.entries.forEachIndexed { index, template ->
                    val routine = EditorCatalog.template(template, PENDING_ID, names.getValue(template))
                    // The id is made when picked, not on every recomposition.
                    TemplateCard("T" + twoDigits(index + 1), names.getValue(template), sentence(routine, texts)) { onPick(routine.copy(id = newId())) }
                }
                CustomCard(stringResource(R.string.editor_custom)) { onPick(RoutineDraft.blank(newId(), blankName)) }
            }
        }
    }
}

@Composable
private fun TemplateCard(code: String, title: String, detail: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = Palette.Surface,
        shape = RoundedCornerShape(Sizes.PanelRadius),
        border = BorderStroke(Sizes.Hairline, Palette.OutlineStrong),
        modifier = Modifier.width(TEMPLATE_WIDTH).heightIn(min = TEMPLATE_HEIGHT),
    ) {
        Column(Modifier.padding(Sizes.CardPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MonoLabel(code, Palette.Copper)
            Text(title, style = MaterialTheme.typography.titleLarge, color = Palette.Text)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = Palette.TextMuted)
        }
    }
}

/** Starting from nothing is drawn as an empty slot: dashed, with a plus. */
@Composable
private fun CustomCard(title: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = Palette.Background.copy(alpha = 0f),
        shape = RoundedCornerShape(Sizes.PanelRadius),
        modifier = Modifier.width(TEMPLATE_WIDTH).heightIn(min = TEMPLATE_HEIGHT).dashedBorder(Palette.OutlineStrong, Sizes.PanelRadius),
    ) {
        Column(Modifier.padding(Sizes.CardPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("+", style = Mono.Index, color = Palette.Copper)
            Text(title, style = MaterialTheme.typography.titleLarge, color = Palette.Text)
        }
    }
}

@Composable
private fun EditorForm(
    state: UiState,
    draft: Routine,
    isNew: Boolean,
    commands: RoutineCommands?,
    texts: Texts,
    onChange: (Routine) -> Unit,
    onClose: () -> Unit,
) {
    val check = remember { SaveCheck(SignalCatalog.default()) }
    val others = state.routines.map { it.routine }
    val result = remember(draft, others) { check.of(draft, others) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Sizes.Gutter), verticalArrangement = Arrangement.spacedBy(32.dp)) {
        item { Header(stringResource(if (isNew) R.string.editor_title_new else R.string.editor_title_edit), onClose) }
        item {
            OutlinedTextField(
                value = draft.name,
                onValueChange = { onChange(RoutineDraft.withName(draft, it)) },
                label = { Text(stringResource(R.string.editor_name)) },
                singleLine = true,
                textStyle = MaterialTheme.typography.titleLarge,
                shape = RoundedCornerShape(Sizes.ControlRadius),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Palette.Text,
                    unfocusedTextColor = Palette.Text,
                    focusedBorderColor = Palette.Copper,
                    unfocusedBorderColor = Palette.OutlineStrong,
                    focusedLabelColor = Palette.Copper,
                    unfocusedLabelColor = Palette.TextMuted,
                    cursorColor = Palette.Copper,
                    focusedContainerColor = Palette.Surface,
                    unfocusedContainerColor = Palette.Surface,
                ),
                modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.Touch),
            )
        }
        item { Section(1, stringResource(R.string.editor_when)) { TriggerEditor(draft, texts, onChange) } }
        item { Section(2, stringResource(R.string.editor_if)) { ConditionEditor(draft, texts, onChange) } }
        item {
            Section(3, stringResource(R.string.editor_do)) {
                ActionEditor(draft, texts, onChange)
                // Said where the actions are chosen, not discovered after the first run.
                if (!state.status.controlOpen && !state.debug.fakeVehicle && draft.actions.any { it is kr.routinegongbang.core.model.Action.Write }) {
                    Note(stringResource(R.string.editor_notify_only))
                }
            }
        }
        item { Section(4, stringResource(R.string.editor_options)) { OptionsEditor(draft, onChange) } }
        item { Section(5, stringResource(R.string.editor_summary)) { Summary(draft, result, texts) } }
        item { Buttons(draft, isNew, result, commands, onClose) }
    }
}

@Composable
private fun Header(title: String, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            MonoLabel("EDITOR")
            Text(title, style = MaterialTheme.typography.displaySmall, color = Palette.Text)
        }
        QuietButton(stringResource(R.string.editor_cancel), onClose, color = Palette.TextMuted)
    }
}

@Composable
internal fun Section(index: Int, title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionHeader(index, title)
        content()
    }
}

/** The sentence and what the check found, like a compiler's output under the code. */
@Composable
private fun Summary(draft: Routine, result: CheckResult, texts: Texts) {
    Panel(modifier = Modifier.fillMaxWidth(), background = Palette.SurfaceRaised, spacing = 14.dp) {
        Text(sentence(draft, texts, stringResource(R.string.editor_nothing)), style = MaterialTheme.typography.titleMedium, color = Palette.Text)
        if (result.problems.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Led(Palette.Done, size = 8.dp)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.editor_check_ok), style = MaterialTheme.typography.bodyLarge, color = Palette.Done)
            }
        }
        result.problems.forEach { problem ->
            val color = if (problem.level == Problem.Level.ERROR) Palette.Error else Palette.Pending
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 4.dp)) { Led(color, size = 8.dp) }
                Spacer(Modifier.width(10.dp))
                Text(stringResource(problem.message), style = MaterialTheme.typography.bodyLarge, color = color)
            }
        }
    }
}

@Composable
private fun Buttons(draft: Routine, isNew: Boolean, result: CheckResult, commands: RoutineCommands?, onClose: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        PrimaryButton(
            stringResource(R.string.editor_save),
            { if (commands?.saveRoutine(draft)?.canSave == true) onClose() },
            enabled = commands != null && result.canSave,
        )
        if (!isNew) {
            // A stray touch in a car must not wipe a routine: the first tap only asks.
            var confirming by rememberSaveable { mutableStateOf(false) }
            // Disarms by itself, so an old first tap never turns a later one into a deletion.
            LaunchedEffect(confirming) {
                if (confirming) {
                    delay(DELETE_CONFIRM_MS)
                    confirming = false
                }
            }
            GhostButton(
                stringResource(if (confirming) R.string.editor_delete_confirm else R.string.editor_delete),
                { if (confirming) { commands?.deleteRoutine(draft.id); onClose() } else confirming = true },
                enabled = commands != null,
                color = Palette.Error,
            )
        }
    }
}

internal fun sentence(routine: Routine, texts: Texts, nothing: String = ""): String = Sentence.join(
    trigger = texts.trigger(kr.routinegongbang.app.ui.model.TriggerSummary.of(routine.trigger)),
    conditions = RoutineDraft.conditions(routine).flatMap { kr.routinegongbang.app.ui.model.ConditionSummary.of(it) }.map { texts.condition(it) },
    actions = routine.actions.map { texts.action(kr.routinegongbang.app.ui.model.ActionSummary.of(it)) },
    nothing = nothing,
)

private fun templateName(template: Template): Int = when (template) {
    Template.COLD_MORNING -> R.string.template_cold_morning
    Template.HOT_DAY -> R.string.template_hot_day
    Template.RAIN -> R.string.template_rain
    Template.COMMUTE_HOME -> R.string.template_commute_home
    Template.WINDOW_CHECK -> R.string.template_window_check
}

private fun newId(): String = "user-${System.currentTimeMillis()}"

private const val PENDING_ID = "new"
private const val DELETE_CONFIRM_MS = 5_000L
private val TEMPLATE_WIDTH = 360.dp
private val TEMPLATE_HEIGHT = 168.dp
