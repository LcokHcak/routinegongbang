package kr.routinegongbang.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kr.routinegongbang.app.R

/** One story the demo can play on the simulated car; the scripts live in core's resources. */
internal data class DemoScenario(val script: String, val title: Int, val story: Int)

/** Release demo mode: the habit story first, it is what only this app does. */
internal object DemoScenarios {
    val all = listOf(
        DemoScenario("d4_habit.json", R.string.demo_habit_title, R.string.demo_habit_story),
        DemoScenario("d5_capability.json", R.string.demo_capability_title, R.string.demo_capability_story),
        DemoScenario("d1_rain.json", R.string.demo_rain_title, R.string.demo_rain_story),
        DemoScenario("d6_commute_home.json", R.string.demo_commute_title, R.string.demo_commute_story),
    )
}

/** Release demo mode ends by itself after [MAX_MS], so the real routines are never paused for long unnoticed. */
internal object DemoExpiry {
    const val MAX_MS = 30 * 60_000L

    fun expired(startedAtMs: Long, nowMs: Long): Boolean = nowMs - startedAtMs >= MAX_MS

    /** Never more than [MAX_MS], even if the clock moved back since the start. */
    fun remaining(startedAtMs: Long, nowMs: Long): Long = (MAX_MS - (nowMs - startedAtMs)).coerceIn(0, MAX_MS)
}

/**
 * 체험 모드: plays the stories on a simulated car with its own data, in the release build too, because reviewers get
 * only the APK and cannot drive three mornings to see a habit suggested. The real car's routines pause meanwhile, which
 * the screen says the whole time ([DemoBanner]); a reboot always returns to the real car.
 */
@Composable
internal fun DemoPanel(ui: UiState, commands: DebugCommands?, routineCommands: RoutineCommands?, texts: Texts, onClose: () -> Unit) {
    val state = ui.debug
    // One tap per switch: the service restarts, and a second tap would restart it again.
    var switching by remember(state.fakeVehicle) { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Palette.Background), contentAlignment = Alignment.TopCenter) {
        DotGrid()
        Column(
            Modifier.widthIn(max = 1100.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(Sizes.Gutter),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    MonoLabel("DEMO")
                    Text(stringResource(R.string.demo_title), style = MaterialTheme.typography.displaySmall, color = Palette.Text)
                }
                QuietButton(stringResource(R.string.demo_close), onClose, color = Palette.TextMuted)
            }
            Text(stringResource(R.string.demo_intro), style = MaterialTheme.typography.bodyLarge, color = Palette.TextMuted)
            if (!state.fakeVehicle) {
                // Never in the middle of a drive. If the car state is unknown (permissions denied), the demo needs no
                // car at all, and AAOS hides this screen while driving anyway: waiting for parked would be a dead end.
                val canStart = commands != null && !switching && ui.status.engineReady && (ui.status.parked || !ui.status.drivingKnown)
                PrimaryButton(stringResource(R.string.demo_start), { switching = true; commands?.setFakeVehicle(true) }, enabled = canStart)
                Text(stringResource(R.string.demo_start_note), style = MaterialTheme.typography.bodyMedium, color = Palette.TextMuted)
                return@Column
            }
            DemoScenarios.all.forEachIndexed { index, scenario ->
                Panel(modifier = Modifier.fillMaxWidth(), spacing = 12.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(twoDigits(index + 1), style = Mono.Index, color = Palette.Copper, modifier = Modifier.width(72.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(scenario.title), style = MaterialTheme.typography.titleLarge, color = Palette.Text)
                            Text(stringResource(scenario.story), style = MaterialTheme.typography.bodyMedium, color = Palette.TextMuted)
                        }
                        Spacer(Modifier.width(16.dp))
                        GhostButton(stringResource(R.string.demo_play), { commands?.play(scenario.script) }, enabled = commands != null && !state.playing)
                    }
                    state.playback?.takeIf { it.script == scenario.script }?.let { PlaybackLine(it, commands) }
                    // The habit story pauses at its suggestion: answer it right here, not behind this panel.
                    if (state.playback?.script == scenario.script && state.playback.phase == PlaybackState.Phase.PAUSED_FOR_SUGGESTION) {
                        // "카드에 답했어요" only after an answer here, not whenever no card is shown.
                        var answered by remember(state.playback.step) { mutableStateOf(false) }
                        val suggestion = ui.suggestion
                        if (suggestion != null) {
                            SuggestionCard(suggestion, routineCommands?.let { inner -> answering(inner) { answered = true } }, texts)
                        } else if (answered) {
                            Text(stringResource(R.string.demo_answered), style = MaterialTheme.typography.bodyLarge, color = Palette.Done)
                        }
                    }
                    // What the story did, in order: e.g. told while control is closed, then done once it opens.
                    if (state.playback?.script == scenario.script && ui.log.isNotEmpty()) {
                        Box(Modifier.padding(start = 72.dp)) { RecentLog(ui, texts, max = DEMO_LOG_ENTRIES) }
                    }
                }
            }
            GhostButton(
                stringResource(R.string.demo_end),
                { switching = true; commands?.setFakeVehicle(false) },
                enabled = commands != null && !state.playing && !switching,
                color = Palette.TextMuted,
            )
        }
    }
}

/** The same commands, telling [onAnswer] when the card is accepted or declined. */
private fun answering(inner: RoutineCommands, onAnswer: () -> Unit): RoutineCommands = object : RoutineCommands by inner {
    override fun acceptSuggestion() {
        onAnswer()
        inner.acceptSuggestion()
    }

    override fun rejectSuggestion() {
        onAnswer()
        inner.rejectSuggestion()
    }
}

@Composable
private fun PlaybackLine(playback: PlaybackState, commands: DebugCommands?) {
    val (text, color) = when (playback.phase) {
        PlaybackState.Phase.RUNNING -> stringResource(R.string.debug_playing, playback.step + 1, playback.total) to Palette.Pending
        PlaybackState.Phase.PAUSED_FOR_SUGGESTION -> stringResource(R.string.demo_paused) to Palette.Copper
        PlaybackState.Phase.FINISHED -> stringResource(R.string.debug_finished) to Palette.Done
    }
    Row(Modifier.padding(start = 72.dp), verticalAlignment = Alignment.CenterVertically) {
        Led(color, size = 8.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.Text, modifier = Modifier.weight(1f))
        if (playback.phase == PlaybackState.Phase.PAUSED_FOR_SUGGESTION) PrimaryButton(stringResource(R.string.debug_resume), { commands?.resume() })
    }
}

/** On the main screen for as long as the simulated car runs: the real routines are paused meanwhile. */
@Composable
internal fun DemoBanner(onOpen: () -> Unit, onEnd: () -> Unit) {
    Panel(modifier = Modifier.fillMaxWidth(), background = Palette.CopperDim, border = Palette.Copper.copy(alpha = 0.6f), spacing = 0.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Led(Palette.Copper)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.demo_banner), style = MaterialTheme.typography.bodyLarge, color = Palette.Text, modifier = Modifier.weight(1f))
            QuietButton(stringResource(R.string.demo_open), onOpen)
            GhostButton(stringResource(R.string.demo_end), onEnd, color = Palette.TextMuted)
        }
    }
}

private const val DEMO_LOG_ENTRIES = 6
