package kr.routinegongbang.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TripTrackerTest {

    private val tracker = TripTracker(endDelayMs = 5 * 60_000)
    private val ids = generateSequence(1) { it + 1 }.map { "trip-$it" }.iterator()
    private fun newId() = ids.next()

    private fun started(): TripState = tracker.onDriverSeat(TripState(), occupied = true, nowEpochMs = 0, newId = ::newId).first

    @Test
    fun `driver sitting down starts a trip`() {
        val (state, event) = tracker.onDriverSeat(TripState(), occupied = true, nowEpochMs = 1_000, newId = ::newId)
        assertEquals(TripEvent.Started("trip-1"), event)
        assertEquals("trip-1", state.tripId)
        assertEquals(1_000L, state.startedAtEpochMs)
    }

    @Test
    fun `sitting down again during a trip does not start a new one`() {
        val (state, event) = tracker.onDriverSeat(started(), occupied = true, nowEpochMs = 10, newId = ::newId)
        assertNull(event)
        assertEquals("trip-1", state.tripId)
    }

    @Test
    fun `locking during a trip marks the end as pending`() {
        val (state, event) = tracker.onLock(started(), locked = true, nowEpochMs = 100, driverSeated = false)
        assertEquals(TripEvent.EndPending("trip-1"), event)
        assertEquals(100L, state.endPendingSinceEpochMs)
    }

    @Test
    fun `locking twice does not report the end twice`() {
        val pending = tracker.onLock(started(), locked = true, nowEpochMs = 100, driverSeated = false).first
        assertNull(tracker.onLock(pending, locked = true, nowEpochMs = 200, driverSeated = false).second)
    }

    @Test
    fun `locking without a trip does nothing`() {
        assertEquals(TripState() to null, tracker.onLock(TripState(), locked = true, nowEpochMs = 0, driverSeated = false))
    }

    @Test
    fun `trip ends only after five minutes locked`() {
        val pending = tracker.onLock(started(), locked = true, nowEpochMs = 0, driverSeated = false).first
        assertNull(tracker.onTick(pending, nowEpochMs = 299_999).second)
        val (ended, event) = tracker.onTick(pending, nowEpochMs = 300_000)
        assertEquals(TripEvent.Ended("trip-1"), event)
        assertEquals(TripState(), ended)
    }

    @Test
    fun `unlocking or sitting down again cancels the pending end`() {
        val pending = tracker.onLock(started(), locked = true, nowEpochMs = 0, driverSeated = false).first
        assertNull(tracker.onLock(pending, locked = false, nowEpochMs = 10, driverSeated = false).first.endPendingSinceEpochMs)
        assertNull(tracker.onDriverSeat(pending, occupied = true, nowEpochMs = 10, newId = ::newId).first.endPendingSinceEpochMs)
    }

    @Test
    fun `clock moving backwards restarts the end delay instead of ending early or never`() {
        val pending = tracker.onLock(started(), locked = true, nowEpochMs = 1_000_000, driverSeated = false).first
        val (rewound, event) = tracker.onTick(pending, nowEpochMs = 0)
        assertNull(event)
        assertEquals(0L, rewound.endPendingSinceEpochMs)
        assertEquals(TripEvent.Ended("trip-1"), tracker.onTick(rewound, nowEpochMs = 300_000).second)
    }

    @Test
    fun `user overrides belong to the trip and end with it`() {
        val withOverride = started().copy(userOverrides = setOf(kr.routinegongbang.core.model.SignalIds.STEERING_WHEEL_HEAT))
        val pending = tracker.onLock(withOverride, locked = true, nowEpochMs = 0, driverSeated = false).first
        assertEquals(emptySet(), tracker.onTick(pending, nowEpochMs = 300_000).first.userOverrides)
    }

    @Test
    fun `locking while the driver is still seated does not end the trip (auto-lock while driving)`() {
        val (state, event) = tracker.onLock(started(), locked = true, nowEpochMs = 100, driverSeated = true)
        assertNull(event)
        assertNull(state.endPendingSinceEpochMs)
    }

    @Test
    fun `startup ends a trip that is too old or whose end delay passed while the service was down`() {
        val old = started()
        assertEquals(TripState(), tracker.onStartup(old, nowEpochMs = TripTracker.MAX_TRIP_AGE_MS))
        val pending = tracker.onLock(old, locked = true, nowEpochMs = 0, driverSeated = false).first
        assertEquals(TripState(), tracker.onStartup(pending, nowEpochMs = 300_000))
        assertEquals(old, tracker.onStartup(old, nowEpochMs = 60_000))
    }
}
