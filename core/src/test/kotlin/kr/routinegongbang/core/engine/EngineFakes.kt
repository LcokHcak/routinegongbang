package kr.routinegongbang.core.engine

import kr.routinegongbang.core.model.AreaKey
import kr.routinegongbang.core.model.Routine
import kr.routinegongbang.core.model.RoutineValidator
import kr.routinegongbang.core.model.SignalCatalog
import kr.routinegongbang.core.model.SignalId
import kr.routinegongbang.core.model.SignalValue
import kr.routinegongbang.core.model.ValidatedRoutine
import kr.routinegongbang.core.model.ValidationOutcome
import java.time.Instant
import java.time.ZoneId

internal val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

/** 2026-10-27 is a Tuesday. */
internal fun kst(time: String, date: String = "2026-10-27"): Instant = Instant.parse("${date}T$time+09:00")

internal class FakeClock(var now: Instant, var mono: Long = 1_000) : EngineClock {
    override fun now(): Instant = now
    override fun monotonicMs(): Long = mono
    fun advance(ms: Long) {
        now = now.plusMillis(ms)
        mono += ms
    }
}

internal class RecordingWriter(var failing: Boolean = false, var onWrite: () -> Unit = {}) : VehicleWriter {
    val writes = mutableListOf<Pair<SignalId, SignalValue>>()
    override suspend fun write(signal: SignalId, area: AreaKey, value: SignalValue): Result<Unit> {
        onWrite()
        if (failing) return Result.failure(IllegalStateException("write rejected"))
        writes += signal to value
        return Result.success(Unit)
    }
}

internal class RecordingNotifier(var onNotify: (NotifyMessage) -> Unit = {}, var failing: Boolean = false) : Notifier {
    val messages = mutableListOf<NotifyMessage>()
    override suspend fun notify(message: NotifyMessage) {
        if (failing) error("notification service gone")
        messages += message
        onNotify(message)
    }
}

internal class RecordingNavigator : Navigator {
    val destinations = mutableListOf<String>()
    override suspend fun setDestination(placeId: String): Result<Unit> {
        destinations += placeId
        return Result.success(Unit)
    }
}

internal class ScriptedConfirmer(var answer: Boolean = true, var onAsk: () -> Unit = {}) : Confirmer {
    var asked = 0
    val requests = mutableListOf<ConfirmRequest>()
    override suspend fun confirm(request: ConfirmRequest): Boolean {
        asked++
        requests += request
        onAsk()
        return answer
    }
}

internal class RecordingObserver(var onDecisionHook: (Decision) -> Unit = {}) : EngineObserver {
    val decisions = mutableListOf<Decision>()
    val results = mutableListOf<ResultUpdate>()
    override fun onDecision(decision: Decision) {
        decisions += decision
        onDecisionHook(decision)
    }
    override fun onResult(update: ResultUpdate) {
        results += update
    }
    val tripsStarted = mutableListOf<String>()
    val userChanges = mutableListOf<Triple<String?, SignalId, SignalValue>>()
    override fun onTripStarted(tripId: String, at: Instant) {
        tripsStarted += tripId
    }
    override fun onUserChange(tripId: String?, signal: SignalId, value: SignalValue, at: Instant) {
        userChanges += Triple(tripId, signal, value)
    }
}

internal class FailingStore : EngineStore {
    override suspend fun load(): EngineSnapshot = error("disk gone")
    override suspend fun save(snapshot: EngineSnapshot) = error("disk gone")
}

/** Load fails (corrupt file) but saving would work: the engine must not overwrite the file. */
internal class UnreadableStore : EngineStore {
    var saves = 0
    override suspend fun load(): EngineSnapshot = error("corrupt")
    override suspend fun save(snapshot: EngineSnapshot) {
        saves++
    }
}

internal fun validate(routine: Routine, catalog: SignalCatalog = testCatalog): ValidatedRoutine =
    when (val outcome = RoutineValidator(catalog).validated(routine)) {
        is ValidationOutcome.Valid -> outcome.routine
        is ValidationOutcome.Invalid -> error("Invalid test routine ${routine.id}: ${outcome.issues}")
    }

/** One engine wired to fakes. Create a second harness with the same [store] to simulate a service restart. */
internal class Harness(
    private val routines: List<Routine>,
    val clock: FakeClock = FakeClock(kst("12:00:00")),
    val store: InMemoryEngineStore = InMemoryEngineStore(),
    private val engineStore: EngineStore = store,
    newTripId: (() -> String)? = null,
    val catalog: SignalCatalog = testCatalog,
) {
    val writer = RecordingWriter()
    val notifier = RecordingNotifier()
    val navigator = RecordingNavigator()
    val confirmer = ScriptedConfirmer()
    val observer = RecordingObserver()
    private var tripCount = 0
    val engine = RoutineEngine(
        catalog = catalog,
        executor = Executor(ActionPlanner(catalog), writer, notifier, navigator, confirmer),
        notifier = notifier,
        store = engineStore,
        clock = clock,
        zone = SEOUL,
        observer = observer,
        newTripId = newTripId ?: { "trip-${++tripCount}" },
    )

    suspend fun start(
        readings: Map<SignalId, SignalValue> = emptyMap(),
        driving: DrivingState = PARKED,
        capability: CapabilitySnapshot = FULL_CONTROL,
    ) = engine.start(EngineStartup(capability, routines.map { validate(it, catalog) }, readings, driving))

    suspend fun signal(id: SignalId, value: SignalValue) = engine.handle(EngineEvent.SignalChanged(id, value))

    fun reports(): List<ExecutionReport> = observer.decisions.filterIsInstance<Decision.Ran>().map { it.report }
}
