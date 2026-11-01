package kr.routinegongbang.app.gleo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kr.routinegongbang.app.ui.RoutineItem
import kr.routinegongbang.app.ui.UiState
import kr.routinegongbang.app.ui.model.LastRun
import kr.routinegongbang.app.ui.model.RunLogEntry
import kr.routinegongbang.app.ui.model.Skip
import kr.routinegongbang.app.ui.model.Status
import kr.routinegongbang.core.engine.ActionResult
import kr.routinegongbang.core.model.Routine
import kr.routinegongbang.core.model.SignalIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Gleo Interface: voice is the only allowed way to act while driving. Gleo calls a function by intent
 * action with one JSON string extra "ACTION_PARAM", and waits up to 7 s for an APP_ACTION_RESPONSE broadcast.
 */
class GleoTest {

    private val routines = listOf(
        GleoRoutine("rain", "비 오는 날", enabled = true),
        GleoRoutine("winter", "겨울 출근", enabled = true),
        GleoRoutine("home", "퇴근길", enabled = false),
    )

    @Test
    fun `parameters arrive as one JSON string, sometimes wrapped in quotes`() {
        assertEquals("비 오는 날", GleoParams.parse("""{"routineName":"비 오는 날"}""").string("routineName"))
        assertEquals("겨울", GleoParams.parse("\"{\\\"routineName\\\":\\\"겨울\\\"}\"").string("routineName"))
        assertEquals(false, GleoParams.parse("""{"routineName":"퇴근길","enabled":false}""").boolean("enabled"))
        assertNull(GleoParams.parse(null).string("routineName"))
        assertNull(GleoParams.parse("not json").string("routineName"))
    }

    @Test
    fun `a routine is found by its name, loosely`() {
        assertEquals("rain", GleoNames.match(routines, "비오는날").single().id)
        assertEquals("winter", GleoNames.match(routines, "겨울").single().id)
        assertTrue(GleoNames.match(routines, "없는 루틴").isEmpty())
    }

    @Test
    fun `a routine without a name is never what was said`() {
        // An empty key is contained in every spoken name.
        val withBlank = routines + GleoRoutine("blank", " ", enabled = true)
        assertEquals("rain", GleoNames.match(withBlank, "비 오는 날").single().id)
        assertEquals("winter", GleoNames.match(withBlank, "겨울 출근 루틴").single().id)
    }

    @Test
    fun `starting needs a name that matches exactly one routine`() {
        assertIs<GleoDecision.Start>(Gleo.start(routines, GleoParams.parse("""{"routineName":"겨울 출근"}"""), parked = true))
        val missing = assertIs<GleoDecision.Reply>(Gleo.start(routines, GleoParams.parse("{}"), parked = true))
        assertEquals(GleoStatus.ASKING, missing.reply.status)
        val unknown = assertIs<GleoDecision.Reply>(Gleo.start(routines, GleoParams.parse("""{"routineName":"주말"}"""), parked = true))
        assertEquals(GleoStatus.ASKING, unknown.reply.status)
        // The candidates go to Gleo so it can ask "which one?".
        assertEquals(3, (Json.parseToJsonElement(unknown.reply.data!!) as JsonArray).size)
    }

    // --- Security: the receiver is exported and the guide names no permission or package ---

    @Test
    fun `only the three declared functions are handled, anything else is dropped unread`() {
        // An explicit intent skips the action filter; its action went into the log and the reply verbatim.
        assertTrue(Gleo.handles("START_ROUTINE") && Gleo.handles("SET_ROUTINE_ENABLED") && Gleo.handles("GET_ROUTINES"))
        assertTrue(!Gleo.handles("FAKE\nRAN rain") && !Gleo.handles(null) && !Gleo.handles("x".repeat(100_000)))
    }

    @Test
    fun `a switched-off routine is not run on request`() {
        // Any app can ask; the driver turned it off on purpose (the screen's own test run stays as it is).
        val reply = assertIs<GleoDecision.Reply>(Gleo.start(routines, GleoParams.parse("""{"routineName":"퇴근길"}"""), parked = true))
        assertEquals(GleoStatus.FAIL, reply.reply.status)
    }

    @Test
    fun `switching to the state it is already in changes and saves nothing`() {
        val reply = assertIs<GleoDecision.Reply>(Gleo.setEnabled(routines, GleoParams.parse("""{"routineName":"겨울 출근","enabled":true}"""), parked = true))
        assertEquals(GleoStatus.SUCCESS, reply.reply.status)
        assertEquals("The routine '겨울 출근' is already on.", reply.reply.message)
    }

