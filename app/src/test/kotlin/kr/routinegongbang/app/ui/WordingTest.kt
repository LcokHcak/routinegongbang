package kr.routinegongbang.app.ui

import kr.routinegongbang.app.R
import kr.routinegongbang.core.model.CompareOp
import kr.routinegongbang.core.model.SignalValue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the driver reads must not be developer or engine-room language. */
class WordingTest {

    /** Every user-facing string (the debug panel is for developers and keeps its own words). */
    private val userFacing: Map<String, String> = Regex("""<string name="([^"]+)">([^<]*)</string>""")
        .findAll(File("src/main/res/values/strings.xml").readText())
        .associate { it.groupValues[1] to it.groupValues[2] }
        .filterKeys { !it.startsWith("debug_") }

    @Test
    fun `no SDK, no engine, no math symbols in anything the driver reads`() {
        // "엔진" reads as the car's engine in a car; "SDK" means nothing to a driver.
        val jargon = listOf("SDK", "엔진", "≥", "≤", "≠")
        val found = userFacing.filterValues { text -> jargon.any { it in text } }
        assertTrue(found.isEmpty(), "jargon in: $found")
    }

    @Test
    fun `comparisons are said in words, one wording per operator`() {
        assertEquals(CompareOp.entries.size, CompareOp.entries.map { Wording.compare(it) }.toSet().size)
        assertEquals("%1\$s %2\$s 이상", userFacing["compare_ge"])
    }

    @Test
    fun `a state in a trigger or condition reads as a state, an action as an action`() {
        // "충전 = 켜기" in a condition reads as an action, not a state.
        assertEquals(R.string.value_state_on, Wording.state(SignalValue.Bool(true)))
        assertEquals(R.string.value_state_off, Wording.state(SignalValue.Bool(false)))
        assertEquals("켜짐", userFacing["value_state_on"])
        assertEquals("켜기", userFacing["value_on"])
    }

    @Test
    fun `the end-of-run message says the request was sent, not that it is done`() {
        // It is posted before the car confirms; the card may later say "확인 불가".
        val text = userFacing.getValue("run_summary")
        assertTrue("요청했어요" in text && "실행했어요" !in text, text)
    }

    @Test
    fun `ride start says what really starts it, sitting down`() {
        assertEquals("차에 타면", userFacing["trigger_ride_start"])
    }
}
