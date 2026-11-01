package kr.routinegongbang.core.fake

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertTrue
import kotlin.test.fail

/** The six demo scenarios, end to end against the fake vehicle. */
class DemoScenariosTest {

    /** Every JSON in resources/scenarios, so a new scenario cannot be skipped by accident. */
    private val files: List<String> = run {
        val dir = javaClass.getResource("/scenarios") ?: fail("scenarios directory missing")
        val names = java.io.File(dir.toURI()).listFiles { f -> f.name.endsWith(".json") }.orEmpty().map { it.name }.sorted()
        check(names.size >= 6) { "expected the six demo scenarios, found $names" }
        names
    }

    @org.junit.jupiter.api.Test
    fun `the adopted habit routine does not run outside its time window`() = runTest {
        // d4 ends at its climax, the fourth morning, so the engine check of the evening ride after it lives here.
        val d4 = javaClass.getResource("/scenarios/d4_habit.json")?.readText() ?: fail("missing d4")
        val evening = """,
    { "type": "at", "time": "2026-10-29T18:30:00+09:00" },
    { "type": "rideStart" },
    { "type": "advance", "ms": 1000 },
    { "type": "expect", "writes": [], "decisions": ["habit-steeringWheel.heat-1-weekday-0750-cold:ConditionNotMet"] }
  ]
}"""
        val text = d4.trimEnd().removeSuffix("}").trimEnd().removeSuffix("]").trimEnd() + evening
        val scenario = ScenarioJson.decode(text).getOrElse { fail("does not parse: ${it.message}") }
        val result = ScenarioRunner().run(scenario)
        assertTrue(result.passed, result.failures.joinToString("\n") + "\n--- transcript ---\n" + result.transcript.joinToString("\n"))
    }

    @TestFactory
    fun `demo scenarios pass`(): List<DynamicTest> = files.map { file ->
        DynamicTest.dynamicTest(file) {
            val text = javaClass.getResource("/scenarios/$file")?.readText() ?: fail("missing scenario $file")
            val scenario = ScenarioJson.decode(text).getOrElse { fail("$file does not parse: ${it.message}") }
            runTest {
                val result = ScenarioRunner().run(scenario)
                assertTrue(result.passed, "${scenario.name}\n" + result.failures.joinToString("\n") + "\n--- transcript ---\n" + result.transcript.joinToString("\n"))
            }
        }
    }
}
