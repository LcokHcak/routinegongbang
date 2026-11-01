package kr.routinegongbang.app.gleo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kr.routinegongbang.app.ui.UiState
import kr.routinegongbang.app.ui.model.LastRun
import kr.routinegongbang.app.ui.model.Outcome
import kr.routinegongbang.app.ui.model.RunLogEntry
import kr.routinegongbang.app.ui.model.Skip
import kr.routinegongbang.app.ui.model.Status
import kr.routinegongbang.app.ui.model.StatusView
import kr.routinegongbang.core.engine.ActionResult

/*
 * Gleo Interface (Pleos App Integration Guide). Gleo calls one of the functions declared in
 * assets/schema.json by intent action, with the parameters as one JSON string extra, and reads one reply broadcast.
 * Voice is the one way a driver may ask for something while driving, so these replies are what the driver hears.
 * Everything here is pure; GleoReceiver does the Android part. Messages are English, as the guide asks, with
 * routine names as they are.
 */

/** What Gleo needs to know of a routine; the replies are broadcasts any app may hear, so nothing more. */
data class GleoRoutine(val id: String, val name: String, val enabled: Boolean)

enum class GleoStatus(val wire: String) { SUCCESS("success"), ASKING("asking"), FAIL("fail") }

/** The APP_ACTION_RESPONSE payload; [data] is a JSON array of objects, serialised as a string. */
data class GleoReply(val functionName: String, val status: GleoStatus, val message: String, val data: String? = null) {
    fun toJson(): String = buildJsonObject {
        put("functionName", functionName)
        put("status", status.wire)
        put("message", message)
        data?.let { put("data", it) }
    }.toString()
}

sealed interface GleoDecision {
    data class Reply(val reply: GleoReply) : GleoDecision
    data class Start(val routineId: String, val name: String) : GleoDecision
    data class SetEnabled(val routineId: String, val name: String, val enabled: Boolean) : GleoDecision
}

/**
 * What a START_ROUTINE run has told so far: its report, or why it did not run. Without either, the
 * reply must not claim the routine started.
 */
sealed interface GleoHeard {
    /** [final] unless a step still waits for the car; a wait or a later reminder is set for later by design. */
    data class Ran(val run: LastRun) : GleoHeard {
        val final: Boolean get() = run.results.none { it is ActionResult.Sent }
    }

    data class Skipped(val kind: Skip.Kind) : GleoHeard

    companion object {
        /**
         * [previous] is the routine's last run and [earlier] the log before the request; only what is newer counts. A run
         * is new by its id: a late confirmation of the previous run changes that run, not this one. A skip is new by its
         * content: updates copy the log's entries.
         */
        fun of(ui: UiState, routineId: String, previous: LastRun?, earlier: List<RunLogEntry>): GleoHeard? {
            ui.routines.firstOrNull { it.routine.id == routineId }?.lastRun?.takeIf { previous == null || it.runId != previous.runId }?.let { return Ran(it) }
            return ui.log.firstOrNull { entry -> entry.routineId == routineId && entry.skip != null && entry !in earlier }
                ?.skip?.let(::Skipped)
        }
    }
}

/**
 * The "ACTION_PARAM" extra. The guide's own sample wraps the JSON in an extra pair of quotes, so a JSON string whose
 * content is JSON is unwrapped once; anything unreadable is treated as no parameters.
 */
