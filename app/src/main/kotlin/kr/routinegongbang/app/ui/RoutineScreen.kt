package kr.routinegongbang.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kr.routinegongbang.app.BuildConfig
import kr.routinegongbang.app.R
import kr.routinegongbang.app.ui.editor.EditorScreen
import kr.routinegongbang.app.ui.editor.EditorTarget
import kr.routinegongbang.app.ui.editor.RoutineDraft
import kr.routinegongbang.app.ui.model.ActionSummary
import kr.routinegongbang.app.ui.model.ConditionSummary
import kr.routinegongbang.app.ui.model.Outcome
import kr.routinegongbang.app.ui.model.TripReceipt
import kr.routinegongbang.app.ui.model.Trust
import kr.routinegongbang.app.ui.model.StatusView
import kr.routinegongbang.app.ui.model.TriggerSummary
import kr.routinegongbang.app.platform.PermissionGate
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.routinegongbang.core.model.Action

private const val WIDE_LAYOUT_DP = 1000
private const val SIDE_PANEL_FRACTION = 0.34f

@Composable
internal fun RoutineScreen(state: UiState, commands: RoutineCommands?, debugCommands: DebugCommands?, realNowMs: Long) {
    val context = LocalContext.current
    val texts = remember(context, LocalConfiguration.current) { Texts(context) }
    var showDebug by rememberSaveable { mutableStateOf(false) }
    if (showDebug && state.debug.available) {
        DebugPanel(state.debug, debugCommands) { showDebug = false }
        return
    }
    var showDemo by rememberSaveable { mutableStateOf(false) }
    if (showDemo && state.debug.demo) {
        DemoPanel(state, debugCommands, commands, texts) { showDemo = false }
        return
    }
    // Open editor: NEW_ROUTINE or the id of the routine being edited; saveable, so a day/night switch keeps it open.
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    val target = editing?.let { key ->
        if (key == NEW_ROUTINE) EditorTarget.New else state.routines.firstOrNull { it.routine.id == key }?.let { EditorTarget.Existing(it.routine) }
    }
    // The routine being edited was deleted or replaced elsewhere: forget it, so it never pops open again later.
    // Only once the list is loaded, so a restored editor survives a service restart.
    LaunchedEffect(editing, target, state.status.engineReady) {
        if (editing != null && editing != NEW_ROUTINE && target == null && state.status.engineReady) editing = null
    }
    if (target != null) {
        EditorScreen(state, target, commands, texts) { editing = null }
        return
    }
    val canEdit = commands != null && state.status.parked
    val edit = Editing(canEdit, onNew = { editing = NEW_ROUTINE }, onEdit = { editing = it })
    // Run times come from the engine clock, which the debug panel may have moved forward.
    val nowMs = realNowMs + state.debug.clockOffsetMs
    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.Background)) {
        DotGrid()
        val panelWidth = maxWidth * SIDE_PANEL_FRACTION
        val narrowPanelMax = maxHeight * NARROW_PANEL_FRACTION
        if (maxWidth.value >= WIDE_LAYOUT_DP) {
            Row(Modifier.fillMaxSize()) {
                // Scrolls, so a suggestion card on a short display never pushes its buttons out of reach.
                Column(
                    Modifier.width(panelWidth).fillMaxHeight().background(Palette.Surface).rightHairline()
                        .verticalScroll(rememberScrollState()).padding(Sizes.Gutter),
                    verticalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    SidePanel(state, commands, texts, openDemo = { showDemo = true }) { showDebug = true }
                }
                RoutineList(state, commands, debugCommands, texts, nowMs, edit, { showDemo = true }, Modifier.weight(1f))
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier.heightIn(max = narrowPanelMax).verticalScroll(rememberScrollState())
                        .padding(horizontal = Sizes.Gutter, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    SidePanel(state, commands, texts, openDemo = { showDemo = true }) { showDebug = true }
                }
                RoutineList(state, commands, debugCommands, texts, nowMs, edit, { showDemo = true }, Modifier.weight(1f))
            }
        }
    }
}

