package kr.routinegongbang.app.platform

import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import kotlin.math.pow

/**
 * The Pleos notification centre draws an app's notification body in #333333 on #282A2D, a contrast of 1.1:1, while
 * its own notices use #909090 (measured on the Pleos Connect v2.1.0 emulator; the title is drawn white either way).
 * A colour span on the body is kept as given, so every body this app posts carries that kind of grey, a little lighter
 * for reading in a car. The centre is dark in day and night mode alike.
 */
internal object NoticeText {
    /** About 5.5:1 on the centre's background, above the 4.5:1 that body text needs. */
    const val BODY_COLOR: Int = 0xFFA0A0A0.toInt()
    const val CENTRE_BACKGROUND: Int = 0xFF282A2D.toInt()

    fun readable(text: CharSequence): CharSequence =
        SpannableString(text).apply { setSpan(ForegroundColorSpan(BODY_COLOR), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }

    /**
     * A group of its own for one notification. With two or more ungrouped notices the system folds them under a summary
     * of its own, and the centre draws that summary's body in #333333 whatever span the notice has (measured on the
     * emulator); the system never folds notifications that name a group.
     */
    fun group(kind: String, id: Int): String = "kr.routinegongbang.$kind.$id"

    /** The WCAG contrast ratio of two opaque colours (1 to 21). */
    fun contrast(a: Int, b: Int): Double {
        val (la, lb) = luminance(a) to luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val c = ((color shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
}
