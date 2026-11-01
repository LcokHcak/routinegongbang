package kr.routinegongbang.core.fake

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScenarioRunnerTest {

    private val runner = ScenarioRunner()

    private val rain = """
        {"id":"rain","name":"비","trigger":{"type":"SignalEdge","signal":"wiper.front.state","to":["ON"]},
         "actions":[{"type":"Write","signal":"hvac.defroster.front","value":true}]}
    """.trimIndent()

    private fun scenario(expect: String, routine: String = rain) = """
        {"name":"t","start":"2026-10-27T08:00:00+09:00",
         "routines":[$routine],
         "initial":{"wiper.front.state":"OFF","hvac.defroster.front":false},
         "steps":[{"type":"user","signal":"wiper.front.state","value":"ON"},{"type":"advance","ms":1000},$expect]}
    """.trimIndent()

    @Test
    fun `passing expectations give a passing result with a transcript`() = runTest {
        val result = runner.run(ScenarioJson.decode(scenario("""{"type":"expect","writes":[{"signal":"hvac.defroster.front","value":true}],"values":{"hvac.defroster.front":true}}""")).getOrThrow())
        assertTrue(result.passed, result.failures.joinToString())
        assertTrue(result.transcript.isNotEmpty())
    }

    @Test
    fun `a wrong expectation is reported with the step number`() = runTest {
        val result = runner.run(ScenarioJson.decode(scenario("""{"type":"expect","writes":[]}""")).getOrThrow())
        assertFalse(result.passed)
        assertTrue(result.failures.single().startsWith("step 3"), result.failures.single())
    }

    @Test
    fun `expectations only see what happened since the previous expectation`() = runTest {
        val json = scenario("""{"type":"expect","writes":[{"signal":"hvac.defroster.front","value":true}]},{"type":"expect","writes":[],"notifications":[]}""")
        assertTrue(runner.run(ScenarioJson.decode(json).getOrThrow()).passed)
    }

    @Test
    fun `an invalid routine fails the scenario before it runs`() = runTest {
        val bad = """{"id":"bad","name":"x","trigger":{"type":"RideStart"},"actions":[{"type":"Write","signal":"light.exterior.lowBeam","value":true}]}"""
        val result = runner.run(ScenarioJson.decode(scenario("""{"type":"expect","writes":[]}""", routine = bad)).getOrThrow())
        assertFalse(result.passed)
        assertTrue("bad" in result.failures.single())
    }

    @Test
    fun `broken json is a decode failure`() {
        assertTrue(ScenarioJson.decode("{").isFailure)
        assertTrue(ScenarioJson.decode("""{"name":"x","start":"not a time","steps":[]}""").isFailure)
    }

    @Test
    fun `unknown signals in a scenario are reported, not thrown`() = runTest {
        val json = """{"name":"t","start":"2026-10-27T08:00:00+09:00","steps":[{"type":"user","signal":"no.such","value":1}]}"""
        val result = runner.run(ScenarioJson.decode(json).getOrThrow())
        assertEquals(listOf("step 1 (user): unknown signal no.such"), result.failures)
    }

    private suspend fun runJson(json: String) = runner.run(ScenarioJson.decode(json).getOrThrow())

    @Test
    fun `decisions show why nothing happened`() = runTest {
        val steps = """{"type":"expect","decisions":["rain:Ran(Sent)"]},""" +
            """{"type":"user","signal":"wiper.front.state","value":"OFF"},{"type":"user","signal":"wiper.front.state","value":"ON"},""" +
            """{"type":"expect","decisions":["rain:Ran(NoChange)"]}"""
        val result = runJson(scenario(steps))
        assertTrue(result.passed, result.failures.joinToString())
    }

    @Test
    fun `a wrong decision expectation fails`() = runTest {
        assertFalse(runJson(scenario("""{"type":"expect","decisions":["rain:ConditionNotMet"]}""")).passed)
    }

    @Test
    fun `ride steps fail loudly when no trip starts or ends`() = runTest {
        val noSeat = """{"name":"t","start":"2026-10-27T08:00:00+09:00","initial":{"door.lock":false},"steps":[{"type":"rideStart"}]}"""
        assertTrue(runJson(noSeat).failures.single().contains("seat.occupied.driver"))
        val noTrip = """{"name":"t","start":"2026-10-27T08:00:00+09:00","initial":{"seat.occupied.driver":false,"door.lock":false},"steps":[{"type":"rideEnd"}]}"""
        assertTrue(runJson(noTrip).failures.single().contains("no trip"))
    }

    @Test
    fun `bad script input is reported`() = runTest {
        val json = """{"name":"t","start":"2026-10-27T08:00:00+09:00","initial":{"ev.soc":"full"},""" +
            """"steps":[{"type":"runNow","routineId":"ghost"},{"type":"user","signal":"ev.soc","value":150},{"type":"expect","noSuggestion":true}]}"""
        val failures = runJson(json).failures
        assertEquals(4, failures.size, failures.joinToString("\n"))
        assertTrue(failures[0].startsWith("initial: ev.soc"), failures[0])
        assertTrue("ghost" in failures[1], failures[1])
        assertTrue("150" in failures[2], failures[2])
        assertTrue("no trip" in failures[3], failures[3])
    }

    @Test
    fun `unknown keys and bad times are rejected when the script is read`() {
        assertTrue(ScenarioJson.decode("""{"name":"t","start":"2026-10-27T08:00:00+09:00","stpes":[]}""").isFailure)
        assertTrue(ScenarioJson.decode("""{"name":"t","start":"2026-10-27T08:00:00+09:00","steps":[{"type":"at","time":"tomorrow"}]}""").isFailure)
    }

    @Test
    fun `going back in time is reported`() = runTest {
        val json = """{"name":"t","start":"2026-10-27T08:00:00+09:00","steps":[{"type":"at","time":"2026-10-27T07:00:00+09:00"}]}"""
        assertTrue(runJson(json).failures.single().contains("before"))
    }

    @Test
    fun `failing writes can be scripted`() = runTest {
        val json = scenario("""{"type":"expect","decisions":["rain:Ran(Failed)"],"writes":[]}""")
            .replaceFirst("{\"name\"", "{\"failing\":[\"hvac.defroster.front\"],\"name\"")
        val result = runJson(json)
        assertTrue(result.passed, result.failures.joinToString())
    }

    @Test
    fun `a slow echo within the grace period is still the app's own echo`() = runTest {
        val json = scenario("""{"type":"advance","ms":5000},{"type":"expect","confirmed":["hvac.defroster.front"]}""")
            .replaceFirst("{\"name\"", "{\"echoDelayMs\":5000,\"name\"")
        val result = runJson(json)
        assertTrue(result.passed, result.failures.joinToString())
    }
}