    @Test
    fun `oversized parameters are not read, and a spoken name is echoed short`() {
        assertNull(GleoParams.parse("""{"routineName":"${"가".repeat(5_000)}"}""").string("routineName"))
        val long = "없는루틴".repeat(30)
        val reply = assertIs<GleoDecision.Reply>(Gleo.start(routines, GleoParams.parse("""{"routineName":"$long"}"""), parked = true))
        assertTrue(long !in reply.reply.message && long.take(60) in reply.reply.message, reply.reply.message)
    }

    @Test
    fun `a one-letter routine name is not found inside any longer phrase`() {
        val withShort = routines + GleoRoutine("a", "A", enabled = true)
        assertTrue(GleoNames.match(withShort, "please run everything at once").isEmpty())
        assertEquals("a", GleoNames.match(withShort, "a").single().id)
    }

    @Test
    fun `requests are rationed, so a flood cannot push Gleo's own call past its 7 s`() {
        var now = 0L
        val gate = GleoGate(maxPerMinute = 3, maxInFlight = 1) { now }
        assertTrue(gate.tryEnter())
        assertTrue(!gate.tryEnter(), "one at a time")
        gate.leave()
        assertTrue(gate.tryEnter()); gate.leave()
        assertTrue(gate.tryEnter()); gate.leave()
        assertTrue(!gate.tryEnter(), "three a minute")
        now = 60_000
        assertTrue(gate.tryEnter())
    }

    @Test
    fun `starting while driving is refused, the app runs routines on request only when parked`() {
        val reply = assertIs<GleoDecision.Reply>(Gleo.start(routines, GleoParams.parse("""{"routineName":"겨울 출근"}"""), parked = false))
        assertEquals(GleoStatus.FAIL, reply.reply.status)
    }

    @Test
    fun `the answer after a run says what it did, in Gleo's words`() {
        val reply = Gleo.ran("겨울 출근", listOf(Status.CONFIRMED, Status.ALREADY_SET, Status.NOTIFIED))
        assertEquals(GleoStatus.SUCCESS, reply.status)
        assertEquals("Ran the routine '겨울 출근': 2 done, 1 only notified.", reply.message)
    }

    @Test
    fun `a step that waits by design is set for later, not waiting for the car`() {
        // A routine with a 10 minute wait must not be said to be "still waiting for the car".
        assertEquals("Ran the routine '겨울 출근': 1 done, 1 step set for later.", Gleo.ran("겨울 출근", listOf(Status.CONFIRMED, Status.SCHEDULED)).message)
    }

    @Test
    fun `on the demo car the answer says so`() {
        assertEquals("Ran the routine 'a' on the demo car: 1 done.", Gleo.ran("a", listOf(Status.CONFIRMED), simulated = true).message)
    }

    @Test
    fun `a routine can be switched on or off by voice, the car itself is not touched`() {
        val decision = assertIs<GleoDecision.SetEnabled>(Gleo.setEnabled(routines, GleoParams.parse("""{"routineName":"퇴근길","enabled":true}"""), parked = true))
        assertEquals("home", decision.routineId)
        assertEquals(true, decision.enabled)
    }

    @Test
    fun `while driving a routine can be switched off, not on`() {
        // Any app can send this; switching on lets a routine act on the car, like saving one does, which
        // the editor allows only while parked. Switching off only stops it, and a driver may want that on the move.
        val on = assertIs<GleoDecision.Reply>(Gleo.setEnabled(routines, GleoParams.parse("""{"routineName":"퇴근길","enabled":true}"""), parked = false))
        assertEquals(GleoStatus.FAIL, on.reply.status)
        assertIs<GleoDecision.SetEnabled>(Gleo.setEnabled(routines, GleoParams.parse("""{"routineName":"겨울 출근","enabled":false}"""), parked = false))
    }

