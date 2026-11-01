package kr.routinegongbang.core.fake

import kr.routinegongbang.core.engine.EngineClock
import java.time.Instant

/** A clock that scenario drivers and the debug panel can move forward. Wall and monotonic time move together. */
interface AdjustableClock : EngineClock {
    fun advance(ms: Long)
}

/**
 * Clock moved by hand (scenario tests). Updated atomically, so
 * [kr.routinegongbang.core.engine.RoutineEngine.post] may read it from any thread.
 */
class ManualClock(start: Instant, monoMs: Long = 0) : AdjustableClock {
    private class Time(val wall: Instant, val monoMs: Long)

    @Volatile private var time = Time(start, monoMs)
    private val lock = Any()

    override fun now(): Instant = time.wall
    override fun monotonicMs(): Long = time.monoMs

    override fun advance(ms: Long) {
        require(ms >= 0) { "Time only moves forward in a scenario" }
        synchronized(lock) { time = Time(time.wall.plusMillis(ms), time.monoMs + ms) }
    }
}

/**
 * Real time plus a fast-forward offset (debug panel "시계 앞으로", clock forward). Moves wall and
 * monotonic time by the same amount, so ledger deadlines, cooldowns, trips and habits all see the jump.
 */
class OffsetClock(private val base: EngineClock = EngineClock.SYSTEM) : AdjustableClock {
    @Volatile var offsetMs: Long = 0
        private set
    private val lock = Any()

    override fun now(): Instant = base.now().plusMillis(offsetMs)
    override fun monotonicMs(): Long = base.monotonicMs() + offsetMs

    override fun advance(ms: Long) {
        require(ms >= 0) { "The debug clock only moves forward" }
        synchronized(lock) { offsetMs += ms }
    }

    /** Back to real time. Earlier persisted times may then lie in the future; the engine treats that as a clock rollback. */
    fun reset() = synchronized(lock) { offsetMs = 0 }
}
