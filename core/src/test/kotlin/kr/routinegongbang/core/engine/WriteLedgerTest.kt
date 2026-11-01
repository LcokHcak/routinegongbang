package kr.routinegongbang.core.engine

import kr.routinegongbang.core.model.AreaKeys
import kr.routinegongbang.core.model.SignalCatalog
import kr.routinegongbang.core.model.SignalId
import kr.routinegongbang.core.model.SignalIds
import kr.routinegongbang.core.model.SignalValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WriteLedgerTest {

    private val catalog = SignalCatalog.default()
    private val heat = SignalIds.STEERING_WHEEL_HEAT
    private val temp = SignalIds.HVAC_TEMP_DRIVER
    private val timeout = catalog.getOrThrow(heat).ledgerTimeoutMs

    @Test
    fun `no write is needed when the current value already equals the target within tolerance`() {
        val ledger = WriteLedger(catalog)
        assertFalse(ledger.needsWrite(temp, current = SignalValue.Num(22.2), target = SignalValue.Num(22.0)))
        assertTrue(ledger.needsWrite(temp, current = SignalValue.Num(21.0), target = SignalValue.Num(22.0)))
        assertTrue(ledger.needsWrite(temp, current = null, target = SignalValue.Num(22.0)))
    }

    @Test
    fun `matching change inside the deadline is the app's own write`() {
        val ledger = WriteLedger(catalog).record(heat, SignalValue.of(1), nowMs = 0)
        val (origin, next) = ledger.classify(heat, SignalValue.of(1), nowMs = timeout - 1)
        assertEquals(ChangeOrigin.APP, origin)
        assertTrue(next.pending.isEmpty())
    }

    @Test
    fun `exactly at the deadline still counts as the app's write`() {
        val ledger = WriteLedger(catalog).record(heat, SignalValue.of(1), nowMs = 0)
        assertEquals(ChangeOrigin.APP, ledger.classify(heat, SignalValue.of(1), nowMs = timeout).first)
    }

    @Test
    fun `change with no pending write is a user operation`() {
        assertEquals(ChangeOrigin.USER, WriteLedger(catalog).classify(heat, SignalValue.of(2), nowMs = 0).first)
    }

    @Test
    fun `different value while a write is pending is uncertain and remembered`() {
        val ledger = WriteLedger(catalog).record(temp, SignalValue.Num(24.0), nowMs = 0)
        val (origin, next) = ledger.classify(temp, SignalValue.Num(23.0), nowMs = 100)
        assertEquals(ChangeOrigin.UNCERTAIN, origin)
        assertEquals(SignalValue.Num(23.0), next.pending.single { !it.coupled }.lastObserved, "the coupled passenger temperature is awaited too")
    }

    @Test
    fun `uncertain value that expires without a match is returned so the caller can treat it as a user override`() {
        val ledger = WriteLedger(catalog).record(temp, SignalValue.Num(24.0), nowMs = 0)
            .classify(temp, SignalValue.Num(23.0), nowMs = 100).second
        val (expired, next) = ledger.expire(nowMs = 10_000)
        assertEquals(SignalValue.Num(23.0), expired.single().lastObserved)
        assertTrue(next.pending.isEmpty())
    }

    @Test
    fun `matching value after the deadline is a user operation`() {
        val ledger = WriteLedger(catalog).record(heat, SignalValue.of(1), nowMs = 0)
        assertEquals(ChangeOrigin.USER, ledger.classify(heat, SignalValue.of(1), nowMs = timeout + 1).first)
    }

    @Test
    fun `expired writes without observations have no last value`() {
        val ledger = WriteLedger(catalog).record(heat, SignalValue.of(1), nowMs = 0)
        val (expired, next) = ledger.expire(nowMs = timeout + 1)
        assertEquals(listOf(heat), expired.map { it.signal })
        assertNull(expired.single().lastObserved)
        assertTrue(next.pending.isEmpty())
    }

    @Test
    fun `explicit catalog area and default area are the same key`() {
        val lock = SignalIds.DOOR_LOCK
        val ledger = WriteLedger(catalog).record(lock, SignalValue.Bool(true), nowMs = 0, area = AreaKeys.ALL_PASSENGER_DOORS)
        assertEquals(ChangeOrigin.APP, ledger.classify(lock, SignalValue.Bool(true), nowMs = 10).first)
    }

    @Test
    fun `H04 the echoes of two quick writes to one signal are both the app's`() {
        // Found by RandomCompositionTest seed 33: forgetting the older write made its echo look like the user's.
        val ledger = WriteLedger(catalog).record(heat, SignalValue.of(1), nowMs = 0).record(heat, SignalValue.of(2), nowMs = 5)
        val (first, afterFirst) = ledger.classify(heat, SignalValue.of(1), nowMs = 100)
        val (second, afterSecond) = afterFirst.classify(heat, SignalValue.of(2), nowMs = 110)
        assertEquals(ChangeOrigin.APP, first)
        assertEquals(ChangeOrigin.APP, second)
        assertEquals(ChangeOrigin.USER, afterSecond.classify(heat, SignalValue.of(3), nowMs = 120).first)
    }

    @Test
    fun `two identical writes in the same millisecond need two echoes`() {
        // Found by RandomCompositionTest seed 619: removing entries by equality settled both with one echo.
        val ledger = WriteLedger(catalog).record(heat, SignalValue.of(1), nowMs = 0).record(heat, SignalValue.of(1), nowMs = 0)
        val (origin, next) = ledger.classifyDetailed(heat, SignalValue.of(1), nowMs = 100).let { it.origin to it }
        assertEquals(ChangeOrigin.APP, origin)
        assertEquals(1, next.confirmed)
        assertEquals(1, next.ledger.pending.count { it.signal == heat })
    }

    @Test
    fun `a car that reports only the final value retires the older write with it`() {
        val ledger = WriteLedger(catalog).record(heat, SignalValue.of(1), nowMs = 0).record(heat, SignalValue.of(2), nowMs = 5)
        val (origin, next) = ledger.classify(heat, SignalValue.of(2), nowMs = 100)
        assertEquals(ChangeOrigin.APP, origin)
        assertTrue(next.pending.none { it.signal == heat })
    }

    @Test
    fun `writes to different signals are tracked independently`() {
        val ledger = WriteLedger(catalog)
            .record(heat, SignalValue.of(1), nowMs = 0)
            .record(SignalIds.DEFROSTER_FRONT, SignalValue.Bool(true), nowMs = 0)
        val (origin, next) = ledger.classify(SignalIds.DEFROSTER_FRONT, SignalValue.Bool(true), nowMs = 10)
        assertEquals(ChangeOrigin.APP, origin)
        assertEquals(listOf(heat), next.pending.map { it.signal })
    }

    @Test
    fun `unknown signals do not crash the engine`() {
        val unknown = SignalId("x.unknown")
        val ledger = WriteLedger(catalog)
        assertTrue(ledger.needsWrite(unknown, SignalValue.of(1), SignalValue.of(2)))
        assertEquals(ChangeOrigin.USER, ledger.classify(unknown, SignalValue.of(1), nowMs = 0).first)
    }

    @Test
    fun `recording is immutable and returns a new ledger`() {
        val empty = WriteLedger(catalog)
        empty.record(heat, SignalValue.of(1), nowMs = 0)
        assertTrue(empty.pending.isEmpty())
    }

    @Test
    fun `H28 a change of a coupled signal within the deadline is COUPLED - never a trigger, still left alone`() {
        val ledger = WriteLedger(coupledCatalog).record(SignalIds.DEFROSTER_FRONT, SignalValue.Bool(true), nowMs = 0)
        val (origin, after) = ledger.classify(SignalIds.RECIRCULATION, SignalValue.Bool(false), nowMs = 100)
        assertEquals(ChangeOrigin.COUPLED, origin)
        // A fan climbing step by step after max defrost changes several times; all of them are the car's.
        assertEquals(ChangeOrigin.COUPLED, after.classify(SignalIds.RECIRCULATION, SignalValue.Bool(true), nowMs = 200).first)
        val deadline = coupledCatalog.getOrThrow(SignalIds.DEFROSTER_FRONT).ledgerTimeoutMs
        assertEquals(ChangeOrigin.USER, after.classify(SignalIds.RECIRCULATION, SignalValue.Bool(true), nowMs = deadline + 1).first)
    }

    @Test
    fun `H28 a coupled change after the deadline is the user's, and an unused coupling expires silently`() {
        val ledger = WriteLedger(coupledCatalog).record(SignalIds.DEFROSTER_FRONT, SignalValue.Bool(true), nowMs = 0)
        val deadline = coupledCatalog.getOrThrow(SignalIds.DEFROSTER_FRONT).ledgerTimeoutMs
        assertEquals(ChangeOrigin.USER, ledger.classify(SignalIds.RECIRCULATION, SignalValue.Bool(false), nowMs = deadline + 1).first)
        val (expired, rest) = ledger.expire(nowMs = deadline + 1)
        assertEquals(listOf(SignalIds.DEFROSTER_FRONT), expired.map { it.signal }, "couplings are not writes and never become results")
        assertTrue(rest.pending.isEmpty())
    }

    @Test
    fun `H28 a real write and a coupling waiting on the same signal are both expected`() {
        val ledger = WriteLedger(coupledCatalog)
            .record(SignalIds.RECIRCULATION, SignalValue.Bool(true), nowMs = 0)
            .record(SignalIds.DEFROSTER_FRONT, SignalValue.Bool(true), nowMs = 10)
        val (own, afterOwn) = ledger.classify(SignalIds.RECIRCULATION, SignalValue.Bool(true), nowMs = 20)
        val (coupled, afterCoupled) = afterOwn.classify(SignalIds.RECIRCULATION, SignalValue.Bool(false), nowMs = 30)
        assertEquals(ChangeOrigin.APP, own)
        assertEquals(ChangeOrigin.COUPLED, coupled)
        assertEquals(ChangeOrigin.COUPLED, afterCoupled.classify(SignalIds.RECIRCULATION, SignalValue.Bool(true), nowMs = 40).first, "until its deadline")
        val deadline = 10 + coupledCatalog.getOrThrow(SignalIds.DEFROSTER_FRONT).ledgerTimeoutMs
        assertEquals(ChangeOrigin.USER, afterCoupled.classify(SignalIds.RECIRCULATION, SignalValue.Bool(true), nowMs = deadline + 1).first)
    }
}
