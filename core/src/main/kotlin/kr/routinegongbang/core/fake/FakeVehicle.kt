package kr.routinegongbang.core.fake

import kr.routinegongbang.core.engine.EngineClock
import kr.routinegongbang.core.engine.EngineEvent
import kr.routinegongbang.core.engine.VehicleWriter
import kr.routinegongbang.core.model.AreaKey
import kr.routinegongbang.core.model.AreaKeys
import kr.routinegongbang.core.model.SignalCatalog
import kr.routinegongbang.core.model.SignalId
import kr.routinegongbang.core.model.SignalValue

/**
 * Simulated vehicle for scenarios and the debug "fake mode".
 * Behaves like the emulator: a write that changes the value is echoed after [echoDelayMs]
 * (writing the current value produces no event); signals that cannot be subscribed (wiper switch) never echo;
 * [failing] signals reject writes; a change made outside the app cancels an echo still on its way.
 * Values the adapter would correct (inverted seat occupancy) are already in their corrected form here.
 */
class FakeVehicle(
    private val catalog: SignalCatalog,
    private val clock: EngineClock,
    initial: Map<SignalId, SignalValue> = emptyMap(),
    private val echoDelayMs: Long = DEFAULT_ECHO_DELAY_MS,
    private val failing: Set<SignalId> = emptySet(),
    /** Writing the key value also sets these signals, as "max defrost" does on a real car. */
    private val couplings: Map<Pair<SignalId, SignalValue>, Map<SignalId, SignalValue>> = emptyMap(),
) : VehicleWriter {

    data class WriteRecord(val signal: SignalId, val area: AreaKey, val value: SignalValue, val atMonoMs: Long)

    private class PendingEcho(val dueMonoMs: Long, val event: EngineEvent.SignalChanged)

    private val lock = Any()
    private val state = initial.mapKeys { (signal, _) -> signal to homeArea(signal) }.toMutableMap()
    private val pending = mutableListOf<PendingEcho>()
    private val log = mutableListOf<WriteRecord>()

    val writes: List<WriteRecord> get() = synchronized(lock) { log.toList() }

    fun value(signal: SignalId, area: AreaKey? = null): SignalValue? = synchronized(lock) { state[signal to (area ?: homeArea(signal))] }

    /** Current values on each signal's catalog area, for [kr.routinegongbang.core.engine.EngineStartup.readings]. */
    fun readings(): Map<SignalId, SignalValue> = synchronized(lock) {
        state.filterKeys { (signal, area) -> area == homeArea(signal) }.mapKeys { it.key.first }
    }

    /** A change made outside the app (driver, sensors). Returns the event the adapter would post. */
    fun userSets(signal: SignalId, value: SignalValue, area: AreaKey? = null): EngineEvent.SignalChanged {
        val resolved = area ?: homeArea(signal)
        synchronized(lock) {
            state[signal to resolved] = value
            pending.removeAll { it.event.signal == signal && it.event.area == resolved }
        }
        return EngineEvent.SignalChanged(signal, value, resolved)
    }

    override suspend fun write(signal: SignalId, area: AreaKey, value: SignalValue): Result<Unit> = synchronized(lock) {
        if (signal in failing || catalog[signal] == null) return Result.failure(IllegalStateException("Write rejected: ${signal.value}"))
        val now = clock.monotonicMs()
        log += WriteRecord(signal, area, value, now)
        change(signal, area, value, now)
        couplings[signal to value].orEmpty().forEach { (other, otherValue) -> change(other, homeArea(other), otherValue, now) }
        Result.success(Unit)
    }

    /** Called with [lock] held. */
    private fun change(signal: SignalId, area: AreaKey, value: SignalValue, now: Long) {
        val changed = state[signal to area] != value
        state[signal to area] = value
        if (changed && catalog[signal]?.subscribable == true) pending += PendingEcho(now + echoDelayMs, EngineEvent.SignalChanged(signal, value, area))
    }

    /** Earliest monotonic time at which an echo is due, or null. */
    fun nextEchoAt(): Long? = synchronized(lock) { pending.minOfOrNull { it.dueMonoMs } }

    /** Echoes due now, in the order they were written; each is returned once. */
    fun dueEchoes(): List<EngineEvent.SignalChanged> = synchronized(lock) {
        val now = clock.monotonicMs()
        val due = pending.filter { it.dueMonoMs <= now }
        pending.removeAll(due)
        due.map { it.event }
    }

    private fun homeArea(signal: SignalId): AreaKey = catalog[signal]?.area ?: AreaKeys.GLOBAL

    companion object {
        /** Steering wheel heat echoed within 1 s on the emulator. */
        const val DEFAULT_ECHO_DELAY_MS: Long = 200
    }
}