    @Test
    fun `the list tells each routine and whether it is on, nothing more`() {
        // The reply is a broadcast any app may hear: names and on/off only, no history.
        val reply = Gleo.list(routines)
        val data = Json.parseToJsonElement(reply.data!!) as JsonArray
        assertEquals("비 오는 날", data[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(setOf("name", "enabled"), data[0].jsonObject.keys)
        assertTrue("2" in reply.message && "3" in reply.message, reply.message)
    }

    // --- After START_ROUTINE: what the run told within Gleo's time ---

    private val routine = Routine(id = "rain", name = "비 오는 날", trigger = kr.routinegongbang.core.model.Trigger.SignalEdge(SignalIds.STEERING_WHEEL_HEAT, to = setOf(kr.routinegongbang.core.model.SignalValue.of(1))), actions = listOf(kr.routinegongbang.core.model.Action.Notify("x")))
    private fun ui(lastRun: LastRun? = null, log: List<RunLogEntry> = emptyList()) = UiState(routines = listOf(RoutineItem(routine, lastRun)), log = log)
    private val oldRun = LastRun(listOf(ActionResult.NoChange), atEpochMs = 1, dryRun = true, simulated = false, runId = 1)

    @Test
    fun `nothing new yet is no answer`() {
        assertNull(GleoHeard.of(ui(oldRun), "rain", previous = oldRun, earlier = emptyList()))
    }

    @Test
    fun `a new run is the answer, and it is final unless a step waits for the car`() {
        val sent = LastRun(listOf(ActionResult.Sent(SignalIds.STEERING_WHEEL_HEAT)), 2, true, false, runId = 2)
        val heard = assertIs<GleoHeard.Ran>(GleoHeard.of(ui(sent), "rain", previous = oldRun, earlier = emptyList()))
        assertTrue(!heard.final)
        val scheduled = sent.copy(results = listOf(ActionResult.Scheduled))
        assertTrue(assertIs<GleoHeard.Ran>(GleoHeard.of(ui(scheduled), "rain", oldRun, emptyList())).final)
    }

    @Test
    fun `a late confirmation of the previous run is not the answer to this request`() {
        // The previous run's confirmation changes lastRun and must not be taken for the new run.
        val waiting = LastRun(listOf(ActionResult.Sent(SignalIds.STEERING_WHEEL_HEAT)), 1, true, false, runId = 4)
        val confirmed = waiting.copy(results = listOf(ActionResult.Confirmed(SignalIds.STEERING_WHEEL_HEAT)))
        assertNull(GleoHeard.of(ui(confirmed), "rain", previous = waiting, earlier = emptyList()))
    }

    @Test
    fun `an old skip copied by a later update is still old`() {
        val old = RunLogEntry(1, "rain", "비 오는 날", skip = Skip.Kind.COOLDOWN)
        assertNull(GleoHeard.of(ui(oldRun, listOf(old.copy())), "rain", oldRun, earlier = listOf(old)))
    }

    @Test
    fun `a new skip is the answer, an old one is not`() {
        val old = RunLogEntry(1, "rain", "비 오는 날", skip = Skip.Kind.COOLDOWN)
        assertNull(GleoHeard.of(ui(oldRun, listOf(old)), "rain", oldRun, earlier = listOf(old)))
        val fresh = RunLogEntry(2, "rain", "비 오는 날", skip = Skip.Kind.COOLDOWN)
        assertEquals(GleoHeard.Skipped(Skip.Kind.COOLDOWN), GleoHeard.of(ui(oldRun, listOf(fresh, old)), "rain", oldRun, earlier = listOf(old)))
    }

    @Test
    fun `the reply says what was heard, and never claims a start it did not see`() {
        assertEquals(GleoStatus.FAIL, Gleo.answer("a", GleoHeard.Skipped(Skip.Kind.COOLDOWN)).status)
        assertEquals("The routine 'a' did not run: it ran a short while ago and is resting.", Gleo.answer("a", GleoHeard.Skipped(Skip.Kind.COOLDOWN)).message)
        assertEquals("Asked the routine 'a' to run; it has not reported yet, the app will show how it went.", Gleo.answer("a", null).message)
        assertEquals("Ran the routine 'a': 1 done.", Gleo.answer("a", GleoHeard.Ran(oldRun)).message)
    }

    @Test
    fun `the response is the JSON Gleo reads`() {
        val json = Json.parseToJsonElement(GleoReply("START_ROUTINE", GleoStatus.SUCCESS, "Done.", "[]").toJson()).jsonObject
        assertEquals("START_ROUTINE", json["functionName"]!!.jsonPrimitive.content)
        assertEquals("success", json["status"]!!.jsonPrimitive.content)
        assertEquals("Done.", json["message"]!!.jsonPrimitive.content)
        assertEquals("[]", json["data"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the schema in the assets declares at most three functions, all in English`() {
        val schema = Json.parseToJsonElement(java.io.File("src/main/assets/schema.json").readText()).jsonObject
        assertEquals("kr.routinegongbang.app", schema["packageName"]!!.jsonPrimitive.content)
        val calls = schema["actionCalls"] as JsonArray
        assertTrue(calls.size <= 3)
        val names = calls.map { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
        assertEquals(setOf(Gleo.START_ROUTINE, Gleo.SET_ROUTINE_ENABLED, Gleo.GET_ROUTINES), names)
        val text = java.io.File("src/main/assets/schema.json").readText()
        assertTrue(text.none { it in '\uAC00'..'\uD7A3' }, "descriptions must be English")
    }

    @Test
    fun `a step still waiting for the car is said so, in proper English`() {
        val reply = Gleo.ran("비 오는 날", listOf(Status.WAITING))
        assertEquals("Ran the routine '비 오는 날': 1 step still waiting for the car.", reply.message)
        assertEquals("Ran the routine 'a': 2 done.", Gleo.ran("a", listOf(Status.CONFIRMED, Status.CONFIRMED)).message)
    }
}
