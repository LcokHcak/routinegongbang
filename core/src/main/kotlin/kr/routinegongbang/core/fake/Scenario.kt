package kr.routinegongbang.core.fake

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kr.routinegongbang.core.engine.Access
import kr.routinegongbang.core.engine.CapabilitySnapshot
import kr.routinegongbang.core.engine.DrivingState
import kr.routinegongbang.core.engine.Gear
import kr.routinegongbang.core.engine.Reason
import kr.routinegongbang.core.model.Routine
import kr.routinegongbang.core.model.SdkModule
import kr.routinegongbang.core.model.SignalCatalog
import kr.routinegongbang.core.model.SignalValue
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * A scripted drive for tests and the debug panel: routines, the car's starting values, and steps.
 * Times are ISO-8601 with offset ("2026-10-27T08:00:00+09:00"); signal ids are catalog ids.
 * Time model: like the service, the runner ticks once a minute (counted from [start]), so time triggers
 * may fire up to a minute late and ledger expiry is seen on the next tick.
 */
@Serializable
data class Scenario(
    val name: String,
    val start: String,
    val zone: String = "Asia/Seoul",
    val capability: CapabilityPreset = CapabilityPreset.FULL,
    val simulated: Boolean = false,
    val driving: DrivingPreset = DrivingPreset.PARKED,
    val routines: List<Routine> = emptyList(),
    val initial: Map<String, SignalValue> = emptyMap(),
    val steps: List<ScenarioStep> = emptyList(),
    /** How long the fake car takes to report a written value; past 3 s the late-echo grace applies. */
    val echoDelayMs: Long = FakeVehicle.DEFAULT_ECHO_DELAY_MS,
    /** Signals whose writes fail, to script ❌ results. */
    val failing: List<String> = emptyList(),
) {
    val startInstant: Instant get() = OffsetDateTime.parse(start).toInstant()
    val zoneId: ZoneId get() = ZoneId.of(zone)
}

enum class CapabilityPreset {
    /** Like the emulator: every SDK open, every signal READ_WRITE. */
    FULL,
    /** Like a real car on 2026-10-27: Vehicle Control, TTS, STT and location are Coming Soon. */
    CONTROL_CLOSED,
    /** Every SDK open but only read access. */
    NO_PERMISSION;

    fun snapshot(catalog: SignalCatalog, simulated: Boolean): CapabilitySnapshot {
        val access = if (this == NO_PERMISSION) Access.READ else Access.READ_WRITE
        val open = when (this) {
            FULL, NO_PERMISSION -> SdkModule.entries.toSet()
            CONTROL_CLOSED -> setOf(SdkModule.VEHICLE_STATE, SdkModule.NAVI, SdkModule.AAOS_CAR_PROPERTY)
        }
        return CapabilitySnapshot(open, catalog.all.associate { it.id to access }, simulated)
    }
}

enum class DrivingPreset(val state: DrivingState) {
    PARKED(DrivingState(Gear.PARK, speedKph = 0.0, uxRestricted = false)),
    DRIVING(DrivingState(Gear.DRIVE, speedKph = 60.0, uxRestricted = true)),
}

@Serializable
data class ExpectedWrite(val signal: String, val value: SignalValue)

/** [reason] null matches any reason. */
@Serializable
data class ExpectedNotification(val text: String, val reason: Reason? = null)

@Serializable
sealed interface ScenarioStep {
    val kind: String

    /** A change made outside the app: by the driver, or reported by the car itself (SOC, wiper state). */
    @Serializable @SerialName("user")
    data class User(val signal: String, val value: SignalValue) : ScenarioStep { override val kind get() = "user" }

    /** Moves time forward, delivering echoes when due and a tick every minute, like the real service. */
    @Serializable @SerialName("advance")
    data class Advance(val ms: Long) : ScenarioStep { override val kind get() = "advance" }

    /** Advances to an absolute time (never backwards). */
    @Serializable @SerialName("at")
    data class At(val time: String) : ScenarioStep { override val kind get() = "at" }

    @Serializable @SerialName("drive")
    data class Drive(val state: DrivingPreset) : ScenarioStep { override val kind get() = "drive" }

    @Serializable @SerialName("navigating")
    data class Navigating(val value: Boolean) : ScenarioStep { override val kind get() = "navigating" }

    @Serializable @SerialName("naviArrived")
    data object NaviArrived : ScenarioStep { override val kind get() = "naviArrived" }

    @Serializable @SerialName("capability")
    data class Capability(val preset: CapabilityPreset, val simulated: Boolean = false) : ScenarioStep { override val kind get() = "capability" }

