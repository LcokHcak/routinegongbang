package kr.routinegongbang.core.platform

import kr.routinegongbang.core.engine.Access
import kr.routinegongbang.core.engine.EngineEvent
import kr.routinegongbang.core.engine.Gear
import kr.routinegongbang.core.model.AreaKeys
import kr.routinegongbang.core.model.SdkModule
import kr.routinegongbang.core.model.SignalCatalog
import kr.routinegongbang.core.model.SignalIds
import kr.routinegongbang.core.model.SignalValue
import kr.routinegongbang.core.model.ValueCorrection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RetryPolicyTest {

    @Test
    fun `delays grow exponentially up to the cap`() {
        val policy = RetryPolicy(initialDelayMs = 1_000, maxDelayMs = 5_000, maxAttempts = 10)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 5_000L, 5_000L), (1..5).map { policy.delayBefore(it) })
    }

    @Test
    fun `gives up after the last attempt`() {
        val policy = RetryPolicy(initialDelayMs = 1_000, maxDelayMs = 5_000, maxAttempts = 3)
        assertEquals(1_000L, policy.delayBefore(1))
        assertEquals(2_000L, policy.delayBefore(2))
        assertNull(policy.delayBefore(3))
    }

    @Test
    fun `unlimited attempts never give up`() {
        assertEquals(30_000L, RetryPolicy.SERVICE.delayBefore(1_000))
    }

    @Test
    fun `huge delays never overflow`() {
        val policy = RetryPolicy(initialDelayMs = Long.MAX_VALUE / 4, maxDelayMs = Long.MAX_VALUE / 2)
        assertEquals(Long.MAX_VALUE / 2, policy.delayBefore(5))
    }

    @Test
    fun `invalid policies are rejected`() {
        assertFailsWith<IllegalArgumentException> { RetryPolicy(initialDelayMs = 0) }
        assertFailsWith<IllegalArgumentException> { RetryPolicy(initialDelayMs = 10, maxDelayMs = 5) }
    }
}

class ValueCorrectionTest {

    @Test
    fun `inverted booleans are flipped both ways and other values pass through`() {
        assertEquals(SignalValue.Bool(true), ValueCorrection.INVERT_BOOLEAN.fromPlatform(SignalValue.Bool(false)))
        assertEquals(SignalValue.Bool(false), ValueCorrection.INVERT_BOOLEAN.toPlatform(SignalValue.Bool(true)))
        assertEquals(SignalValue.of(3), ValueCorrection.INVERT_BOOLEAN.fromPlatform(SignalValue.of(3)))
        assertEquals(SignalValue.Bool(false), ValueCorrection.NONE.fromPlatform(SignalValue.Bool(false)))
    }

    @Test
    fun `seat occupancy from the SDK is corrected using the catalog`() {
        val spec = SignalCatalog.default().getOrThrow(SignalIds.SEAT_OCCUPIED_DRIVER)
        assertEquals(SignalValue.Bool(true), spec.correction.fromPlatform(SignalValue.Bool(false)))
    }
}

class PercentScaleTest {

    private val scale = PercentScale(min = 0, max = 10)

    @Test
    fun `raw values map to percent and back`() {
        assertEquals(SignalValue.num(20.0), scale.toPercent(2))
        assertEquals(5, scale.toRaw(50.0))
        assertEquals(10, scale.toRaw(100.0))
    }

    @Test
    fun `out of range values are clamped`() {
        assertEquals(SignalValue.num(100.0), scale.toPercent(12))
        assertEquals(0, scale.toRaw(-5.0))
    }

    @Test
    fun `a range without width is rejected`() {
        assertFailsWith<IllegalArgumentException> { PercentScale(min = 3, max = 3) }
    }
}

class DrivingInputsTest {

    @Test
    fun `aaos gear values map to the engine gear`() {
        assertEquals(Gear.PARK, AaosGear.toGear(AaosGear.PARK))
        assertEquals(Gear.DRIVE, AaosGear.toGear(AaosGear.DRIVE))
        assertEquals(Gear.DRIVE, AaosGear.toGear(AaosGear.FIRST), "manual forward gears count as driving")
        assertEquals(Gear.REVERSE, AaosGear.toGear(AaosGear.REVERSE))
        assertEquals(Gear.NEUTRAL, AaosGear.toGear(AaosGear.NEUTRAL))
        assertEquals(Gear.UNKNOWN, AaosGear.toGear(0))
        assertEquals(Gear.UNKNOWN, AaosGear.toGear(12345))
    }

