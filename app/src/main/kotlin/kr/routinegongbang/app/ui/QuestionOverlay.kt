package kr.routinegongbang.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kr.routinegongbang.app.R
import kr.routinegongbang.app.platform.Questions

/**
 * "「추운 아침」 실행할까요?" on the app's own screen, over everything (the notification alone is never
 * seen with the app open). The countdown is the run's real deadline; the first answer, here or in the notification,
 * counts, and the run takes it off the screen when it stops waiting.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun QuestionOverlay(parked: Boolean, questions: Questions = Questions.app) {
    val shown by questions.current.collectAsStateWithLifecycle()
    // Driving off mid-question: no full-screen question while moving; the notification stays.
    val question = shown?.takeIf { parked } ?: return
    val answerFirst = remember { FocusRequester() }
    // A rotary controller starts on the answer and cannot wander to the list behind.
    LaunchedEffect(question.id) { runCatching { answerFirst.requestFocus() } }
    val remaining by produceState(secondsLeft(question.content.deadlineMs), question.id) {
        while (value > 0) {
            delay(TICK_MS)
            value = secondsLeft(question.content.deadlineMs)
        }
    }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
            // Touches outside the question must not reach the list behind it.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .focusProperties { exit = { FocusRequester.Cancel } }
            .focusGroup(),
        contentAlignment = Alignment.Center,
    ) {
        Panel(modifier = Modifier.widthIn(max = 880.dp), border = Palette.Copper.copy(alpha = 0.6f), spacing = 20.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Led(Palette.Pending)
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.confirm_title, question.content.routineName),
                    style = MaterialTheme.typography.headlineMedium,
                    color = Palette.Text,
                    modifier = Modifier.weight(1f),
                )
                Text(stringResource(R.string.question_remaining, remaining), style = Mono.Value, color = Palette.Pending)
            }
            // Many actions scroll here, so the buttons below never leave the screen.
            Column(Modifier.heightIn(max = LINES_MAX_HEIGHT).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                question.content.lines.forEach { Text(it, style = MaterialTheme.typography.titleLarge, color = Palette.Text) }
            }
            Text(stringResource(R.string.confirm_timeout_hint), style = MaterialTheme.typography.bodyMedium, color = Palette.TextMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton(stringResource(R.string.confirm_yes), { questions.answer(question.id, yes = true) }, Modifier.focusRequester(answerFirst))
                GhostButton(stringResource(R.string.confirm_no), { questions.answer(question.id, yes = false) }, color = Palette.TextMuted)
            }
        }
    }
}

private fun secondsLeft(deadlineMs: Long): Int = ((deadlineMs - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(0).toInt()

private const val TICK_MS = 250L
private val LINES_MAX_HEIGHT = 320.dp
