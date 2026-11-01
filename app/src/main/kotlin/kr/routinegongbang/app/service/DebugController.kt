package kr.routinegongbang.app.service

import android.content.Context
import android.os.Build
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kr.routinegongbang.app.BuildConfig
import kr.routinegongbang.app.ui.AppState
import kr.routinegongbang.app.ui.CapabilityOverride
import kr.routinegongbang.app.ui.DebugCommands
import kr.routinegongbang.app.ui.PlaybackState
import kr.routinegongbang.core.engine.CapabilitySnapshot
import kr.routinegongbang.core.engine.EngineEvent
import kr.routinegongbang.core.engine.RoutineEngine
import kr.routinegongbang.core.fake.CapabilityPreset
import kr.routinegongbang.core.fake.FakeVehicle
import kr.routinegongbang.core.fake.OffsetClock
import kr.routinegongbang.core.fake.PlaybackResult
import kr.routinegongbang.core.fake.QueuedInput
import kr.routinegongbang.core.fake.Scenario
import kr.routinegongbang.core.fake.ScenarioDriver
import kr.routinegongbang.core.fake.ScenarioJson
import kr.routinegongbang.core.fake.ScenarioPlayback
import kr.routinegongbang.core.fake.ScenarioStep
import kr.routinegongbang.core.habit.HabitBook
import kr.routinegongbang.core.model.SignalCatalog

/** Debug-only switches that must survive a service restart. */
internal object DebugSettings {
    private const val PREFS = "debug"
    private const val FAKE_VEHICLE = "fake_vehicle"
    private const val CLOCK_OFFSET = "fake_clock_offset_ms"
    private const val WIPE_FAKE = "wipe_fake_data"
    private const val SELF_TEST = "self_test"
    const val FAKE_PREFIX = "fake_"
    private const val DEMO_STARTED = "demo_started_at"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun fakeVehicle(context: Context): Boolean = prefs(context).getBoolean(FAKE_VEHICLE, false)

    fun setFakeVehicle(context: Context, enabled: Boolean) {
        val edit = prefs(context).edit().putBoolean(FAKE_VEHICLE, enabled)
        // Release demo mode ends by itself (DemoExpiry); the clock of its start is the real one.
        if (enabled) edit.putLong(DEMO_STARTED, System.currentTimeMillis())
        edit.apply()
    }

    fun demoStartedAt(context: Context): Long = prefs(context).getLong(DEMO_STARTED, 0)

    /** The fake car's clock offset: kept across restarts so its saved cooldowns and trips never lie in the future. */
    fun clockOffset(context: Context): Long = prefs(context).getLong(CLOCK_OFFSET, 0)

    fun setClockOffset(context: Context, ms: Long) {
        prefs(context).edit().putLong(CLOCK_OFFSET, ms).apply()
    }

    /** The next service start runs the signal self-test instead of the engine, once. */
    fun requestSelfTest(context: Context) {
        prefs(context).edit().putBoolean(SELF_TEST, true).commit()
    }

    /** The Android emulator's own hardware names; a real head unit never reports them. */
    fun isEmulator(hardware: String = Build.HARDWARE): Boolean = hardware == "ranchu" || hardware == "goldfish"

    fun takeSelfTest(context: Context): Boolean {
        if (!prefs(context).getBoolean(SELF_TEST, false)) return false
        prefs(context).edit().putBoolean(SELF_TEST, false).commit()
        return true
    }

    /** Asks the next fake-mode start to begin from nothing (after the old service has saved its last state). */
    fun requestFakeWipe(context: Context) {
        prefs(context).edit().putBoolean(WIPE_FAKE, true).commit()
    }

    /** Called once at a fake-mode start, before anything is loaded: deletes `fake_` files and the clock offset. */
    fun wipeFakeDataIfRequested(context: Context): Boolean {
        if (!prefs(context).getBoolean(WIPE_FAKE, false)) return false
        context.filesDir.listFiles { file: File -> file.name.startsWith(FAKE_PREFIX) }?.forEach { it.delete() }
        prefs(context).edit().putLong(CLOCK_OFFSET, 0).putBoolean(WIPE_FAKE, false).commit()
        return true
    }
}

