package kr.routinegongbang.app.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kr.routinegongbang.app.ui.editor.CheckResult
import kr.routinegongbang.app.ui.model.LastRun
import kr.routinegongbang.app.ui.model.RunLogEntry
import kr.routinegongbang.app.ui.model.Skip
import kr.routinegongbang.app.ui.model.TripReceipt
import kr.routinegongbang.core.habit.HabitPattern
import kr.routinegongbang.core.model.Routine

/** Car and platform state shown in the status bar. */
data class VehicleStatus(
    val engineReady: Boolean = false,
    val vehicleConnected: Boolean = false,
    val parked: Boolean = false,
    /** False until gear, speed and UX restrictions are all known. */
    val drivingKnown: Boolean = false,
    val controlOpen: Boolean = false,
    /** Debug build on the emulator: writes are real there but must be labelled "에뮬레이터 기준". */
    val emulator: Boolean = false,
)

/** [trustYes]: the yeses in a row a routine that asks first has heard (Trust). */
data class RoutineItem(val routine: Routine, val lastRun: LastRun?, val lastSkip: Skip? = null, val trustYes: Int = 0)

data class SuggestionItem(val tripId: String, val pattern: HabitPattern, val routine: Routine, val supportingTrips: Int)

data class UiState(
    val status: VehicleStatus = VehicleStatus(),
    val routines: List<RoutineItem> = emptyList(),
    val suggestion: SuggestionItem? = null,
    val debug: DebugState = DebugState(),
    /** Recent runs and skips, newest first (RunLog). */
    val log: List<RunLogEntry> = emptyList(),
    /** What the routines did on the drive that just ended; cleared when the next trip starts. */
    val receipt: TripReceipt? = null,
)

/** Simulated capability presets of the debug panel (results are labelled "모의"). */
enum class CapabilityOverride { FULL, CONTROL_CLOSED, NO_PERMISSION }

/** Debug panel and demo mode state; release builds use only the demo part. */
data class DebugState(
    /** The full developer panel: debug builds only. */
    val available: Boolean = false,
    /** Demo mode (scenario playback on the simulated car): every build, so reviewers can see the stories. */
    val demo: Boolean = false,
    /** Fake vehicle instead of the Pleos SDK; data lives in separate `fake_` files. */
    val fakeVehicle: Boolean = false,
    val clockOffsetMs: Long = 0,
    val capability: CapabilityOverride? = null,
    val playback: PlaybackState? = null,
) {
    /** A script is driving the fake car: the clock and the vehicle switch wait until it stops. */
    val playing: Boolean get() = playback?.phase == PlaybackState.Phase.RUNNING
}

data class PlaybackState(val script: String, val step: Int, val total: Int, val phase: Phase, val problems: List<String> = emptyList()) {
    enum class Phase { RUNNING, PAUSED_FOR_SUGGESTION, FINISHED }
}

/** Debug panel commands; release builds use only the demo ones (fake vehicle, play, resume), the rest refuse. */
interface DebugCommands {
    fun advanceClock(ms: Long)
    fun resetClock()
    fun setCapability(override: CapabilityOverride?)
    fun setFakeVehicle(enabled: Boolean)
    /** Restarts the service without the engine and tests every signal of the real (emulator) car. */
    fun selfTest()
    /** Plays a demo script on the fake vehicle (fake mode only); pauses at the suggestion. */
    fun play(script: String)
    fun resume()
}

/** What the screen can ask the engine service to do. Calls return at once; results arrive through [AppState.ui]. */
interface RoutineCommands {
    fun setEnabled(routineId: String, enabled: Boolean)
    /** "지금 실행해보기": the engine refuses while not parked and still applies every safety rule. */
    fun runNow(routineId: String)
    fun acceptSuggestion()
    fun rejectSuggestion()
    /** The card was on screen; unanswered showings count toward an implicit rejection. */
    fun suggestionShown()

    /**
     * Saves a new or edited routine if it passes the save check; an edited one keeps its place in the
     * list (its priority). Returns what the check found either way.
     */
    fun saveRoutine(routine: Routine): CheckResult

    fun deleteRoutine(routineId: String)
}

/** Process-wide bridge between the engine service and the screen. */
object AppState {
    private val state = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = state.asStateFlow()

    @Volatile var commands: RoutineCommands? = null
        internal set

    @Volatile var debug: DebugCommands? = null
        internal set

    /**
     * Runtime permissions still missing, kept by the activity (the service resets [ui] when it restarts), and how to
     * ask for them again: the system dialog, or the app's settings once the system no longer asks.
     */
    private val missing = MutableStateFlow<Set<String>>(emptySet())
    val missingPermissions: StateFlow<Set<String>> = missing.asStateFlow()

    fun setMissingPermissions(permissions: Set<String>) {
        missing.value = permissions
    }
    @Volatile var fixPermissions: (() -> Unit)? = null
        internal set

    internal fun update(change: (UiState) -> UiState) = state.update(change)
}
