package kr.routinegongbang.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.routinegongbang.app.R
import kr.routinegongbang.app.service.EngineLog

/** Demo scripts shipped in core resources; fake mode plays them on the live engine. */
private val SCRIPTS = listOf(
    "d1_rain.json" to "D1 비",
    "d2_lock_check.json" to "D2 창문",
    "d3_charge_done.json" to "D3 충전",
    "d4_habit.json" to "D4 습관",
    "d5_capability.json" to "D5 권한",
    "d6_commute_home.json" to "D6 퇴근",
)

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS

/**
 * Debug panel: controls on the left, the engine's event stream with
 * its decision reasons on the right. Shown only in debug builds; every simulated result is labelled "모의".
 */
@Composable
internal fun DebugPanel(state: DebugState, commands: DebugCommands?, onClose: () -> Unit) {
    val lines by EngineLog.lines.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxSize().background(Palette.Background)) {
        Column(
            Modifier.width(520.dp).fillMaxHeight().background(Palette.Surface).verticalScroll(rememberScrollState()).padding(Sizes.Gutter),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.debug_title), style = MaterialTheme.typography.headlineMedium, color = Palette.Text, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose, modifier = Modifier.heightIn(min = Sizes.Touch)) {
                    Text(stringResource(R.string.debug_close), style = MaterialTheme.typography.labelLarge, color = Palette.Copper)
                }
            }
            ClockSection(state, commands)
            CapabilitySection(state, commands)
            VehicleSection(state, commands)
            PlaybackSection(state, commands)
        }
        EventStream(lines, Modifier.weight(1f))
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Palette.TextMuted)
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClockSection(state: DebugState, commands: DebugCommands?) {
    Section(stringResource(R.string.debug_clock, offsetLabel(state.clockOffsetMs))) {
        // Only the fake car's data may hold future times; a real car's cooldowns and habit log must not.
        if (!state.fakeVehicle) {
            Text(stringResource(R.string.debug_clock_fake_only), style = MaterialTheme.typography.bodyMedium, color = Palette.TextMuted)
            return@Section
        }
        val idle = !state.playing
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Toggle(stringResource(R.string.debug_plus_10m), selected = false, enabled = idle) { commands?.advanceClock(10 * MINUTE_MS) }
            Toggle(stringResource(R.string.debug_plus_1h), selected = false, enabled = idle) { commands?.advanceClock(HOUR_MS) }
            Toggle(stringResource(R.string.debug_plus_1d), selected = false, enabled = idle) { commands?.advanceClock(DAY_MS) }
            Toggle(stringResource(R.string.debug_reset), selected = false, enabled = idle) { commands?.resetClock() }
        }
        Text(stringResource(R.string.debug_reset_note), style = MaterialTheme.typography.labelMedium, color = Palette.TextMuted)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CapabilitySection(state: DebugState, commands: DebugCommands?) {
    Section(stringResource(R.string.debug_capability)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Toggle(stringResource(R.string.debug_cap_real), state.capability == null) { commands?.setCapability(null) }
            Toggle(stringResource(R.string.debug_cap_full), state.capability == CapabilityOverride.FULL) { commands?.setCapability(CapabilityOverride.FULL) }
            Toggle(stringResource(R.string.debug_cap_closed), state.capability == CapabilityOverride.CONTROL_CLOSED) {
                commands?.setCapability(CapabilityOverride.CONTROL_CLOSED)
            }
            Toggle(stringResource(R.string.debug_cap_none), state.capability == CapabilityOverride.NO_PERMISSION) {
                commands?.setCapability(CapabilityOverride.NO_PERMISSION)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VehicleSection(state: DebugState, commands: DebugCommands?) {
    Section(stringResource(R.string.debug_vehicle)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Toggle(stringResource(R.string.debug_vehicle_real), !state.fakeVehicle, enabled = !state.playing) { if (state.fakeVehicle) commands?.setFakeVehicle(false) }
            Toggle(stringResource(R.string.debug_vehicle_fake), state.fakeVehicle, enabled = !state.playing) { if (!state.fakeVehicle) commands?.setFakeVehicle(true) }
        }
        Text(stringResource(R.string.debug_vehicle_note), style = MaterialTheme.typography.labelMedium, color = Palette.TextMuted)
        if (!state.fakeVehicle) {
            Toggle(stringResource(R.string.debug_self_test), selected = false, enabled = !state.playing) { commands?.selfTest() }
            Text(stringResource(R.string.debug_self_test_note), style = MaterialTheme.typography.labelMedium, color = Palette.TextMuted)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaybackSection(state: DebugState, commands: DebugCommands?) {
    Section(stringResource(R.string.debug_scripts)) {
        if (!state.fakeVehicle) {
            Text(stringResource(R.string.debug_scripts_fake_only), style = MaterialTheme.typography.bodyMedium, color = Palette.TextMuted)
            return@Section
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SCRIPTS.forEach { (file, label) -> Toggle(label, state.playback?.script == file, enabled = !state.playing) { commands?.play(file) } }
        }
        state.playback?.let { playback -> PlaybackStatus(playback, commands) }
    }
}

@Composable
private fun PlaybackStatus(playback: PlaybackState, commands: DebugCommands?) {
    val text = when (playback.phase) {
        PlaybackState.Phase.RUNNING -> stringResource(R.string.debug_playing, playback.step + 1, playback.total)
        PlaybackState.Phase.PAUSED_FOR_SUGGESTION -> stringResource(R.string.debug_paused)
        PlaybackState.Phase.FINISHED -> stringResource(R.string.debug_finished)
    }
    Text(text, style = MaterialTheme.typography.bodyMedium, color = if (playback.problems.isEmpty()) Palette.Text else Palette.Error)
    playback.problems.take(3).forEach { Text(it, style = MaterialTheme.typography.labelMedium, color = Palette.Error) }
    if (playback.phase == PlaybackState.Phase.PAUSED_FOR_SUGGESTION) {
        Toggle(stringResource(R.string.debug_resume), selected = true) { commands?.resume() }
    }
}

@Composable
private fun Toggle(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(2.dp, if (selected) Palette.Copper else Palette.Outline),
        contentPadding = PaddingValues(horizontal = 18.dp),
        modifier = Modifier.heightIn(min = Sizes.Touch),
    ) { Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) Palette.Copper else Palette.Text) }
}

@Composable
private fun EventStream(lines: List<String>, modifier: Modifier) {
    Column(modifier.fillMaxHeight().padding(Sizes.Gutter)) {
        Text(stringResource(R.string.debug_events), style = MaterialTheme.typography.titleLarge, color = Palette.Text)
        Spacer(Modifier.heightIn(min = 16.dp))
        Surface(color = Palette.Surface, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxSize()) {
            LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(lines.asReversed()) { _, line ->
                    Text(line, fontFamily = FontFamily.Monospace, fontSize = 16.sp, lineHeight = 22.sp, color = colorOf(line), modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** Decision lines carry their meaning in colour too: ran, blocked, failed, debug. */
private fun colorOf(line: String): Color = when {
    line.startsWith("RAN") || line.startsWith("RESULT") && "CONFIRMED" in line -> Palette.Done
    line.startsWith("BLOCKED") || line.startsWith("CONDITION") || "SENT_UNCONFIRMED" in line -> Palette.Pending
    "FAILED" in line || line.startsWith("SERVICE ERROR") -> Palette.Error
    line.startsWith("DEBUG") -> Palette.Copper
    line.startsWith("USER") || line.startsWith("REMINDER") || line.startsWith("TRIP") -> Palette.Notice
    else -> Palette.TextMuted
}

private fun offsetLabel(offsetMs: Long): String {
    if (offsetMs == 0L) return "±0"
    val days = offsetMs / DAY_MS
    val hours = (offsetMs % DAY_MS) / HOUR_MS
    val minutes = (offsetMs % HOUR_MS) / MINUTE_MS
    return buildString {
        append("+")
        if (days > 0) append("${days}일 ")
        if (hours > 0) append("${hours}시간 ")
        if (minutes > 0 || (days == 0L && hours == 0L)) append("${minutes}분")
    }.trim()
}
