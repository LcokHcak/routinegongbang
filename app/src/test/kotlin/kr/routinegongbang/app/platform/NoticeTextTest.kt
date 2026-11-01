package kr.routinegongbang.app.platform

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Measured on the Pleos Connect v2.1.0 emulator: the notification centre draws an app's notification body in #333333
 * on #282A2D, unreadable, while its own notices use #909090. A colour span on the body is kept as given.
 */
class NoticeTextTest {

    @Test
    fun `the body colour reads well on the notification centre`() {
        assertTrue(NoticeText.contrast(NoticeText.BODY_COLOR, NoticeText.CENTRE_BACKGROUND) >= 4.5)
    }

    @Test
    fun `what the centre draws by itself does not`() {
        assertTrue(NoticeText.contrast(0xFF333333.toInt(), NoticeText.CENTRE_BACKGROUND) < 1.5)
    }

    @Test
    fun `contrast is the usual ratio, black on white the largest`() {
        val ratio = NoticeText.contrast(0xFF000000.toInt(), 0xFFFFFFFF.toInt())
        assertTrue(ratio > 20.9 && ratio < 21.1, "$ratio")
    }
}
