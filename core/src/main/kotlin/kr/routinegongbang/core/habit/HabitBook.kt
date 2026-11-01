package kr.routinegongbang.core.habit

import kr.routinegongbang.core.engine.EngineObserver
import kr.routinegongbang.core.model.Routine
import kr.routinegongbang.core.model.SignalId
import kr.routinegongbang.core.model.SignalIds
import kr.routinegongbang.core.model.SignalValue
import java.time.Instant

/**
 * Thread-safe owner of the habit log for the app: the engine thread feeds it through the [EngineObserver]
 * hooks, the UI asks for a suggestion and answers it. Every change is passed to [onChange] for saving;
 * the callback runs under the lock, so it must only hand the value off (e.g. to a coroutine), not do IO.
 */
class HabitBook(
    private val recorder: HabitRecorder,
    private val detector: HabitDetector,
    initial: HabitLog,
    private val onChange: (HabitLog) -> Unit = {},
) : EngineObserver {
    private val lock = Any()

    var log: HabitLog = initial
        get() = synchronized(lock) { field }
        private set

    override fun onTripStarted(tripId: String, at: Instant) = update { recorder.onTripStarted(it, tripId, at) }

    /** The cabin temperature known at the start becomes part of the ride's context. */
    override fun onTripStarted(tripId: String, at: Instant, values: Map<SignalId, SignalValue>) = update {
        recorder.onTripStarted(it, tripId, at, (values[SignalIds.CABIN_TEMPERATURE] as? SignalValue.Num)?.value)
    }

    override fun onUserChange(tripId: String?, signal: SignalId, value: SignalValue, at: Instant) =
        update { recorder.onUserChange(it, tripId, signal, value, at) }

    /** The one suggestion to show when parked after [tripId], or null. */
    fun bestSuggestion(tripId: String, existing: List<Routine>, nowEpochMs: Long): Suggestion? =
        detector.suggest(log, tripId, existing, nowEpochMs).firstOrNull()

    /** Records SHOWN / ACCEPTED / REJECTED for the context of [tripId]; unknown trips are ignored. */
    fun answer(pattern: HabitPattern, tripId: String, kind: FeedbackKind, atEpochMs: Long) = update { current ->
        val context = current.trips.lastOrNull { it.tripId == tripId }?.context ?: return@update current
        current.withFeedback(pattern, context, kind, atEpochMs)
    }

    /** Forgets every trip, operation and answer (user "forget my habits", demo replays). */
    fun clear() = update { HabitLog() }

    private fun update(change: (HabitLog) -> HabitLog) {
        synchronized(lock) {
            val next = change(log)
            if (next == log) return
            log = next
            onChange(next)
        }
    }
}