private fun Modifier.rightHairline(): Modifier = drawBehind {
    val x = size.width - Sizes.Hairline.toPx() / 2
    drawLine(Palette.Outline, Offset(x, 0f), Offset(x, size.height), strokeWidth = Sizes.Hairline.toPx())
}

@Composable
private fun SidePanel(state: UiState, commands: RoutineCommands?, texts: Texts, openDemo: () -> Unit, openDebug: () -> Unit) {
    Brand()
    Telemetry(state.status, state.debug)
    state.suggestion?.let { SuggestionCard(it, commands, texts, notifyOnly = !state.status.controlOpen && !state.debug.fakeVehicle) }
    state.receipt?.let { ReceiptCard(it, state.routines, texts) }
    RecentLog(state, texts, max = SIDE_LOG_ENTRIES)
    if (state.debug.demo && !state.debug.fakeVehicle) QuietButton(stringResource(R.string.demo_open), openDemo)
    if (state.debug.available) QuietButton(stringResource(R.string.debug_open), openDebug, color = Palette.TextMuted)
}

@Composable
private fun Brand() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // The mark: an orange plate with a punched hole, the workshop's own tag.
        Box(
            Modifier.size(44.dp).background(Palette.Copper, RoundedCornerShape(Sizes.ControlRadius)).clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) { Box(Modifier.size(12.dp).background(Palette.Background, CircleShape)) }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, color = Palette.Text)
            MonoLabel("ROUTINE WORKSHOP · v${BuildConfig.VERSION_NAME}")
        }
    }
}

/** The car and the app at a glance, like the status strip of a bench instrument: key, light, plain words. */
@Composable
private fun Telemetry(status: VehicleStatus, debug: DebugState) {
    val driving = when {
        !status.engineReady -> stringResource(R.string.status_starting) to Palette.Neutral
        !status.drivingKnown -> stringResource(R.string.status_unknown) to Palette.Neutral
        status.parked -> stringResource(R.string.status_parked) to Palette.Done
        else -> stringResource(R.string.status_driving) to Palette.Pending
    }
    val vehicle = when {
        debug.fakeVehicle -> stringResource(R.string.status_fake_vehicle) to Palette.Notice
        status.vehicleConnected -> stringResource(R.string.status_vehicle_on) to Palette.Done
        else -> stringResource(R.string.status_vehicle_off) to Palette.Pending
    }
    val control = when {
        // The simulated car runs what the scripts allow, so "지금은 알림으로" would not be true there.
        debug.fakeVehicle -> stringResource(R.string.status_control_demo) to Palette.Notice
        status.controlOpen && status.emulator -> stringResource(R.string.status_control_emulator) to Palette.Copper
        else -> stringResource(R.string.status_control_closed) to Palette.Notice
    }
    Panel(padding = PaddingValues(horizontal = 24.dp, vertical = 20.dp), spacing = 14.dp) {
        TelemetryRow("DRIVE", driving)
        TelemetryRow("LINK", vehicle)
        TelemetryRow("CTRL", control)
        debug.capability?.let { TelemetryRow("SIM", stringResource(R.string.status_simulated_capability, capabilityLabel(it)) to Palette.Notice) }
    }
}