    @Serializable @SerialName("confirmAnswer")
    data class ConfirmAnswer(val value: Boolean) : ScenarioStep { override val kind get() = "confirmAnswer" }

    @Serializable @SerialName("runNow")
    data class RunNow(val routineId: String) : ScenarioStep { override val kind get() = "runNow" }

    /** Unlock if locked, then the driver sits down. Fails unless a new trip starts (needs a known seat value). */
    @Serializable @SerialName("rideStart")
    data object RideStart : ScenarioStep { override val kind get() = "rideStart" }

    /** Park, the driver gets out, locks, and the trip end delay plus one tick (6 minutes) passes. Fails if no trip ends. */
    @Serializable @SerialName("rideEnd")
    data object RideEnd : ScenarioStep { override val kind get() = "rideEnd" }

    /** Accepts the current habit suggestion: records ACCEPTED and adds its routine. */
    @Serializable @SerialName("acceptSuggestion")
    data object AcceptSuggestion : ScenarioStep { override val kind get() = "acceptSuggestion" }

    /**
     * Checks what happened since the previous expect step. Omitted fields are not checked.
     * [values] are current car values; [confirmed] are signals whose write was confirmed by an echo;
     * [simulated] checks that every routine run in the interval was labelled as simulated (or not).
     * [decisions] lists the routine decisions in order, so "nothing happened" can say why:
     * `id:ConditionNotMet`, `id:LimitBlocked(COOLDOWN)`, `id:Ran(Sent,Skipped:USER_OVERRIDE,Notified)`,
     * `id:DryRun(...)`. Result labels: Sent, NoChange, Failed, Notified[:REASON], Cancelled:REASON,
     * Skipped:REASON, Deferred, Requested.
     */
    @Serializable @SerialName("expect")
    data class Expect(
        val writes: List<ExpectedWrite>? = null,
        val notifications: List<ExpectedNotification>? = null,
        val destinations: List<String>? = null,
        val values: Map<String, SignalValue>? = null,
        val confirmed: List<String>? = null,
        val confirmations: Int? = null,
        val suggestion: ExpectedWrite? = null,
        val noSuggestion: Boolean = false,
        val simulated: Boolean? = null,
        val decisions: List<String>? = null,
    ) : ScenarioStep { override val kind get() = "expect" }
}

object ScenarioJson {
    const val MAX_INPUT_LENGTH: Int = 512 * 1024

    private val json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = false
    }

    /** Fails on malformed JSON, unknown fields (typos in a test script), a bad start time or zone. */
    fun decode(text: String): Result<Scenario> {
        if (text.length > MAX_INPUT_LENGTH) return Result.failure(IllegalArgumentException("Scenario too large: ${text.length}"))
        return try {
            val scenario = json.decodeFromString(Scenario.serializer(), text)
            scenario.startInstant
            scenario.zoneId
            scenario.steps.filterIsInstance<ScenarioStep.At>().forEach { OffsetDateTime.parse(it.time) }
            require(scenario.echoDelayMs >= 0) { "echoDelayMs must not be negative" }
            Result.success(scenario)
        } catch (e: IllegalArgumentException) {
            Result.failure(e)
        } catch (e: DateTimeException) {
            Result.failure(e)
        }
    }

    fun encode(scenario: Scenario): String = json.encodeToString(Scenario.serializer(), scenario)
}

/**
 * Replays a script on a live clock: its wall-clock times ("Monday 08:00") are kept in [zone], the device timezone,
 * and moved forward by whole weeks so it starts at or after [now]. Weekdays and times of day, which routines and
 * habits depend on, stay the same.
 */
fun Scenario.rebasedOnto(now: Instant, zone: ZoneId = zoneId): Scenario {
    fun local(time: String) = OffsetDateTime.parse(time).toLocalDateTime()
    val startInZone = local(start).atZone(zone).toInstant()
    if (zone == zoneId && !startInZone.isBefore(now)) return this
    val behind = Duration.between(startInZone, now).toMillis()
    val weeks = if (behind <= 0) 0 else (behind + WEEK_MS - 1) / WEEK_MS
    fun shift(time: String): String = local(time).plusWeeks(weeks).atZone(zone).toOffsetDateTime().toString()
    return copy(start = shift(start), zone = zone.id, steps = steps.map { if (it is ScenarioStep.At) it.copy(time = shift(it.time)) else it })
}

private const val WEEK_MS: Long = 7 * 24 * 60 * 60_000L
