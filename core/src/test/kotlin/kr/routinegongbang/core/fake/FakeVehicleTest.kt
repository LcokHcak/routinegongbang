package kr.routinegongbang.core.fake

import kotlinx.coroutines.test.runTest
import kr.routinegongbang.core.engine.EngineEvent
import kr.routinegongbang.core.model.AreaKeys
import kr.routinegongbang.core.model.SignalCatalog
import kr.routinegongbang.core.model.SignalId
import kr.routinegongbang.core.model.SignalIds
import kr.routinegongbang.core.model.SignalValue
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FakeVehicleTest {

    private val catalog = SignalCatalog.default()
    private val clock = ManualClock(Instant.parse("2026-10-27T00:00:00Z"))
    private val heat = SignalIds.STEERING_WHEEL_HEAT

    private fun vehicle() = FakeVehicle(catalog, clock, initial = mapOf(heat to SignalValue.of(0)), echoDelayMs = 200)

    @Test
    fun `write changes the value and echoes it after the delay`() = runTest {
        val car = vehicle()
        assertTrue(car.write(heat, AreaKeys.GLOBAL, SignalValue.of(2)).isSuccess)
        assertEquals(SignalValue.of(2), car.value(heat))
        assertTrue(car.dueEchoes().isEmpty())
        clock.advance(200)
        assertEquals(listOf(EngineEvent.SignalChanged(heat, SignalValue.of(2), AreaKeys.GLOBAL)), car.dueEchoes())
        assertTrue(car.dueEchoes().isEmpty(), "each echo is delivered once")
    }

    @Test
    fun `signals that cannot be subscribed never echo`() = runTest {
        val car = vehicle()
        car.write(SignalIds.WIPER_FRONT_SWITCH, AreaKeys.FRONT_WINDSHIELD, SignalValue.Text("AUTO"))
        clock.advance(1_000)
        assertTrue(car.dueEchoes().isEmpty())
    }

    @Test
    fun `failing signals return a failure and keep their value`() = runTest {
        val car = FakeVehicle(catalog, clock, initial = mapOf(heat to SignalValue.of(0)), failing = setOf(heat))
        assertTrue(car.write(heat, AreaKeys.GLOBAL, SignalValue.of(1)).isFailure)
        assertEquals(SignalValue.of(0), car.value(heat))
    }

    @Test
    fun `a user change updates the value and returns the event to post`() {
        val car = vehicle()
        assertEquals(EngineEvent.SignalChanged(heat, SignalValue.of(3), AreaKeys.GLOBAL), car.userSets(heat, SignalValue.of(3)))
        assertEquals(SignalValue.of(3), car.value(heat))
    }

    @Test
    fun `writes are logged in order`() = runTest {
        val car = vehicle()
        car.write(heat, AreaKeys.GLOBAL, SignalValue.of(1))
        car.write(SignalIds.DEFROSTER_FRONT, AreaKeys.FRONT_WINDSHIELD, SignalValue.Bool(true))
        assertEquals(listOf(heat to SignalValue.of(1), SignalIds.DEFROSTER_FRONT to SignalValue.Bool(true)), car.writes.map { it.signal to it.value })
    }

    @Test
    fun `readings expose the current values for engine startup`() {
        assertEquals(mapOf(heat to SignalValue.of(0)), vehicle().readings())
    }

    @Test
    fun `writing the value the car already has does not echo`() = runTest {
        val car = vehicle()
        car.write(heat, AreaKeys.GLOBAL, SignalValue.of(0))
        clock.advance(1_000)
        assertTrue(car.dueEchoes().isEmpty())
    }

    @Test
    fun `a user change before the echo cancels the stale echo`() = runTest {
        val car = vehicle()
        car.write(heat, AreaKeys.GLOBAL, SignalValue.of(2))
        car.userSets(heat, SignalValue.of(0))
        clock.advance(1_000)
        assertTrue(car.dueEchoes().isEmpty())
        assertEquals(SignalValue.of(0), car.value(heat))
    }

    @Test
    fun `manual clock never goes backwards`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> { clock.advance(-1) }
    }

    @Test
    fun `H28 a coupled write also changes and echoes the coupled signal, like max defrost on a real car`() = runTest {
        val couplings: Map<Pair<SignalId, SignalValue>, Map<SignalId, SignalValue>> =
            mapOf((SignalIds.DEFROSTER_FRONT to SignalValue.Bool(true)) to mapOf(SignalIds.RECIRCULATION to SignalValue.Bool(false)))
        val car = FakeVehicle(catalog, clock, mapOf(SignalIds.RECIRCULATION to SignalValue.Bool(true)), couplings = couplings)
        car.write(SignalIds.DEFROSTER_FRONT, catalog.getOrThrow(SignalIds.DEFROSTER_FRONT).area, SignalValue.Bool(true))
        assertEquals(SignalValue.Bool(false), car.value(SignalIds.RECIRCULATION))
        clock.advance(FakeVehicle.DEFAULT_ECHO_DELAY_MS)
        assertEquals(setOf(SignalIds.DEFROSTER_FRONT, SignalIds.RECIRCULATION), car.dueEchoes().map { it.signal }.toSet())
        assertEquals(listOf(SignalIds.DEFROSTER_FRONT), car.writes.map { it.signal }, "the coupled change is the car's, not a write")
    }
}