@Composable
private fun TelemetryRow(key: String, value: Pair<String, Color>) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        MonoLabel(key, modifier = Modifier.width(72.dp))
        Led(value.second, size = 9.dp)
        Spacer(Modifier.width(10.dp))
        Text(value.first, style = MaterialTheme.typography.titleMedium, color = Palette.Text)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SuggestionCard(suggestion: SuggestionItem, commands: RoutineCommands?, texts: Texts, notifyOnly: Boolean = false) {
    val latest by rememberUpdatedState(commands)
    LaunchedEffect(suggestion.tripId, suggestion.routine.id, latest != null) { latest?.suggestionShown() }
    Panel(background = Palette.CopperDim, border = Palette.Copper.copy(alpha = 0.6f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Led(Palette.Copper, size = 9.dp)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.suggestion_label), style = MaterialTheme.typography.labelLarge, color = Palette.Copper)
        }
        Text(
            suggestion.routine.actions.joinToString(" · ") { texts.action(ActionSummary.of(it)) },
            style = MaterialTheme.typography.titleLarge,
            color = Palette.Text,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Tag(texts.trigger(TriggerSummary.of(suggestion.routine.trigger)), code = "WHEN", accent = true)
            ConditionSummary.of(suggestion.routine.condition).forEach { Tag(texts.condition(it), code = "IF") }
        }
        Evidence(suggestion.supportingTrips)
        Text(stringResource(R.string.suggestion_body, suggestion.supportingTrips), style = MaterialTheme.typography.bodyMedium, color = Palette.TextMuted)
        // Adopted, it asks first until the driver has said yes three times (Trust): said before the tap, not after.
        // Control closed, a habit (a setting) is only told, never asked: then the note would not be true.
        if (!notifyOnly && RoutineDraft.askingFirst(suggestion.routine).policy.askBeforeRun) {
            Text(stringResource(R.string.suggestion_asks_first, Trust.EARNED_AFTER), style = MaterialTheme.typography.bodyMedium, color = Palette.Copper)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton(stringResource(R.string.suggestion_accept), { commands?.acceptSuggestion() }, enabled = commands != null)
            QuietButton(stringResource(R.string.suggestion_reject), { commands?.rejectSuggestion() }, enabled = commands != null, color = Palette.TextMuted)
        }
    }
}

/**
 * What the routines did on the drive that just ended, one line per routine, done or not and why: a till receipt, torn
 * along a perforated top edge. It stays until the next trip starts.
 */
@Composable
private fun ReceiptCard(receipt: TripReceipt, routines: List<RoutineItem>, texts: Texts) {
    Panel(modifier = Modifier.perforated(Palette.OutlineStrong), background = Palette.SurfaceRaised, spacing = 12.dp) {
        // The side panel is narrow: the title gets the whole line, the drive's time span goes under it.
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MonoLabel("RECEIPT", Palette.Copper)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.receipt_title), style = MaterialTheme.typography.titleMedium, color = Palette.Text)
            }
            MonoLabel(stringResource(R.string.receipt_span, texts.clock(receipt.startedAtMs), texts.clock(receipt.endedAtMs)))
        }
        receipt.lines.forEach { line ->
            val routine = routines.firstOrNull { it.routine.id == line.routineId }?.routine
            val color = when {
                line.outcomes.isEmpty() -> Palette.Neutral
                else -> Palette.of(line.outcomes.firstOrNull { it != Outcome.DONE } ?: Outcome.DONE)
            }
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 4.dp)) { Led(color, size = 7.dp) }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(line.routineName, style = MaterialTheme.typography.bodyLarge, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(texts.receiptLine(line, routine?.limits?.cooldownMinutes ?: 0), style = MaterialTheme.typography.labelMedium, color = Palette.TextMuted)
                }
            }
        }
    }
}

/** A row of small punched holes along the top edge, like the tear line of a till roll. Decorative only. */
private fun Modifier.perforated(color: Color): Modifier = drawWithCache {
    val radius = 2.5.dp.toPx()
    val step = 14.dp.toPx()
    val holes = generateSequence(step / 2) { it + step }.takeWhile { it < size.width }.map { Offset(it, 8.dp.toPx()) }.toList()
    onDrawWithContent {
        drawContent()
        holes.forEach { drawCircle(color, radius = radius, center = it) }
    }
}

/** One bar per trip the habit was seen on: the evidence, drawn as well as said. */
@Composable
private fun Evidence(trips: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clearAndSetSemantics {}) {
        repeat(trips.coerceAtMost(MAX_EVIDENCE_BARS)) { Box(Modifier.size(width = 12.dp, height = 22.dp).background(Palette.Copper, RoundedCornerShape(3.dp))) }
        Spacer(Modifier.width(8.dp))
        MonoLabel("×$trips", Palette.Copper)
    }
}