    @Test
    fun `parked needs park, a fresh zero speed and no ux restriction`() {
        val inputs = DrivingInputs().withGear(Gear.PARK).withSpeed(0.0, atMs = 1_000).withUxRestricted(false)
        assertTrue(inputs.state(nowMs = 1_500).isParked)
    }

    @Test
    fun `a speed sample that is too old counts as unknown`() {
        val inputs = DrivingInputs(maxSpeedAgeMs = 3_000).withGear(Gear.PARK).withSpeed(0.0, atMs = 1_000).withUxRestricted(false)
        assertTrue(inputs.state(nowMs = 4_000).isParked)
        val stale = inputs.state(nowMs = 4_001)
        assertNull(stale.speedKph)
        assertFalse(stale.isParked)
    }

    @Test
    fun `missing inputs are unknown, never parked`() {
        assertFalse(DrivingInputs().state(nowMs = 0).isParked)
        assertFalse(DrivingInputs().withGear(Gear.PARK).withUxRestricted(false).state(nowMs = 0).isParked)
    }

    @Test
    fun `speed from AAOS in metres per second becomes km per hour`() {
        val inputs = DrivingInputs().withSpeedMetresPerSecond(10.0f, atMs = 0)
        assertEquals(36.0, inputs.state(nowMs = 0).speedKph!!, 0.001)
    }
}

class CapabilityAssemblerTest {

    private val catalog = SignalCatalog.default()
    private val heat = SignalIds.STEERING_WHEEL_HEAT

    private fun snapshot(reported: Access?, read: Boolean, write: Boolean, open: Set<SdkModule> = SdkModule.entries.toSet()) =
        CapabilityAssembler(catalog).assemble(
            openSdks = open,
            reported = mapOf(heat to reported),
            granted = { _, kind -> if (kind == PermissionKind.READ) read else write },
        )

    @Test
    fun `reported access is limited by runtime permissions`() {
        assertEquals(Access.READ_WRITE, snapshot(Access.READ_WRITE, read = true, write = true).access[heat])
        assertEquals(Access.READ, snapshot(Access.READ_WRITE, read = true, write = false).access[heat])
        assertEquals(Access.WRITE, snapshot(Access.READ_WRITE, read = false, write = true).access[heat])
        assertEquals(Access.NONE, snapshot(Access.READ_WRITE, read = false, write = false).access[heat])
    }

    @Test
    fun `a failed or missing capability check means no access`() {
        assertEquals(Access.NONE, snapshot(reported = null, read = true, write = true).access[heat])
        assertEquals(Access.NONE, snapshot(Access.READ_WRITE, read = true, write = true).access[SignalIds.EV_SOC])
    }

    @Test
    fun `sdk availability is passed through so closed control still downgrades writes`() {
        val closed = snapshot(Access.READ_WRITE, read = true, write = true, open = setOf(SdkModule.VEHICLE_STATE))
        assertFalse(closed.canWrite(catalog.getOrThrow(heat)))
        assertFalse(closed.simulated)
    }
}

class StartupBufferTest {

    private val catalog = SignalCatalog.default()

    @Test
    fun `values before release become startup readings, later ones are forwarded`() {
        val forwarded = mutableListOf<EngineEvent.SignalChanged>()
        val buffer = StartupBuffer(catalog)
        buffer.accept(EngineEvent.SignalChanged(SignalIds.EV_SOC, SignalValue.of(70)))
        buffer.accept(EngineEvent.SignalChanged(SignalIds.EV_SOC, SignalValue.of(71)))
        buffer.accept(EngineEvent.SignalChanged(SignalIds.DOOR_LOCK, SignalValue.Bool(true), AreaKeys.ALL_PASSENGER_DOORS))
        buffer.accept(EngineEvent.SignalChanged(SignalIds.SEAT_HEAT_DRIVER, SignalValue.of(1), AreaKeys.PASSENGER_SEAT))

        val readings = buffer.release { forwarded += it }
        assertEquals(mapOf(SignalIds.EV_SOC to SignalValue.of(71), SignalIds.DOOR_LOCK to SignalValue.Bool(true)), readings)

        buffer.accept(EngineEvent.SignalChanged(SignalIds.EV_SOC, SignalValue.of(72)))
        assertEquals(listOf(EngineEvent.SignalChanged(SignalIds.EV_SOC, SignalValue.of(72))), forwarded)
    }

    @Test
    fun `release happens once`() {
        val buffer = StartupBuffer(catalog)
        buffer.release { }
        assertFailsWith<IllegalStateException> { buffer.release { } }
    }
}
