package kr.routinegongbang.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Measured on the Pleos Connect emulator: the notification centre wraps a long line and shows three rows; a body
 * it has to cut, more rows than that, is drawn in #333333 whatever span it has. So every body is fitted here first.
 */
class NoticeLinesTest {

    private fun more(n: Int) = "외 ${n}건"

    @Test
    fun `short lines stay as they are, blank ones go`() {
        assertEquals(listOf("비 오는 날 · 알려드렸어요 1", "창문이 열려 있어요."), NoticeLines.fit(listOf("비 오는 날 · 알려드렸어요 1", "", "창문이 열려 있어요."), ::more))
    }

    @Test
    fun `a long sentence that wraps onto two rows stays whole`() {
        val earned = "「내 루틴」을(를) 3번 연속 허락하셔서, 다음부터는 묻지 않고 할게요. 루틴 편집에서 언제든 되돌릴 수 있어요."
        assertEquals(listOf(earned), NoticeLines.fit(listOf(earned), ::more))
    }

    @Test
    fun `more rows than the centre shows end in a count of the rest`() {
        val lines = (1..5).map { "루틴 $it · 했어요 1" }
        assertEquals(listOf("루틴 1 · 했어요 1", "루틴 2 · 했어요 1", "외 3건"), NoticeLines.fit(lines, ::more))
    }

    @Test
    fun `a line that wraps counts its rows`() {
        val wide = "비 오는 날 · 알려드렸어요 1 · 아직 자동 제어가 열리지 않았어요 · 한 번 실행하면 30분은 쉬어요"
        assertEquals(listOf(wide, "외 2건"), NoticeLines.fit(listOf(wide, "창문 확인 · 알려드렸어요 1", "내 루틴 · 했어요 1"), ::more))
    }

    @Test
    fun `a text with a line break of its own counts each of its lines`() {
        // A held run summary joins what was done and what was told with a line break.
        val held = listOf("비 오는 날 · 성에 제거 켰어요\n창문 닫아 주세요", "추운 아침 · 핸들 열선 켰어요\n운전석 열선 직접 켜 주세요")
        assertEquals(listOf("비 오는 날 · 성에 제거 켰어요", "창문 닫아 주세요", "외 2건"), NoticeLines.fit(held, ::more))
    }

    @Test
    fun `notices already left out are counted with the rest`() {
        assertEquals(listOf("루틴 1", "루틴 2", "외 4건"), NoticeLines.fit(listOf("루틴 1", "루틴 2"), ::more, hidden = 4))
    }

    @Test
    fun `a long first line among others keeps two rows and counts the rest`() {
        val long = "가".repeat(NoticeLines.ROW_CHARS * 3)
        val fitted = NoticeLines.fit(listOf(long, "루틴 2"), ::more)
        assertEquals(NoticeLines.ROW_CHARS * 2, fitted.first().length)
        assertEquals("외 1건", fitted.last())
    }

    @Test
    fun `nothing in, nothing out`() {
        assertEquals(emptyList(), NoticeLines.fit(emptyList(), ::more))
    }

    @Test
    fun `exactly as many rows as the centre shows need no count`() {
        val lines = (1..3).map { "루틴 $it" }
        assertEquals(lines, NoticeLines.fit(lines, ::more))
    }

    @Test
    fun `a line longer than every row together is cut, with an ellipsis`() {
        val endless = "가".repeat(NoticeLines.ROW_CHARS * NoticeLines.MAX_ROWS + 10)
        val cut = NoticeLines.fit(listOf(endless), ::more).single()
        assertEquals(NoticeLines.ROW_CHARS * NoticeLines.MAX_ROWS, cut.length)
        assertEquals('…', cut.last())
    }
}