@Composable
private fun RoutineList(
    state: UiState,
    commands: RoutineCommands?,
    debugCommands: DebugCommands?,
    texts: Texts,
    nowMs: Long,
    edit: Editing,
    openDemo: () -> Unit,
    modifier: Modifier,
) {
    val missingPermissions by AppState.missingPermissions.collectAsStateWithLifecycle()
    LazyColumn(modifier.fillMaxHeight(), contentPadding = PaddingValues(Sizes.Gutter), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (state.debug.fakeVehicle) item { DemoBanner(onOpen = openDemo, onEnd = { debugCommands?.setFakeVehicle(false) }) }
        // No empty item when there is nothing to say: spacedBy would leave a gap.
        PermissionGate.banner(missingPermissions)?.let { banner -> item { PermissionBanner(banner) } }
        item {
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(bottom = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    MonoLabel("ROUTINES")
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(stringResource(R.string.routines_title), style = MaterialTheme.typography.displaySmall, color = Palette.Text)
                        Spacer(Modifier.width(16.dp))
                        Text(
                            stringResource(R.string.routines_counter, twoDigits(state.routines.count { it.routine.enabled }), twoDigits(state.routines.size)),
                            style = Mono.Value,
                            color = Palette.TextMuted,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                }
                // Always there, so the empty screen's "「+ 새 루틴」" can be found; it says when it cannot be used.
                Column(horizontalAlignment = Alignment.End) {
                    PrimaryButton(stringResource(R.string.editor_new), edit.onNew, enabled = edit.allowed)
                    if (!edit.allowed) Text(stringResource(R.string.editor_new_not_yet), style = MaterialTheme.typography.labelMedium, color = Palette.TextMuted)
                }
            }
        }
        // Not before the list is loaded: someone with routines would see "no routines" for a moment.
        if (state.routines.isEmpty() && state.status.engineReady) item { Intro(onDemo = openDemo.takeIf { state.debug.demo }) }
        itemsIndexed(state.routines, key = { _, it -> it.routine.id }) { index, item ->
            RoutineCard(index, item, canRun = commands != null && state.status.engineReady && state.status.parked, commands, texts, nowMs, edit, notifyOnly = !state.status.controlOpen && !state.debug.fakeVehicle)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoutineCard(index: Int, item: RoutineItem, canRun: Boolean, commands: RoutineCommands?, texts: Texts, nowMs: Long, edit: Editing, notifyOnly: Boolean) {
    val routine = item.routine
    val dim = if (routine.enabled) 1f else 0.5f
    Panel(modifier = Modifier.fillMaxWidth(), spacing = 18.dp) {
        Row(verticalAlignment = Alignment.Top) {
            // The list order is the priority (L1-5), so the number is information, not decoration.
            Text(twoDigits(index + 1), style = Mono.Index, color = if (routine.enabled) Palette.Copper else Palette.TextFaint, modifier = Modifier.width(INDEX_WIDTH))
            Column(Modifier.weight(1f).alpha(dim), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Two lines so a larger system font scale never hides the name.
                Text(routine.name, style = MaterialTheme.typography.titleLarge, color = Palette.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tag(texts.trigger(TriggerSummary.of(routine.trigger)), code = "WHEN", accent = true)
                    ConditionSummary.of(routine.condition).forEach { Tag(texts.condition(it), code = "IF") }
                    // How far an asking routine is from running by itself; one that waits keeps asking (Trust.canEarn).
                    // Control closed, a setting is only told and nothing is asked: only a route can be asked about then.
                    val asks = !notifyOnly || routine.actions.any { it is Action.SetDestination }
                    if (routine.policy.askBeforeRun && asks) {
                        val label = if (Trust.canEarn(routine)) stringResource(R.string.trust_asking, item.trustYes, Trust.EARNED_AFTER) else stringResource(R.string.editor_ask_first)
                        Tag(label, code = "ASK")
                    }
                }
            }
            Spacer(Modifier.width(16.dp))
            KitSwitch(routine.enabled, { commands?.setEnabled(routine.id, it) }, routine.name, enabled = commands != null)
        }
        ActionRail(item, texts, dim, notifyOnly, Modifier.padding(start = INDEX_WIDTH))
        // Newer than the last run: the trigger came and the routine did not run, and the card says why.
        item.lastSkip?.takeIf { skip -> item.lastRun.let { it == null || skip.atEpochMs >= it.atEpochMs } }?.let { skip ->
            Row(Modifier.padding(start = INDEX_WIDTH), verticalAlignment = Alignment.CenterVertically) {
                Led(Palette.Neutral, size = 8.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.skip_line, texts.skip(skip.kind, routine.limits.cooldownMinutes), texts.ago(skip.atEpochMs, nowMs)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.TextMuted,
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(Sizes.Hairline).background(Palette.Outline))
        Footer(item, canRun, commands, texts, nowMs, edit)
    }
}

/** The actions in order on a rail, each with how its last run went: the routine reads as a small program. */
@Composable
private fun ActionRail(item: RoutineItem, texts: Texts, dim: Float, notifyOnly: Boolean, modifier: Modifier) {
    val actions = item.routine.actions
    Column(modifier) {
        actions.forEachIndexed { index, action ->
            // The minimum first: an intrinsic height applied before it would fix the height and void the minimum.
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                RailNode(first = index == 0, last = index == actions.lastIndex)
                Spacer(Modifier.width(16.dp))
                // Only the action dims with a switched-off routine; how its last run went stays readable.
                Row(Modifier.weight(1f).alpha(dim), verticalAlignment = Alignment.CenterVertically) {
                    Text(texts.action(ActionSummary.of(action)), style = MaterialTheme.typography.bodyLarge, color = Palette.Text)
                    // Release build: control is not open yet, so a setting is only told.
                    if (notifyOnly && action is Action.Write) {
                        Spacer(Modifier.width(12.dp))
                        Badge(stringResource(R.string.action_notify_only), Palette.Notice)
                    }
                }
                item.lastRun?.results?.getOrNull(index)?.let { ResultPill(StatusView.of(it, action), texts, item.lastRun.dryRun) }
            }
        }
    }
}

@Composable
private fun RailNode(first: Boolean, last: Boolean) {
    Box(
        Modifier.width(20.dp).fillMaxHeight().clearAndSetSemantics {}.drawBehind {
            val x = size.width / 2
            val mid = size.height / 2
            val stroke = Sizes.Hairline.toPx() * 1.5f
            val radius = 6.dp.toPx()
            if (!first) drawLine(Palette.OutlineStrong, Offset(x, 0f), Offset(x, mid - radius), stroke)
            if (!last) drawLine(Palette.OutlineStrong, Offset(x, mid + radius), Offset(x, size.height), stroke)
            drawCircle(Palette.TextMuted, radius = radius, center = Offset(x, mid), style = Stroke(stroke))
        },
    )
}

@Composable
private fun ResultPill(view: StatusView, texts: Texts, dryRun: Boolean) {
    val color = Palette.of(view)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(horizontalAlignment = Alignment.End) {
            Text(texts.outcome(view.outcome), style = MaterialTheme.typography.labelLarge, color = color)
            texts.detail(view, dryRun)?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = Palette.TextMuted) }
        }
        Spacer(Modifier.width(6.dp))
        Led(color, size = 8.dp)
    }
}

@Composable
private fun Footer(item: RoutineItem, canRun: Boolean, commands: RoutineCommands?, texts: Texts, nowMs: Long, edit: Editing) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val run = item.lastRun
            Text(
                if (run == null) stringResource(R.string.never_run) else stringResource(R.string.last_run, texts.ago(run.atEpochMs, nowMs)),
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.TextMuted,
            )
            if (run?.simulated == true) Badge(stringResource(R.string.badge_simulated), Palette.Notice)
            if (run?.dryRun == true) Badge(stringResource(R.string.badge_dry_run), Palette.Copper)
        }
        if (edit.allowed) {
            QuietButton(stringResource(R.string.editor_edit), { edit.onEdit(item.routine.id) })
            Spacer(Modifier.width(8.dp))
        }
        Column(horizontalAlignment = Alignment.End) {
            GhostButton(stringResource(R.string.run_now), { commands?.runNow(item.routine.id) }, enabled = canRun)
            if (!canRun) Text(stringResource(R.string.run_now_parked_only), style = MaterialTheme.typography.labelMedium, color = Palette.TextMuted)
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = Modifier.border(Sizes.Hairline, color.copy(alpha = 0.6f), RoundedCornerShape(Sizes.TagRadius)).padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

/** Says what a denied permission costs, and asks again. */
@Composable
private fun PermissionBanner(banner: PermissionGate.Banner) {
    Panel(modifier = Modifier.fillMaxWidth(), background = Palette.SurfaceRaised, border = Palette.Pending.copy(alpha = 0.6f), spacing = 0.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Led(Palette.Pending)
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(
                    when (banner) {
                        PermissionGate.Banner.PARKING_UNKNOWN -> R.string.permission_banner
                        PermissionGate.Banner.SOME_CAR_DATA -> R.string.permission_some_car_data
                        PermissionGate.Banner.NOTIFICATIONS_OFF -> R.string.permission_notify_off
                    },
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = Palette.Text,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            PrimaryButton(stringResource(R.string.permission_fix), { AppState.fixPermissions?.invoke() })
        }
    }
}

/** The empty list explains the app in three lines before anything else is asked of the driver. */
@Composable
private fun Intro(onDemo: (() -> Unit)?) {
    Box(Modifier.fillMaxWidth().dashedBorder(Palette.OutlineStrong, Sizes.PanelRadius)) {
        Column(Modifier.padding(Sizes.CardPadding), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.intro_title), style = MaterialTheme.typography.titleLarge, color = Palette.Text)
            listOf(R.string.intro_what to Palette.Copper, R.string.intro_respect to Palette.Done, R.string.intro_never to Palette.Notice).forEach { (text, color) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Led(color, size = 8.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(text), style = MaterialTheme.typography.bodyLarge, color = Palette.Text)
                }
            }
            Text(stringResource(R.string.routines_empty), style = MaterialTheme.typography.bodyMedium, color = Palette.TextMuted)
            // The fastest way to see what the app does, for anyone without three mornings to spare.
            onDemo?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PrimaryButton(stringResource(R.string.demo_try), it)
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.demo_try_note), style = MaterialTheme.typography.bodyMedium, color = Palette.TextMuted)
                }
            }
        }
    }
}

