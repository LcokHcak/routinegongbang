package kr.routinegongbang.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `every notification is a group of its own, so the system never folds them into its own summary`() {
        // With two or more ungrouped notices the system adds a summary of its own, and the centre draws its body in
        // #333333 whatever span the notice has (measured on the emulator): an explicit group per notification keeps them apart.
        val keys = listOf(NoticeText.group("notice", 1001), NoticeText.group("notice", 1002), NoticeText.group("confirm", 1001), NoticeText.group("service", 1))
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `contrast is the usual ratio, black on white the largest`() {
        val ratio = NoticeText.contrast(0xFF000000.toInt(), 0xFFFFFFFF.toInt())
        assertTrue(ratio > 20.9 && ratio < 21.1, "$ratio")
    }
}