class GleoParams private constructor(private val values: JsonObject) {
    fun string(name: String): String? = (values[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    fun boolean(name: String): Boolean? = (values[name] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }

    companion object {
        /** Two names and a flag fit many times over; anything longer is not read. */
        private const val MAX_CHARS = 2_048

        fun parse(raw: String?): GleoParams {
            val element = raw?.takeIf { it.length <= MAX_CHARS }?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
            val unwrapped = (element as? JsonPrimitive)?.takeIf { it.isString }?.let { runCatching { Json.parseToJsonElement(it.content) }.getOrNull() } ?: element
            return GleoParams(unwrapped as? JsonObject ?: JsonObject(emptyMap()))
        }
    }
}

/** Spoken names are loose: spaces and case do not matter, and a part of the name is enough when it is unique. */
object GleoNames {
    private const val MIN_CONTAINED = 2

    private fun key(text: String) = text.filterNot { it.isWhitespace() }.lowercase()

    fun match(routines: List<GleoRoutine>, spoken: String): List<GleoRoutine> {
        val wanted = key(spoken)
        if (wanted.isEmpty()) return emptyList()
        // A routine without a name is never what was said; its empty key is contained in every name.
        val named = routines.filter { key(it.name).isNotEmpty() }
        named.filter { key(it.name) == wanted }.takeIf { it.isNotEmpty() }?.let { return it }
        // A one-letter name would be found inside almost any phrase.
        return named.filter { key(it.name).contains(wanted) || (key(it.name).length >= MIN_CONTAINED && wanted.contains(key(it.name))) }
    }
}

object Gleo {
    const val START_ROUTINE = "START_ROUTINE"
    const val SET_ROUTINE_ENABLED = "SET_ROUTINE_ENABLED"
    const val GET_ROUTINES = "GET_ROUTINES"
    const val PARAM_NAME = "routineName"
    const val PARAM_ENABLED = "enabled"
    private val FUNCTIONS = setOf(START_ROUTINE, SET_ROUTINE_ENABLED, GET_ROUTINES)
    /** A spoken name is said back at most this long; the reply is heard by any app. */
    private const val MAX_ECHO = 60

    /**
     * Only the functions of assets/schema.json. An explicit intent skips the action filter, and its action would reach
     * the log and the reply as it is.
     */
    fun handles(action: String?): Boolean = action in FUNCTIONS

    /** Runs a routine now, the same path as "지금 실행해보기": parked only, every safety rule still applies. */
    fun start(routines: List<GleoRoutine>, params: GleoParams, parked: Boolean): GleoDecision {
        val routine = when (val found = find(START_ROUTINE, routines, params)) {
            is Found.One -> found.routine
            is Found.Ask -> return GleoDecision.Reply(found.reply)
        }
        // The driver turned it off on purpose, and any app can send this request. The screen's
        // own test run still works for a switched-off routine.
        if (!routine.enabled) return reply(START_ROUTINE, GleoStatus.FAIL, "The routine '${routine.name}' is turned off; turn it on in the app to run it.")
        if (!parked) return reply(START_ROUTINE, GleoStatus.FAIL, "The routine '${routine.name}' can be run on request only while the car is parked.")
        return GleoDecision.Start(routine.id, routine.name)
    }

    /**
     * Switching off only stops a routine, so it works while driving. Switching on lets it act on the car, like saving
     * one, which the editor allows only while parked; any app can send this request.
     */
    fun setEnabled(routines: List<GleoRoutine>, params: GleoParams, parked: Boolean): GleoDecision {
        val routine = when (val found = find(SET_ROUTINE_ENABLED, routines, params)) {
            is Found.One -> found.routine
            is Found.Ask -> return GleoDecision.Reply(found.reply)
        }
        val enabled = params.boolean(PARAM_ENABLED)
            ?: return reply(SET_ROUTINE_ENABLED, GleoStatus.ASKING, "Should the routine '${routine.name}' be turned on or off?", missing(PARAM_ENABLED))
        // Already so: nothing to change, save or tell the engine (a flood would otherwise save on every call).
        if (enabled == routine.enabled) return reply(SET_ROUTINE_ENABLED, GleoStatus.SUCCESS, "The routine '${routine.name}' is already ${if (enabled) "on" else "off"}.")
        if (enabled && !parked) {
            return reply(SET_ROUTINE_ENABLED, GleoStatus.FAIL, "The routine '${routine.name}' can be turned on only while the car is parked; turning it off works any time.")
        }
        return GleoDecision.SetEnabled(routine.id, routine.name, enabled)
    }

    fun enabledChanged(name: String, enabled: Boolean) =
        GleoReply(SET_ROUTINE_ENABLED, GleoStatus.SUCCESS, "Turned ${if (enabled) "on" else "off"} the routine '$name'.")

    fun list(routines: List<GleoRoutine>): GleoReply {
        val on = routines.count { it.enabled }
        val data = buildJsonArray {
            routines.forEach { routine ->
                add(buildJsonObject {
                    put("name", routine.name)
                    put("enabled", routine.enabled)
                })
            }
        }
        return GleoReply(GET_ROUTINES, GleoStatus.SUCCESS, "You have ${routines.size} routines, $on of them turned on.", data.toString())
    }

    /** What to say once Gleo's time is up: the run, why it did not run, or honestly that nothing came back yet. */
    fun answer(name: String, heard: GleoHeard?): GleoReply = when (heard) {
        is GleoHeard.Ran -> ran(name, heard.run.results.map { StatusView.of(it).status }, heard.run.simulated)
        is GleoHeard.Skipped -> GleoReply(START_ROUTINE, GleoStatus.FAIL, "The routine '$name' did not run: ${because(heard.kind)}.")
        null -> GleoReply(START_ROUTINE, GleoStatus.SUCCESS, "Asked the routine '$name' to run; it has not reported yet, the app will show how it went.")
    }

    /**
     * After a run: what it did, as counted on the card (했어요 / 알려드렸어요 / 안 했어요). A step waiting by design
     * (a wait, a reminder for parking) is set for later, not waiting for the car.
     */
    fun ran(name: String, statuses: List<Status>, simulated: Boolean = false): GleoReply {
        fun steps(n: Int) = if (n == 1) "1 step" else "$n steps"
        val outcomes = statuses.map { Outcome.of(it) }
        val done = outcomes.count { it == Outcome.DONE }
        val told = outcomes.count { it == Outcome.TOLD }
        val notDone = outcomes.count { it == Outcome.NOT_DONE || it == Outcome.UNCONFIRMED }
        val waiting = statuses.count { it == Status.WAITING }
        val later = statuses.count { it == Status.SCHEDULED || it == Status.LATER }
        val parts = listOfNotNull(
            "$done done".takeIf { done > 0 },
            "$told only notified".takeIf { told > 0 },
            "$notDone not done".takeIf { notDone > 0 },
            "${steps(waiting)} still waiting for the car".takeIf { waiting > 0 },
            "${steps(later)} set for later".takeIf { later > 0 },
        ).ifEmpty { listOf("nothing to do") }
        val where = if (simulated) " on the demo car" else ""
        return GleoReply(START_ROUTINE, GleoStatus.SUCCESS, "Ran the routine '$name'$where: ${parts.joinToString(", ")}.")
    }

    fun notReady(function: String) = GleoReply(function, GleoStatus.FAIL, "The routine app is still starting, please try again in a moment.")

    fun unknown(function: String) = GleoReply(function, GleoStatus.FAIL, "This request is not supported.")

    fun failed(function: String) = GleoReply(function, GleoStatus.FAIL, "Something went wrong in the routine app; please try again.")

    fun busy(function: String) = GleoReply(function, GleoStatus.FAIL, "The routine app is busy; please try again in a moment.")

    /** The card's skip reasons (strings.xml skip_*), in Gleo's English. */
    private fun because(kind: Skip.Kind): String = when (kind) {
        Skip.Kind.CONDITION -> "its conditions are not met right now"
        Skip.Kind.NOT_READ_YET -> "its conditions are not met, or the car has not reported what they need yet"
        Skip.Kind.COOLDOWN -> "it ran a short while ago and is resting"
        Skip.Kind.DAILY_LIMIT -> "it has already run as often as allowed today"
        Skip.Kind.TRIP_LIMIT -> "it has already run on this trip"
        Skip.Kind.SUSPENDED -> "it stopped after failing several times in a row; saving it again restarts it"
        Skip.Kind.STILL_WAITING -> "its previous run has not finished yet"
    }

    private sealed interface Found {
        data class One(val routine: GleoRoutine) : Found
        data class Ask(val reply: GleoReply) : Found
    }

    private fun find(function: String, routines: List<GleoRoutine>, params: GleoParams): Found {
        val spoken = params.string(PARAM_NAME)
            ?: return Found.Ask(GleoReply(function, GleoStatus.ASKING, "Which routine do you mean?", names(routines)))
        val matches = GleoNames.match(routines, spoken)
        return when (matches.size) {
            1 -> Found.One(matches.single())
            0 -> Found.Ask(GleoReply(function, GleoStatus.ASKING, "There is no routine called '${spoken.take(MAX_ECHO)}'; which one do you mean?", names(routines)))
            else -> Found.Ask(GleoReply(function, GleoStatus.ASKING, "More than one routine matches '${spoken.take(MAX_ECHO)}'; which one do you mean?", names(matches)))
        }
    }

    private fun names(routines: List<GleoRoutine>): String = buildJsonArray { routines.forEach { add(buildJsonObject { put("name", it.name) }) } }.toString()

    private fun missing(field: String): String = buildJsonArray { add(buildJsonObject { put("missingField", field) }) }.toString()

    private fun reply(function: String, status: GleoStatus, message: String, data: String? = null) = GleoDecision.Reply(GleoReply(function, status, message, data))
}

/**
 * Rations requests: any app can call, each run holds its slot up to 4 s, and a flood would push
 * Gleo's own call past its 7 s and evict the log. Over the limit the answer is an immediate "busy".
 */
class GleoGate(
    private val maxPerMinute: Int = MAX_PER_MINUTE,
    private val maxInFlight: Int = MAX_IN_FLIGHT,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val recent = ArrayDeque<Long>()
    private var inFlight = 0

    @Synchronized
    fun tryEnter(): Boolean {
        val at = now()
        while (recent.isNotEmpty() && at - recent.first() >= WINDOW_MS) recent.removeFirst()
        if (inFlight >= maxInFlight || recent.size >= maxPerMinute) return false
        recent.addLast(at)
        inFlight++
        return true
    }

    @Synchronized
    fun leave() {
        if (inFlight > 0) inFlight--
    }

    private companion object {
        const val WINDOW_MS = 60_000L
        /** A driver asks a few times a minute at most. */
        const val MAX_PER_MINUTE = 10
        const val MAX_IN_FLIGHT = 2
    }
}
