package kr.routinegongbang.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale

/*
 * The app's small design kit: every screen builds from these, so spacing, radii and states stay one system.
 * Touch targets never go below Sizes.Touch; colour never carries meaning alone (a word always goes with it).
 */

/**
 * The workshop's cutting mat behind a screen. Its own layer, so it is recorded once per size and never again when
 * something in front of it redraws (a blinking cursor, a ripple).
 */
@Composable
internal fun DotGrid() {
    Box(Modifier.fillMaxSize().graphicsLayer().dotGrid())
}

/** A faint dot grid, computed once per size. */
private fun Modifier.dotGrid(spacing: Dp = 28.dp, color: Color = Palette.Grid): Modifier = drawWithCache {
    val step = spacing.toPx()
    val points = buildList {
        var y = step / 2
        while (y < this@drawWithCache.size.height) {
            var x = step / 2
            while (x < this@drawWithCache.size.width) {
                add(Offset(x, y))
                x += step
            }
            y += step
        }
    }
    val dot = 2.dp.toPx()
    onDrawBehind { drawPoints(points, PointMode.Points, color, strokeWidth = dot, cap = StrokeCap.Round) }
}

/** A dashed hairline: "nothing here yet, make one" (the custom template, empty lists). */
internal fun Modifier.dashedBorder(color: Color, radius: Dp): Modifier = drawWithCache {
    val width = Sizes.Hairline.toPx() * 1.5f
    val stroke = Stroke(width = width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 8.dp.toPx())))
    // Inset by half the stroke, so the whole line stays inside the bounds.
    val inset = width / 2
    val box = Size(this.size.width - width, this.size.height - width)
    onDrawBehind {
        drawRoundRect(color = color, topLeft = Offset(inset, inset), size = box, cornerRadius = CornerRadius(radius.toPx()), style = stroke)
    }
}

/** "07": list positions and step numbers, independent of the device locale. */
internal fun twoDigits(n: Int): String = String.format(Locale.ROOT, "%02d", n)

@Composable
internal fun Panel(
    modifier: Modifier = Modifier,
    border: Color = Palette.Outline,
    background: Color = Palette.Surface,
    padding: PaddingValues = PaddingValues(Sizes.CardPadding),
    spacing: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Sizes.PanelRadius)
    Column(
        modifier.clip(shape).background(background).border(Sizes.Hairline, border, shape).padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

/**
 * Small monospaced caption: section indices, counters, telemetry keys. The English keys ("WHEN", "DRIVE") are part of
 * the look, not of the message, so a screen reader skips them; the Korean next to them says it all.
 */
@Composable
internal fun MonoLabel(text: String, color: Color = Palette.TextFaint, modifier: Modifier = Modifier) {
    Text(text, style = Mono.Label, color = color, modifier = modifier.clearAndSetSemantics {}, maxLines = 1)
}

/** A status light with a soft glow; decorative only, the word next to it says the state. */
@Composable
internal fun Led(color: Color, size: Dp = 10.dp) {
    Box(
        Modifier.size(size * 2).clearAndSetSemantics {}.drawBehind {
            drawCircle(color.copy(alpha = 0.18f), radius = size.toPx())
            drawCircle(color, radius = size.toPx() / 2)
        },
    )
}

/** "01 ─ 언제 ───────" : numbered sections make the editor read as one flow, top to bottom. */
@Composable
internal fun SectionHeader(index: Int, title: String, trailing: @Composable (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        MonoLabel(twoDigits(index), Palette.Copper)
        Spacer(Modifier.width(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = Palette.Text)
        Spacer(Modifier.width(18.dp))
        Box(Modifier.weight(1f).height(Sizes.Hairline).background(Palette.Outline))
        trailing?.let {
            Spacer(Modifier.width(12.dp))
            it()
        }
    }
}

/** A trigger or condition as a tag; [code] is the monospaced key in front ("WHEN", "IF"). */
@Composable
internal fun Tag(text: String, code: String? = null, accent: Boolean = false) {
    val shape = RoundedCornerShape(Sizes.TagRadius)
    Row(
        Modifier.clip(shape)
            .background(if (accent) Palette.CopperDim else Palette.SurfaceRaised)
            .border(Sizes.Hairline, if (accent) Palette.Copper.copy(alpha = 0.45f) else Palette.Outline, shape)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        code?.let {
            MonoLabel(it, if (accent) Palette.Copper else Palette.TextFaint)
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = if (accent) Palette.Text else Palette.TextMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(Sizes.ControlRadius),
        colors = ButtonDefaults.buttonColors(
            containerColor = Palette.Copper,
            contentColor = Palette.Background,
            disabledContainerColor = Palette.SurfaceRaised,
            disabledContentColor = Palette.TextFaint,
        ),
        contentPadding = PaddingValues(horizontal = 28.dp),
        modifier = modifier.heightIn(min = Sizes.Touch),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
internal fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = Palette.Copper) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(Sizes.ControlRadius),
        border = BorderStroke(1.5.dp, if (enabled) color.copy(alpha = 0.7f) else Palette.Outline),
        contentPadding = PaddingValues(horizontal = 24.dp),
        modifier = modifier.heightIn(min = Sizes.Touch),
    ) { Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) color else Palette.TextFaint) }
}

@Composable
internal fun QuietButton(text: String, onClick: () -> Unit, enabled: Boolean = true, color: Color = Palette.Copper) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(Sizes.ControlRadius),
        modifier = Modifier.heightIn(min = Sizes.Touch).widthIn(min = Sizes.Touch),
    ) { Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) color else Palette.TextFaint) }
}

@Composable
internal fun KitSwitch(checked: Boolean, onChange: (Boolean) -> Unit, description: String, enabled: Boolean = true) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedTrackColor = Palette.Copper,
            checkedThumbColor = Palette.Background,
            checkedBorderColor = Palette.Copper,
            uncheckedTrackColor = Palette.SurfaceRaised,
            uncheckedThumbColor = Palette.TextMuted,
            uncheckedBorderColor = Palette.OutlineStrong,
        ),
        modifier = Modifier.size(width = 72.dp, height = Sizes.Touch).semantics { contentDescription = description },
    )
}

/** Monospaced value between − and +, so the number being changed sits where the finger is. */
@Composable
internal fun StepperControl(value: String?, onDown: () -> Unit, onUp: () -> Unit) {
    val shape = RoundedCornerShape(Sizes.ControlRadius)
    Row(
        Modifier.clip(shape).border(Sizes.Hairline, Palette.OutlineStrong, shape).background(Palette.SurfaceRaised),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepKey("−", onDown)
        value?.let {
            Box(Modifier.height(Sizes.Touch).width(Sizes.Hairline).background(Palette.Outline))
            Text(it, style = Mono.Value, color = Palette.Text, modifier = Modifier.padding(horizontal = 18.dp), maxLines = 1)
        }
        Box(Modifier.height(Sizes.Touch).width(Sizes.Hairline).background(Palette.Outline))
        StepKey("+", onUp)
    }
}

@Composable
private fun StepKey(symbol: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        shape = RoundedCornerShape(0.dp),
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.size(Sizes.Touch),
    ) { Text(symbol, style = Mono.Index, color = Palette.Text) }
}
