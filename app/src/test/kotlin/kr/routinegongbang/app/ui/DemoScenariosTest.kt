package kr.routinegongbang.app.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Release demo mode: reviewers only get the APK, so the demo must offer real scripts. */
class DemoScenariosTest {

    @Test
    fun `every demo scenario exists as a script`() {
        DemoScenarios.all.forEach { scenario ->
            assertTrue(File("../core/src/main/resources/scenarios/${scenario.script}").isFile, scenario.script)
        }
    }

    @Test
    fun `the habit story comes first, it is what only this app does`() {
        assertEquals("d4_habit.json", DemoScenarios.all.first().script)
    }

    @Test
    fun `demo mode ends by itself, so the real routines are never paused for long unnoticed`() {
        // No boot event on suspend/resume, and the banner scrolls away.
        assertEquals(false, DemoExpiry.expired(startedAtMs = 0, nowMs = DemoExpiry.MAX_MS - 1))
        assertEquals(true, DemoExpiry.expired(startedAtMs = 0, nowMs = DemoExpiry.MAX_MS))
        assertEquals(DemoExpiry.MAX_MS - 1_000, DemoExpiry.remaining(startedAtMs = 0, nowMs = 1_000))
        // A start time in the future (clock moved back) never keeps it alive longer than the maximum.
        assertEquals(true, DemoExpiry.remaining(startedAtMs = 10 * DemoExpiry.MAX_MS, nowMs = 0) <= DemoExpiry.MAX_MS)
    }
}
