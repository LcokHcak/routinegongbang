package kr.routinegongbang.app.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kr.routinegongbang.app.BuildConfig
import kr.routinegongbang.app.R
import kr.routinegongbang.app.aaos.AaosVehicleProperties
import kr.routinegongbang.app.data.FileEngineStore
import kr.routinegongbang.app.data.FileHabitStore
import kr.routinegongbang.app.data.RoutineRepository
import kr.routinegongbang.app.platform.AndroidNotifier
import kr.routinegongbang.app.platform.Channels
import kr.routinegongbang.app.platform.CrashRecorder
import kr.routinegongbang.app.platform.NoticeText
import kr.routinegongbang.app.platform.NotificationConfirmer
import kr.routinegongbang.app.pleos.PleosBindings
import kr.routinegongbang.app.pleos.PleosNavigator
import kr.routinegongbang.app.pleos.PleosVehicle
import kr.routinegongbang.app.ui.AppState
import kr.routinegongbang.app.ui.Texts
import kr.routinegongbang.app.ui.UiState
import kr.routinegongbang.app.ui.DemoExpiry
import kr.routinegongbang.core.engine.Access
import kr.routinegongbang.core.engine.ActionPlanner
import kr.routinegongbang.core.engine.NotifyMessage
import kr.routinegongbang.core.engine.CapabilitySnapshot
import kr.routinegongbang.core.engine.EngineClock
import kr.routinegongbang.core.engine.EngineEvent
import kr.routinegongbang.core.engine.EngineStartup
import kr.routinegongbang.core.engine.Executor
import kr.routinegongbang.core.engine.Navigator
import kr.routinegongbang.core.engine.RoutineEngine
import kr.routinegongbang.core.engine.VehicleWriter
import kr.routinegongbang.core.fake.CapabilityPreset
import kr.routinegongbang.core.fake.DrivingPreset
import kr.routinegongbang.core.fake.FakeNavigator
import kr.routinegongbang.core.fake.FakeVehicle
import kr.routinegongbang.core.fake.OffsetClock
import kr.routinegongbang.core.habit.HabitBook
import kr.routinegongbang.core.habit.HabitDetector
import kr.routinegongbang.core.habit.HabitLog
import kr.routinegongbang.core.habit.HabitRecorder
import kr.routinegongbang.core.model.Routine
import kr.routinegongbang.core.model.SdkModule
import kr.routinegongbang.core.model.SignalCatalog
import kr.routinegongbang.core.model.SignalId
import kr.routinegongbang.core.model.SignalIds
import kr.routinegongbang.core.model.SignalValue
import kr.routinegongbang.core.platform.CapabilityAssembler
import kr.routinegongbang.core.platform.SelfTestCar
import kr.routinegongbang.core.platform.SignalSelfTest
import kr.routinegongbang.core.platform.toMarkdown
import kr.routinegongbang.core.platform.PermissionKind
import kr.routinegongbang.core.platform.StartupBuffer
import java.io.File
import java.time.ZoneId

/**
 * Hosts the engine. All engine state lives on one single-threaded
 * dispatcher; adapters only call `engine.post`, which is thread-safe. No adapter failure may stop the engine:
 * a missing vehicle service, AAOS property or navigation only lowers the capability snapshot.
 * It can run on a fake vehicle (debug panel; release demo mode) whose clock may move; its data is
 * kept apart.
 */
class RoutineService : Service() {

