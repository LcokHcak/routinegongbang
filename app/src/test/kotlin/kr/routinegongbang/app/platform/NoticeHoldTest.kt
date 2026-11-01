package kr.routinegongbang.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Notices popping up while driving would in effect ask the driver to operate the car on the move. */
class NoticeHoldTest {

    @Test
    fun `parked, a notice goes out at once`() {
        assertEquals("a", NoticeHold().offer("a") { false })
    }

    @Test
    fun `driving, notices wait and come out together once parked`() {
        val hold = NoticeHold()
        assertNull(hold.offer("a") { true })
        assertNull(hold.offer("b") { true })
        assertEquals(NoticeHold.Released(listOf("a", "b"), dropped = 0), hold.release())
        assertTrue(hold.release().isEmpty)
    }

    @Test
    fun `a long drive keeps the newest notices only, and says how many it left out`() {
        // The oldest must not be dropped without a word.
        val hold = NoticeHold(max = 2)
        listOf("a", "b", "c").forEach { text -> hold.offer(text) { true } }
        assertEquals(NoticeHold.Released(listOf("b", "c"), dropped = 1), hold.release())
    }

    @Test
    fun `the same notice twice in one drive is held once`() {
        val hold = NoticeHold()
        repeat(3) { hold.offer("a") { true } }
        assertEquals(listOf("a"), hold.release().texts)
    }

    @Test
    fun `whether to hold is decided under the hold's lock`() {
        // Decided before taking the lock, a notice could slip in just after the release and wait a
        // whole next drive. Released first, the moment is no longer quiet, so the notice goes out.
        var quiet = true
        val hold = NoticeHold()
        quiet = false
        assertTrue(hold.release().isEmpty)
        assertEquals("a", hold.offer("a") { quiet })
    }

    @Test
    fun `clearing forgets what a previous story held`() {
        val hold = NoticeHold()
        hold.offer("a") { true }
        hold.clear()
        assertTrue(hold.release().isEmpty)
    }
}
