package kr.routinegongbang.app.pleos

import ai.pleos.playground.vehicle.Vehicle
import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kr.routinegongbang.app.service.DebugSettings
import kr.routinegongbang.core.engine.Access
import kr.routinegongbang.core.engine.EngineEvent
import kr.routinegongbang.core.engine.VehicleWriter
import kr.routinegongbang.core.model.AreaKey
import kr.routinegongbang.core.model.SignalCatalog
import kr.routinegongbang.core.model.SignalId
import kr.routinegongbang.core.model.SignalValue
import kr.routinegongbang.core.platform.RetryPolicy
import kr.routinegongbang.core.platform.fromPlatform
import kr.routinegongbang.core.platform.toPlatform
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume

/**
 * Pleos Vehicle SDK adapter (vehicle state and vehicle control).
 *
 * Connection: `Vehicle.initialize()` has no callback. The SDK binds with its own retries and queues calls
 * meanwhile, so readiness is a cheap capability call: any answer, success or failure, proves the
 * service is there. Only silence makes us release the Vehicle and retry with [retry] backoff.
 */
class PleosVehicle(
    private val context: Context,
    private val catalog: SignalCatalog,
    private val log: (String) -> Unit,
    private val retry: RetryPolicy = RetryPolicy.SERVICE,
    private val emulator: Boolean = DebugSettings.isEmulator(),
) : VehicleWriter {

    @Volatile private var vehicle: Vehicle? = null
    @Volatile private var probes: Map<SignalId, Probe> = emptyMap()
    private val bindings = PleosBindings.all.associateBy { it.signal }
    private val unsubscribers = mutableListOf<() -> Unit>()

    /** Suspends until the vehicle service answers. Returns false only when [retry] gives up. */
    suspend fun connect(): Boolean {
        var attempt = 1
        while (true) {
            val candidate = Vehicle(context).also { it.initialize() }
            val answered = try {
                ping(candidate)
            } catch (e: CancellationException) {
                runCatching { candidate.release() }
                throw e
            }
            if (answered) {
                vehicle = candidate
                log("vehicle connected (attempt $attempt)")
                return true
            }
            runCatching { candidate.release() }
            val wait = retry.delayBefore(attempt) ?: return false.also { log("vehicle connection given up after $attempt attempts") }
            log("vehicle not answering (attempt $attempt), retrying in ${wait}ms")
            delay(wait)
            attempt++
        }
    }

    private suspend fun ping(candidate: Vehicle): Boolean = withTimeoutOrNull(PING_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            val answered = { if (cont.isActive) cont.resume(true) }
            try {
                candidate.getSteeringWheel().checkHvacSteeringWheelHeatCapabilities({ _, _ -> answered() }, { answered() })
            } catch (e: RuntimeException) {
                if (cont.isActive) cont.resume(false)
            }
        }
    } ?: false

    /** Runs every capability check. A failed, throwing or twice-silent check counts as no access. */
    suspend fun checkCapabilities(): Map<SignalId, Access?> {
        val car = vehicle ?: return emptyMap()
        // Concurrent checks go unanswered on the emulator, so they run one at a time; a silent one is retried once.
        val found = bindings.values.associate { binding ->
            val first = probe(car, binding)
            binding.signal to if (first.access != null) first else probe(car, binding).also { log("capability ${binding.signal.value} retried") }
        }
        probes = found
        found.forEach { (signal, probe) -> log("capability ${signal.value}: ${probe.access ?: "check failed"}") }
        return found.mapValues { it.value.access }
    }

    private suspend fun probe(car: Vehicle, binding: SignalBinding): Probe {
        val probe = withTimeoutOrNull(CHECK_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                try {
                    binding.check(car) { if (cont.isActive) cont.resume(it) }
                } catch (e: RuntimeException) {
                    log("capability ${binding.signal.value} threw: ${e.message}")
                    if (cont.isActive) cont.resume(Probe(null))
                }
            }
        } ?: Probe(null)
        // A signal this adapter cannot write yet must never be reported as writable.
        return if (binding.write == null) probe.copy(access = probe.access?.readOnly()) else probe
    }

    /** Subscribes every readable signal; on the emulator, values are corrected per catalog before they reach [sink]. */
    fun subscribe(sink: (EngineEvent.SignalChanged) -> Unit) {
        val car = vehicle ?: return
        // Unverified on a real car: say which way the seat sensors are read, once per subscription.
        log(if (emulator) "value corrections: emulator, seat occupancy inverted" else "value corrections: none (real car)")
        probes.filterValues { it.access == Access.READ || it.access == Access.READ_WRITE }.forEach { (signal, probe) ->
            val binding = bindings.getValue(signal)
            val spec = catalog.getOrThrow(signal)
            val correction = EmulatorCorrection.effective(spec.correction, emulator)
            val emit: Emit = { raw -> sink(EngineEvent.SignalChanged(signal, correction.fromPlatform(raw), spec.area)) }
            val failed: Failed = { e -> log("subscription ${signal.value} failed: ${e.message}") }
            runCatching { binding.subscribe(car, probe, emit, failed) }
                .onSuccess { synchronized(unsubscribers) { unsubscribers += it } }
                .onFailure { log("subscribe ${signal.value} threw: ${it.message}") }
        }
    }

    /**
     * A write the SDK never acknowledges may still land, so a timeout is not a failure: the executor keeps the
     * ledger entry and the result becomes SENT_UNCONFIRMED unless the echo arrives. Errors stay failures.
     */
    override suspend fun write(signal: SignalId, area: AreaKey, value: SignalValue): Result<Unit> {
        val car = vehicle ?: return Result.failure(IllegalStateException("vehicle not connected"))
        val binding = bindings[signal] ?: return Result.failure(UnsupportedOperationException("${signal.value} is not served by Pleos"))
        val setter = binding.write ?: return Result.failure(UnsupportedOperationException("${signal.value} is read-only in this build"))
        val probe = probes[signal] ?: Probe(null)
        val raw = EmulatorCorrection.effective(catalog.getOrThrow(signal).correction, emulator).toPlatform(value)
        return withTimeoutOrNull(WRITE_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                try {
                    setter(car, probe, raw) { result -> if (cont.isActive) cont.resume(result) }
                } catch (e: RuntimeException) {
                    if (cont.isActive) cont.resume(Result.failure(e))
                }
            }
        } ?: Result.success(Unit).also { log("write ${signal.value} not acknowledged in ${WRITE_TIMEOUT_MS}ms; waiting for the echo") }
    }

    fun release() {
        synchronized(unsubscribers) {
            unsubscribers.forEach { runCatching { it() } }
            unsubscribers.clear()
        }
        runCatching { vehicle?.release() }
        vehicle = null
    }

    private fun Access.readOnly(): Access = if (this == Access.READ_WRITE || this == Access.READ) Access.READ else Access.NONE

    private companion object {
        const val PING_TIMEOUT_MS = 20_000L
        const val CHECK_TIMEOUT_MS = 5_000L
        const val WRITE_TIMEOUT_MS = 5_000L
    }
}
