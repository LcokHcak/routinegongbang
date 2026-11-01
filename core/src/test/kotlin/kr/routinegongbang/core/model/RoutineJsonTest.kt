package kr.routinegongbang.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoutineJsonTest {

    private val routine = Routine(
        id = "r_rain",
        name = "비 오는 날",
        trigger = Trigger.SignalEdge(SignalIds.WIPER_FRONT_STATE, from = setOf(SignalValue.Text("OFF")), to = setOf(SignalValue.Text("ON"))),
        condition = Condition.And(
            listOf(
                Condition.Compare(SignalIds.SEAT_OCCUPIED_DRIVER, CompareOp.EQ, SignalValue.Bool(true)),
                Condition.DayType(weekdays = true, weekends = false),
            ),
        ),
        actions = listOf(
            Action.Write(SignalIds.DEFROSTER_FRONT, SignalValue.Bool(true)),
            Action.Write(SignalIds.STEERING_WHEEL_HEAT, SignalValue.of(1)),
            Action.Notify("routine_rain_done"),
        ),
        limits = Limits(cooldownMinutes = 30, maxPerDay = 3),
        origin = Origin.TEMPLATE,
    )

    @Test
    fun `routine survives a JSON round trip`() {
        assertEquals(routine, RoutineJson.decode(RoutineJson.encode(routine)).getOrThrow())
    }

    @Test
    fun `JSON uses readable type names and primitive values`() {
        val json = RoutineJson.encode(routine)
        assertTrue("\"type\":\"SignalEdge\"" in json, json)
        assertTrue("\"to\":[\"ON\"]" in json, json)
        assertTrue("\"value\":true" in json, json)
    }

    @Test
    fun `integer written as 1 decodes to the same value as 1_0`() {
        val json = RoutineJson.encode(routine).replace("\"value\":1.0", "\"value\":1")
        assertEquals(routine, RoutineJson.decode(json).getOrThrow())
    }

    @Test
    fun `malformed JSON returns a failure instead of throwing`() {
        assertTrue(RoutineJson.decode("{ not json").isFailure)
    }

    @Test
    fun `unknown action type returns a failure`() {
        val json = RoutineJson.encode(routine).replace("\"type\":\"Notify\"", "\"type\":\"SelfDestruct\"")
        assertTrue(RoutineJson.decode(json).isFailure)
    }

    @Test
    fun `oversized input is rejected before parsing`() {
        val huge = "{\"pad\":\"" + "x".repeat(RoutineJson.MAX_INPUT_LENGTH) + "\"}"
        assertTrue(RoutineJson.decode(huge).isFailure)
    }

    @Test
    fun `unknown fields from newer versions are ignored`() {
        val json = RoutineJson.encode(routine).replaceFirst("{", "{\"futureField\":1,")
        assertEquals(routine, RoutineJson.decode(json).getOrThrow())
    }

    @Test
    fun `a stored list round trips and one broken entry does not lose the others`() {
        val other = routine.copy(id = "r_other")
        val text = RoutineJson.encodeList(listOf(routine, other))
        assertEquals(listOf(routine, other), RoutineJson.decodeList(text).getOrThrow().map { it.getOrThrow() })

        val broken = text.replaceFirst("\"type\":\"Notify\"", "\"type\":\"SelfDestruct\"")
        val entries = RoutineJson.decodeList(broken).getOrThrow()
        assertTrue(entries[0].isFailure)
        assertEquals(other, entries[1].getOrThrow())
    }

    @Test
    fun `a list file that is not an array fails as a whole`() {
        assertTrue(RoutineJson.decodeList("{}").isFailure)
        assertTrue(RoutineJson.decodeList("[").isFailure)
    }
}