    private val failures = CoroutineExceptionHandler { _, e -> EngineLog.add("SERVICE ERROR ${e.javaClass.simpleName}: ${e.message}") }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1) + failures)
    private val catalog = SignalCatalog.default()
    private val log: (String) -> Unit = { EngineLog.add(it) }

    /** Moves only for the simulated car (the debug panel, release demo mode); the real car always runs on real time. */
    private val clock = OffsetClock(EngineClock.SYSTEM)
    private var fakeMode = false
    private val prefix get() = if (fakeMode) DebugSettings.FAKE_PREFIX else ""

    private lateinit var vehicle: PleosVehicle
    private lateinit var aaos: AaosVehicleProperties
    private lateinit var navigator: PleosNavigator
    @Volatile private var habits: HabitBook? = null
    private val habitStore by lazy { FileHabitStore(this, prefix) }
    private val habitSaves = Channel<HabitLog>(Channel.CONFLATED)
    /** One writer for routines.json: AtomicFile is not safe for concurrent writers, and the latest list must win. */
    private val routineSaves = Channel<List<Routine>>(Channel.CONFLATED)
    @Volatile private var navigating = false
    @Volatile private var realCapability: CapabilitySnapshot? = null
    @Volatile private var debug: DebugController? = null
    private var selfTestJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        CrashRecorder.install(this)
        // A crash ended an earlier process: say so once, with the newest exception line (the stack: debug builds only).
        CrashRecorder.takeLast(CrashRecorder.file(this))?.let {
            if (BuildConfig.DEBUG) Log.w(EngineLog.TAG, it)
            EngineLog.add("PREVIOUS CRASH ${CrashRecorder.headline(it).orEmpty()}")
        }
        Channels.create(this)
        // Release demo mode ends by itself, also when no boot event comes (suspend/resume).
        if (!BuildConfig.DEBUG && DebugSettings.fakeVehicle(this) && DemoExpiry.expired(DebugSettings.demoStartedAt(this), System.currentTimeMillis())) {
            DebugSettings.setFakeVehicle(this, false)
            log("demo mode ended by itself")
        }
        try {
            startInForeground()
        } catch (e: IllegalStateException) {
            // e.g. ForegroundServiceStartNotAllowedException after a background restart: try again on next launch.
            log("cannot start in foreground: ${e.message}")
            stopSelf()
            return
        }
        // Release builds too: demo mode plays the stories on the simulated car.
        fakeMode = DebugSettings.fakeVehicle(this)
        if (fakeMode) {
            if (DebugSettings.wipeFakeDataIfRequested(this)) log("fake vehicle data wiped")
            // The fake car's own time continues where it stopped, so its saved state never lies in the future.
            clock.advance(DebugSettings.clockOffset(this))
        }
        vehicle = PleosVehicle(this, catalog, log)
        aaos = AaosVehicleProperties(this, log)
        navigator = PleosNavigator(this, log)
        // The request is taken in every debug start, so a fake-mode start never leaves it for later.
        val selfTestRequested = BuildConfig.DEBUG && DebugSettings.takeSelfTest(this)
        val selfTestHere = !fakeMode && DebugSettings.isEmulator()
        // A request that cannot run here is dropped, and says so.
        if (selfTestRequested && !selfTestHere) log("SELFTEST request dropped: needs the real vehicle on the emulator")
        if (selfTestRequested && selfTestHere) {
            selfTestJob = scope.launch { selfTest() }
            return
        }
        scope.launch { boot() }
        scope.launch(Dispatchers.IO) { for (latest in habitSaves) saveHabits(latest) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        // Stop every coroutine first, so no late playback step or boot publishes after the screen is cleared.
        scope.cancel()
        AppState.commands = null
        AppState.debug = null
        // Nothing on screen may look actionable without a running engine.
        AppState.update { UiState() }
        running?.close()
        running = null
        if (fakeMode) DebugSettings.setClockOffset(this, clock.offsetMs)
        if (::aaos.isInitialized) runCatching { aaos.stop() }
        if (::navigator.isInitialized) runCatching { navigator.stop() }
        if (::vehicle.isInitialized) releaseVehicle(vehicle)
        habits?.let { saveHabits(it.log) }
        log("service stopped")
        super.onDestroy()
    }

    /**
     * A self-test stopped half way is still putting its last write back (NonCancellable), and needs
     * the car until then; the vehicle is released once it has finished, or after a bounded wait.
     */
    private fun releaseVehicle(car: PleosVehicle) {
        val testing = selfTestJob?.takeIf { !it.isCompleted }
        if (testing == null) {
            runCatching { car.release() }
            return
        }
        CoroutineScope(Dispatchers.Main).launch {
            withTimeoutOrNull(SELF_TEST_RESTORE_WAIT_MS) { testing.join() }
            runCatching { car.release() }
        }
    }

    /**
     * Debug builds only: tests every signal of the car with the engine stopped, so its writes reach no
     * routine and no habit log; writes the report to files/self_test.md and restarts the service normally.
     */
    private suspend fun selfTest() {
        log("SELFTEST starting; the routine engine is not running")
        try {
            val ready = withTimeoutOrNull(BOOT_VEHICLE_WAIT_MS) { vehicle.connect() } == true
            if (ready) {
                val car = object : SelfTestCar {
                    override suspend fun capabilities() = vehicle.checkCapabilities()
                    override fun subscribe(onChange: (SignalId, SignalValue) -> Unit) = vehicle.subscribe { onChange(it.signal, it.value) }
                    override suspend fun write(signal: SignalId, value: SignalValue) = vehicle.write(signal, catalog.getOrThrow(signal).area, value)
                }
                val rows = SignalSelfTest(catalog, car).run { log(it) }
                withContext(Dispatchers.IO) { File(filesDir, SELF_TEST_REPORT).writeText(rows.toMarkdown()) }
                val readable = rows.count { it.access == Access.READ || it.access == Access.READ_WRITE }
                log("SELFTEST done: ${rows.size} signals, $readable readable, ${rows.count { it.wrote != null }} written; files/$SELF_TEST_REPORT")
            } else {
                log("SELFTEST vehicle service not ready; nothing tested")
            }
        } catch (e: CancellationException) {
            // The service is being stopped: the self-test has put its last write back, and nothing restarts.
            throw e
        } catch (e: Exception) {
            // A failed self-test must not leave the app without its engine.
            log("SELFTEST failed: ${e.javaClass.simpleName}: ${e.message}")
        }
        RoutineService.restart(this)
    }

    /** Startup order: restore, capability snapshot, prime readings without triggering, then events. */
    private suspend fun boot() {
        log("service starting (control sdk open=${BuildConfig.CONTROL_SDK_OPEN}, fake vehicle=$fakeMode)")
        AppState.update { it.copy(status = it.status.copy(controlOpen = BuildConfig.CONTROL_SDK_OPEN, emulator = BuildConfig.DEBUG)) }
        val repository = RoutineRepository(this, log, prefix)
        val stored = withContext(Dispatchers.IO) {
            runCatching { repository.load() }.getOrElse { emptyList<Routine>().also { _ -> log("routines not loaded: ${it.message}") } }
        }
        val book = withContext(Dispatchers.IO) { habitBook() }
        habits = book
        scope.launch(Dispatchers.IO) {
            for (list in routineSaves) runCatching { repository.save(list) }.onFailure { log("routines not saved: ${it.message}") }
        }
        var engineRef: RoutineEngine? = null
        var notifierRef: AndroidNotifier? = null
        val controller = RoutineController(
            catalog, stored, book,
            post = { event -> engineRef?.post(event) },
            saveRoutines = { list -> routineSaves.trySend(list) },
            log = log,
            now = { clock.now().toEpochMilli() },
            onParkedAgain = { held -> notifierRef?.postDigest(held) },
        )
        val fakeCar = if (fakeMode) FakeVehicle(catalog, clock, FAKE_START_VALUES) else null
        val writer: VehicleWriter = fakeCar ?: vehicle
        val navi: Navigator = if (fakeMode) FakeNavigator() else navigator
        val notifier = AndroidNotifier(this, controller.notices, controller::quiet).also { notifierRef = it }
        val texts = Texts(this)
        // What a run really did, from its results; posted off the engine coroutine.
        val summaries = RunSummaryObserver(
            routineOf = { id -> AppState.ui.value.routines.firstOrNull { it.routine.id == id }?.routine },
            describe = texts::runSummary,
            announce = { text ->
                scope.launch {
                    try {
                        notifier.notify(NotifyMessage(text))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log("run summary not shown: ${e.message}")
                    }
                }
            },
        )
        val engine = RoutineEngine(
            catalog, Executor(ActionPlanner(catalog), writer, notifier, navi, NotificationConfirmer(this)),
            notifier, FileEngineStore(this, prefix), clock, ZoneId.systemDefault(),
            observer = CompositeObserver(listOf(LogObserver, book, controller, summaries)),
        )
        engineRef = engine
        running = engine

        if (fakeCar != null) startFake(engine, controller, fakeCar) else startReal(engine, controller)
        currentCoroutineContext().ensureActive() // destroyed while booting: publish nothing
        AppState.commands = controller
        // Every build: the debug build shows the full panel, the release build only demo mode (DebugState.panel).
        // "실제" is the fake car's full capability in fake mode, and what the car reported otherwise.
        debug = DebugController(this, catalog, clock, engine, controller, book, scope, fakeCar) {
            if (fakeCar != null) CapabilityPreset.FULL.snapshot(catalog, simulated = true) else realCapability ?: capability(emptyMap())
        }.also { AppState.debug = it }
        scope.launch { engine.runLoop() }
        scope.launch { tick(engine, controller) }
    }

    private suspend fun startReal(engine: RoutineEngine, controller: RoutineController) {
        val buffer = StartupBuffer(catalog)
        aaos.start { event ->
            when (event) {
                is EngineEvent.SignalChanged -> buffer.accept(event)
                is EngineEvent.DrivingChanged -> {
                    controller.onDriving(event.state)
                    engine.post(event)
                }
                else -> engine.post(event)
            }
        }
        scope.launch(Dispatchers.IO) {
            navigator.start { event ->
                if (event is EngineEvent.NavigationChanged) navigating = event.navigating
                engine.post(event)
            }
        }
        val reported = connectVehicle(buffer)

        delay(PRIME_WINDOW_MS)
        val readings = buffer.release { engine.post(it) }
        log("startup readings: ${readings.size} signals")
        val routines = controller.validated()
        val capability = capability(reported ?: emptyMap()).also { realCapability = it }
        // The latest navigation state wins over anything posted before start (start resets the live state).
        engine.start(EngineStartup(capability, routines, readings, aaos.current(), navigating))
        log("engine started with ${routines.size} routines")
        controller.onDriving(aaos.current())
        AppState.update { it.copy(status = it.status.copy(engineReady = true, vehicleConnected = reported != null)) }
        if (reported == null) scope.launch { reconnectLater(engine) }
    }

    /** Debug fake mode: a simulated car, parked, with every capability open and every result labelled "모의". */
    private suspend fun startFake(engine: RoutineEngine, controller: RoutineController, car: FakeVehicle) {
        val routines = controller.validated()
        engine.start(EngineStartup(CapabilityPreset.FULL.snapshot(catalog, simulated = true), routines, car.readings(), DrivingPreset.PARKED.state))
        log("engine started on the fake vehicle with ${routines.size} routines")
        if (!BuildConfig.DEBUG) {
            scope.launch {
                delay(DemoExpiry.remaining(DebugSettings.demoStartedAt(this@RoutineService), System.currentTimeMillis()))
                log("demo mode ended by itself")
                debug?.setFakeVehicle(false)
            }
        }
        controller.onDriving(DrivingPreset.PARKED.state)
        AppState.update { it.copy(status = it.status.copy(engineReady = true, vehicleConnected = true)) }
        // Writes from routines echo back like the emulator; during playback the script delivers them in order.
        scope.launch {
            while (isActive) {
                if (debug?.isPlaying != true) car.dueEchoes().forEach { engine.post(it) }
                delay(ECHO_PUMP_MS)
            }
        }
    }

    /** Waits a bounded time for the vehicle service; null means boot without it and keep trying in the background. */
    private suspend fun connectVehicle(buffer: StartupBuffer): Map<SignalId, Access?>? {
        val ready = withTimeoutOrNull(BOOT_VEHICLE_WAIT_MS) { vehicle.connect() } == true
        if (!ready) return null.also { log("vehicle service not ready; starting without it") }
        val reported = vehicle.checkCapabilities()
        vehicle.subscribe { buffer.accept(it) }
        return reported
    }

    /** Late vehicle connection: first values have no previous value, so they never trigger anything. */
    private suspend fun reconnectLater(engine: RoutineEngine) {
        if (!vehicle.connect()) return
        val reported = vehicle.checkCapabilities()
        vehicle.subscribe { engine.post(it) }
        val snapshot = capability(reported).also { realCapability = it }
        engine.post(EngineEvent.CapabilityChanged(snapshot))
        AppState.update { it.copy(status = it.status.copy(vehicleConnected = true)) }
        log("vehicle connected after startup; capabilities updated")
    }

    private fun capability(reported: Map<SignalId, Access?>): CapabilitySnapshot =
        CapabilityAssembler(catalog).assemble(openSdks(), reported + aaos.capabilities(), ::granted)

    private suspend fun tick(engine: RoutineEngine, controller: RoutineController) {
        while (scope.isActive) {
            delay(TICK_MS)
            if (!fakeMode) aaos.refresh()
            engine.post(EngineEvent.Tick)
            controller.refreshSuggestion()
        }
    }

    /** Habit changes go through a conflated channel and are saved on IO; the last one is also saved on destroy. */
    private fun habitBook(): HabitBook {
        val zone = ZoneId.systemDefault()
        val initial = runCatching { habitStore.load() }.getOrElse { HabitLog().also { _ -> log("habit log not loaded: ${it.message}") } }
        // Suggested routines get a readable Korean name, e.g. "핸들 열선 1단계 · 습관".
        val texts = Texts(this)
        val detector = HabitDetector(catalog, zone, namer = { getString(R.string.habit_routine_name, "${texts.signal(it.signal)} ${texts.value(it.signal, it.value)}") })
        return HabitBook(HabitRecorder(catalog, zone), detector, initial, onChange = { habitSaves.trySend(it) })
    }

    private fun saveHabits(latest: HabitLog) {
        try {
            habitStore.save(latest)
        } catch (e: java.io.IOException) {
            EngineLog.add("habit log not saved: ${e.message}")
        }
    }

    /** TTS, STT and location are Coming Soon; Vehicle Control only where it accepts writes. */
    private fun openSdks(): Set<SdkModule> = buildSet {
        add(SdkModule.VEHICLE_STATE)
        add(SdkModule.NAVI)
        add(SdkModule.AAOS_CAR_PROPERTY)
        if (BuildConfig.CONTROL_SDK_OPEN) add(SdkModule.VEHICLE_CONTROL)
    }

    private fun granted(signal: SignalId, kind: PermissionKind): Boolean {
        val permission = permissionFor(signal, kind) ?: return false
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun permissionFor(signal: SignalId, kind: PermissionKind): String? {
        PleosBindings.all.firstOrNull { it.signal == signal }?.let { return if (kind == PermissionKind.READ) it.readPermission else it.writePermission }
        if (kind == PermissionKind.WRITE) return null
        return when (signal) {
            SignalIds.GEAR_SELECTION -> "android.car.permission.CAR_POWERTRAIN"
            SignalIds.VEHICLE_SPEED -> "android.car.permission.CAR_SPEED"
            SignalIds.EV_CHARGING -> "android.car.permission.CAR_ENERGY"
            else -> null
        }
    }

    private fun startInForeground() {
        val notification = Notification.Builder(this, Channels.SERVICE)
            .setSmallIcon(android.R.drawable.ic_menu_rotate)
            .setContentTitle(getString(R.string.app_name))
            // The car's own notification area says it too while the real routines pause for the demo.
            .setContentText(NoticeText.readable(getString(if (DebugSettings.fakeVehicle(this)) R.string.service_demo else R.string.service_running)))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(FOREGROUND_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(FOREGROUND_ID, notification)
        }
    }

    companion object {
        private const val FOREGROUND_ID = 1
        /** Long enough for the first subscription callbacks; HVAC has no getCurrent. */
        private const val PRIME_WINDOW_MS = 3_000L
        /** The emulator connects in 6–8 s; after this the engine starts without the vehicle. */
        private const val BOOT_VEHICLE_WAIT_MS = 45_000L
        private const val TICK_MS = 60_000L
        private const val ECHO_PUMP_MS = 100L
        private const val SELF_TEST_REPORT = "self_test.md"
        /** Longer than a restore of the tested signal and its couplings (a few settle periods of 3 s). */
        private const val SELF_TEST_RESTORE_WAIT_MS = 15_000L

        /** The fake car at service start: parked, empty, locked, everything off. */
        private val FAKE_START_VALUES: Map<SignalId, SignalValue> = mapOf(
            SignalIds.SEAT_OCCUPIED_DRIVER to SignalValue.Bool(false),
            SignalIds.SEAT_OCCUPIED_PASSENGER to SignalValue.Bool(false),
            SignalIds.DOOR_LOCK to SignalValue.Bool(true),
            SignalIds.WIPER_FRONT_STATE to SignalValue.Text("OFF"),
            SignalIds.DEFROSTER_FRONT to SignalValue.Bool(false),
            SignalIds.RECIRCULATION to SignalValue.Bool(false),
            SignalIds.HVAC_TEMP_DRIVER to SignalValue.num(22.0),
            SignalIds.STEERING_WHEEL_HEAT to SignalValue.of(0),
            SignalIds.SEAT_HEAT_DRIVER to SignalValue.of(0),
            SignalIds.WINDOW_POSITION_DRIVER to SignalValue.of(0),
            SignalIds.EV_SOC to SignalValue.of(70),
            SignalIds.EV_CHARGING to SignalValue.Bool(false),
        )

        /** The running engine, for debug commands only (`app/src/debug`). */
        @Volatile internal var running: RoutineEngine? = null
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, RoutineService::class.java))
        }

        /** Debug: applies a mode switch by starting a fresh service instance. */
        internal fun restart(context: Context) {
            context.stopService(Intent(context, RoutineService::class.java))
            start(context)
        }
    }
}
