package kr.routinegongbang.core.safety

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every hazard, H01 to H36, has at least one test that names it, e.g.
 * `H07 a window is never opened`, or is listed here as deferred with the reason. Adding a hazard without a test
 * fails the build.
 */
class HazardTraceabilityTest {

    private val hazards = (1..36).map { "H%02d".format(it) }

    /** Not testable yet because the signal or feature is not in the app; each lands with its phase. */
    private val deferred = mapOf(
        "H17" to "hold-limit reminders (recirculation, heat level 3) come with the editor's run history",
        "H19" to "the Pleos SDK exposes the charging port only through hidden (@hide) APIs, so the app does not use it at all",
        "H25" to "routine import arrives with the editor (phase 3); it goes through the same validator",
        "H33" to "smart glass and blinds are not in the catalog yet (phase 4)",
        "H34" to "the charging current limit is not in the catalog yet (phase 4)",
        "H35" to "routine export arrives with the editor (phase 3)",
    )

    @Test
    fun `every hazard has a test that names it, or a reason why not yet`() {
        val testNames = File("src/test/kotlin").walkTopDown().filter { it.extension == "kt" }
            .flatMap { file -> Regex("fun `([^`]*)`").findAll(file.readText()).map { it.groupValues[1] } }
            .toList()
        assertTrue(testNames.size > 100, "test sources not found from ${File(".").absolutePath}")
        val named = testNames.flatMap { name -> Regex("\\bH\\d{2}\\b").findAll(name).map { it.value } }.toSet()
        val missing = hazards.filter { it !in named && it !in deferred }
        assertTrue(missing.isEmpty(), "hazards without a test: $missing")
        val stale = deferred.keys.filter { it in named }
        assertTrue(stale.isEmpty(), "now tested, remove from deferred: $stale")
    }
}