/** Recent runs and skips in order: the side panel's story of what happened, and the demo's before/after. */
@Composable
internal fun RecentLog(state: UiState, texts: Texts, max: Int) {
    val entries = state.log.take(max)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MonoLabel("LOG")
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.log_title), style = MaterialTheme.typography.titleMedium, color = Palette.Text)
        }
        if (entries.isEmpty()) Text(stringResource(R.string.log_empty), style = MaterialTheme.typography.bodyMedium, color = Palette.TextMuted)
        entries.forEach { entry ->
            val routine = state.routines.firstOrNull { it.routine.id == entry.routineId }?.routine
            val summary = texts.logLine(entry, routine?.limits?.cooldownMinutes ?: 0)
            val color = if (entry.skip != null) Palette.Neutral else Palette.of(entry.outcomes.firstOrNull { it != Outcome.DONE } ?: Outcome.DONE)
            Row(verticalAlignment = Alignment.Top) {
                Text(texts.clock(entry.atEpochMs), style = Mono.Label, color = Palette.TextFaint, modifier = Modifier.width(LOG_TIME_WIDTH).padding(top = 4.dp))
                Box(Modifier.padding(top = 4.dp)) { Led(color, size = 7.dp) }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(entry.routineName, style = MaterialTheme.typography.bodyLarge, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(summary, style = MaterialTheme.typography.labelMedium, color = Palette.TextMuted)
                }
            }
        }
    }
}

@Composable
private fun capabilityLabel(override: CapabilityOverride): String = stringResource(
    when (override) {
        CapabilityOverride.FULL -> R.string.debug_cap_full
        CapabilityOverride.CONTROL_CLOSED -> R.string.debug_cap_closed
        CapabilityOverride.NO_PERMISSION -> R.string.debug_cap_none
    },
)

private const val NEW_ROUTINE = "__new__"
private const val MAX_EVIDENCE_BARS = 10
private const val NARROW_PANEL_FRACTION = 0.45f
private const val SIDE_LOG_ENTRIES = 5
private val LOG_TIME_WIDTH = 140.dp
private val INDEX_WIDTH = 56.dp

/** What the list may offer for editing: only while parked and connected to the service. */
private class Editing(val allowed: Boolean, val onNew: () -> Unit, val onEdit: (String) -> Unit)