/**
 * Debug panel commands: clock fast-forward, simulated capabilities
 * labelled "모의", the fake vehicle switch, and demo script playback on the fake vehicle. Created in every build: release
 * builds reach only the demo part (fake vehicle and playback); the rest refuses outside debug builds.
 * The clock moves only on the fake car, whose data is kept apart: a real car's records never get future times.
 */
internal class DebugController(
    private val context: Context,
    private val catalog: SignalCatalog,
    private val clock: OffsetClock,
    private val engine: RoutineEngine,
    private val controller: RoutineController,
    private val habits: HabitBook,
    private val scope: CoroutineScope,
    private val fakeCar: FakeVehicle?,
    private val realCapability: () -> CapabilitySnapshot,
) : DebugCommands {

    @Volatile private var playback: Job? = null
    @Volatile private var current: Scenario? = null
    @Volatile private var selected: CapabilityOverride? = null

    /** The fake car's echo pump stands aside while a script delivers echoes itself, so events keep their order. */
    val isPlaying: Boolean get() = playback?.isActive == true

    init {
        // A fresh service: nothing plays and nothing is overridden, whatever the previous instance last published.
        AppState.update {
            it.copy(debug = it.debug.copy(available = BuildConfig.DEBUG, demo = true, fakeVehicle = fakeCar != null, clockOffsetMs = clock.offsetMs, capability = null, playback = null))
        }
    }

    /** One tick after the jump lets time triggers, trip ends and ledger expiry catch up; skipped times never fire. */
    override fun advanceClock(ms: Long) {
        if (!BuildConfig.DEBUG) return
        if (fakeCar == null) return EngineLog.add("DEBUG clock moves only on the fake vehicle")
        if (isPlaying) return EngineLog.add("DEBUG clock is busy with playback")
        clock.advance(ms)
        engine.post(EngineEvent.Tick)
        AppState.update { it.copy(debug = it.debug.copy(clockOffsetMs = clock.offsetMs)) }
        DebugSettings.setClockOffset(context, clock.offsetMs)
        controller.refreshSuggestion()
        EngineLog.add("DEBUG clock +${ms / 60_000} min")
    }

    /** Monotonic time cannot go back under a running engine: the fake car starts over instead, from no data. */
    override fun resetClock() {
        if (!BuildConfig.DEBUG) return
        if (fakeCar == null) return EngineLog.add("DEBUG clock moves only on the fake vehicle")
        if (isPlaying) return EngineLog.add("DEBUG reset waits for playback")
        EngineLog.add("DEBUG reset: clock and fake vehicle data, restarting the service")
        DebugSettings.requestFakeWipe(context)
        RoutineService.restart(context)
    }

    override fun setCapability(override: CapabilityOverride?) {
        if (!BuildConfig.DEBUG) return
        selected = override
        val snapshot = override?.let { CapabilityPreset.valueOf(it.name).snapshot(catalog, simulated = true) } ?: realCapability()
        engine.post(EngineEvent.CapabilityChanged(snapshot))
        AppState.update { it.copy(debug = it.debug.copy(capability = override)) }
        EngineLog.add("DEBUG capability ${override ?: "real"}")
    }

    override fun setFakeVehicle(enabled: Boolean) {
        if (isPlaying) return EngineLog.add("DEBUG vehicle switch waits for playback")
        DebugSettings.setFakeVehicle(context, enabled)
        EngineLog.add("DEBUG fake vehicle $enabled: restarting the service")
        RoutineService.restart(context)
    }

    /** Only on the real (emulator) car; the engine is not running meanwhile, so no routine or habit sees the writes. */
    override fun selfTest() {
        if (!BuildConfig.DEBUG) return
        if (fakeCar != null) return EngineLog.add("DEBUG the self-test needs the real vehicle")
        if (isPlaying) return EngineLog.add("DEBUG the self-test waits for playback")
        // The self-test writes every comfort control, so only on the emulator and only parked.
        if (!DebugSettings.isEmulator()) return EngineLog.add("DEBUG the self-test runs on the emulator only")
        if (!AppState.ui.value.status.parked) return EngineLog.add("DEBUG the self-test needs the car parked")
        EngineLog.add("DEBUG self-test: restarting the service without the engine")
        DebugSettings.requestSelfTest(context)
        RoutineService.restart(context)
    }

    override fun play(script: String) {
        val car = fakeCar ?: return EngineLog.add("DEBUG playback needs the fake vehicle")
        if (isPlaying) return EngineLog.add("DEBUG playback already running")
        val scenario = runCatching { ScenarioJson.decode(checkNotNull(javaClass.getResource("/scenarios/$script")).readText()).getOrThrow() }
            .getOrElse { return EngineLog.add("DEBUG script $script unreadable: ${it.message}") }
        // Self-contained replays: the script's own routines and a fresh habit log, so every replay ends the same way.
        controller.replaceAll(scenario.routines)
        habits.clear()
        current = null
        launchPlayback(script, scenario.steps.size, car, after = null) { playback, onStep ->
            // Wall-clock times of the script are kept in the device timezone (the emulator runs on Pacific time).
            val replay = playback.replay(scenario, ZoneId.systemDefault(), onStep)
            current = replay.scenario
            replay.result
        }
    }

    override fun resume() {
        val state = AppState.ui.value.debug.playback ?: return
        val scenario = current ?: return
        val car = fakeCar ?: return
        if (state.phase != PlaybackState.Phase.PAUSED_FOR_SUGGESTION) return
        // The paused run may still be finishing its last lines; the next part starts after it.
        launchPlayback(state.script, scenario.steps.size, car, after = playback) { playback, onStep ->
            playback.play(scenario, state.step + 1, onStep)
        }
    }

    private fun launchPlayback(
        script: String,
        total: Int,
        car: FakeVehicle,
        after: Job?,
        body: suspend (ScenarioPlayback, (Int, ScenarioStep) -> Unit) -> PlaybackResult,
    ) {
        val driver = ScenarioDriver(catalog, QueuedInput(engine), car, clock) { event ->
            if (event is EngineEvent.DrivingChanged) controller.onDriving(event.state)
        }
        publish(script, 0, total, PlaybackState.Phase.RUNNING)
        playback = scope.launch(Dispatchers.Default) {
            after?.join()
            EngineLog.add("DEBUG playing $script")
            val result = try {
                body(ScenarioPlayback(driver)) { index, _ -> publish(script, index, total, PlaybackState.Phase.RUNNING) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Never leave the panel stuck on "playing": the script buttons come back with the reason.
                PlaybackResult.Finished(listOf("playback stopped: ${e.javaClass.simpleName}: ${e.message}"))
            }
            when (result) {
                is PlaybackResult.Paused -> {
                    publish(script, result.stepIndex, total, PlaybackState.Phase.PAUSED_FOR_SUGGESTION, result.problems)
                    controller.refreshSuggestion()
                }
                is PlaybackResult.Finished -> {
                    publish(script, total, total, PlaybackState.Phase.FINISHED, result.problems)
                    // Scripts set their own capability; afterwards the panel's choice holds again.
                    setCapability(selected)
                }
            }
            result.problems.forEach { EngineLog.add("DEBUG playback problem: $it") }
        }
    }

    private fun publish(script: String, step: Int, total: Int, phase: PlaybackState.Phase, problems: List<String> = emptyList()) {
        AppState.update { it.copy(debug = it.debug.copy(clockOffsetMs = clock.offsetMs, playback = PlaybackState(script, step, total, phase, problems))) }
        if (phase != PlaybackState.Phase.RUNNING) DebugSettings.setClockOffset(context, clock.offsetMs)
    }
}
