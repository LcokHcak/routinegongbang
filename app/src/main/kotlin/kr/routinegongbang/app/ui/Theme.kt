package kr.routinegongbang.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.routinegongbang.app.ui.model.Outcome
import kr.routinegongbang.app.ui.model.Status
import kr.routinegongbang.app.ui.model.StatusView

/**
 * "Workshop instrument panel": near-black with a faint dot grid, hairline panels instead of soft cards, one signal
 * orange for the app's own voice, monospaced figures for anything measured. Status colours carry meaning only
 * and are never used for decoration.
 */
internal object Palette {
    val Background = Color(0xFF08090B)
    val Grid = Color(0xFF1B1E23)
    val Surface = Color(0xFF0F1114)
    val SurfaceRaised = Color(0xFF16191D)
    val Outline = Color(0xFF23272D)
    val OutlineStrong = Color(0xFF353B43)
    val Text = Color(0xFFF2F3F5)
    val TextMuted = Color(0xFF8E959F)
    /** Still 4.5:1 or more on every surface: faint, never unreadable. */
    val TextFaint = Color(0xFF7A828D)
    val Copper = Color(0xFFFF8A3D)
    val CopperDim = Color(0xFF261710)

    val Done = Color(0xFF3DDC97)
    val Pending = Color(0xFFFFC145)
    val Error = Color(0xFFFF5C5C)
    val Notice = Color(0xFF5AA9FF)
    val Neutral = Color(0xFF7A828D)

    /** A failure is red, a deliberate skip stays grey: both "안 했어요", not the same news. */
    fun of(view: StatusView): Color = if (view.status == Status.FAILED) Error else of(view.outcome)

    fun of(outcome: Outcome): Color = when (outcome) {
        Outcome.DONE -> Done
        Outcome.TOLD -> Notice
        Outcome.NOT_DONE -> Neutral
        Outcome.IN_PROGRESS, Outcome.UNCONFIRMED -> Pending
    }
}

/** Car displays are read at arm's length in a glance: large type, AAOS touch targets of 64 dp or more. */
internal object Sizes {
    val Touch = 64.dp
    val Gutter = 32.dp
    val CardPadding = 28.dp
    val Hairline = 1.dp

    /** Radii differ on purpose: panels are soft, controls firmer, tags nearly square. */
    val PanelRadius = 16.dp
    val ControlRadius = 10.dp
    val TagRadius = 6.dp
}

/**
 * Korean reads in the system sans (Noto Sans CJK on AAOS); figures, clocks, counters and section indices use the
 * system monospace, so every measured value lines up and reads as data. No bundled fonts.
 */
internal object Mono {
    private val family = FontFamily.Monospace
    val Index = TextStyle(fontFamily = family, fontSize = 26.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp)
    val Value = TextStyle(fontFamily = family, fontSize = 20.sp, fontWeight = FontWeight.Medium)
    val Label = TextStyle(fontFamily = family, fontSize = 15.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.6.sp)
}

private val CarTypography = Typography(
    displaySmall = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.2).sp, lineHeight = 52.sp),
    headlineMedium = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
    titleLarge = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 20.sp, lineHeight = 28.sp),
    bodyMedium = TextStyle(fontSize = 18.sp, lineHeight = 26.sp),
    labelLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
    labelMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
)

@Composable
internal fun RoutineTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Copper,
            onPrimary = Palette.Background,
            background = Palette.Background,
            surface = Palette.Surface,
            surfaceVariant = Palette.SurfaceRaised,
            onSurface = Palette.Text,
            onSurfaceVariant = Palette.TextMuted,
            outline = Palette.Outline,
        ),
        typography = CarTypography,
        content = content,
    )
}
