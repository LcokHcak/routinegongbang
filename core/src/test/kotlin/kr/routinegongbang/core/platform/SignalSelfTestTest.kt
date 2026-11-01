package kr.routinegongbang.core.platform

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kr.routinegongbang.core.engine.Access
import kr.routinegongbang.core.model.SignalCatalog
import kr.routinegongbang.core.model.SignalId
import kr.routinegongbang.core.model.SignalIds
import kr.routinegongbang.core.model.SignalValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The self-test writes every comfort control once, watches what else moves, and puts it back. */
class SignalSelfTestTest {

    private val catalog = SignalCatalog.default()

    /** Echoes every write; turning max defrost on also moves the fan, like a real car might. Mirror heat never echoes. */
    private class Car(private val access: Map<SignalId, Access?>, start: Map<SignalId, SignalValue>) : SelfTestCar {
        val values = start.toMutableMap()
        val writes = mutableListOf<Pair<SignalId, SignalValue>>()
        private var listener: (SignalId, SignalValue) -> Unit = { _, _ -> }

        override suspend fun capabilities() = access
        override fun subscribe(onChange: (SignalId, SignalValue) -> Unit) {
            listener = onChange
            values.forEach { (s, v) -> onChange(s, v) }
        }
        override suspend fun write(signal: SignalId, value: SignalValue): Result<Unit> {
            writes += signal to value
            if (signal == SignalIds.MIRROR_HEAT) return Result.success(Unit)
            set(signal, value)
            if (signal == SignalIds.HVAC_MAX_DEFROST && value == SignalValue.Bool(true)) set(SignalIds.HVAC_FAN_SPEED, SignalValue.of(8))
            return Result.success(Unit)
        }
        private fun set(signal: SignalId, value: SignalValue) {
            values[signal] = value
            listener(signal, value)
        }
    }

    private val start = mapOf(
        SignalIds.HVAC_MAX_DEFROST to SignalValue.Bool(false), SignalIds.HVAC_FAN_SPEED to SignalValue.of(3),
        SignalIds.MIRROR_HEAT to SignalValue.of(0), SignalIds.SEAT_HEAT_DRIVER to SignalValue.of(3), SignalIds.EV_SOC to SignalValue.of(70),
    )
    private val access = mapOf(
        SignalIds.HVAC_MAX_DEFROST to Access.READ_WRITE, SignalIds.HVAC_FAN_SPEED to Access.READ_WRITE, SignalIds.MIRROR_HEAT to Access.READ_WRITE,
        SignalIds.SEAT_HEAT_DRIVER to Access.READ_WRITE, SignalIds.EV_SOC to Access.READ, SignalIds.HVAC_AC to Access.NONE,
    )

    private suspend fun run(): Pair<Car, Map<SignalId, SelfTestRow>> {
        val car = Car(access, start)
        return car to SignalSelfTest(catalog, car).run().associateBy { it.signal }
    }

    @Test
    fun `a write that echoes is confirmed, and the value is put back`() = runTest {
        val (car, rows) = run()
        val fan = rows.getValue(SignalIds.HVAC_FAN_SPEED)
        assertEquals(SignalValue.of(4), fan.wrote)
        assertEquals(true, fan.echoed)
        assertEquals(true, fan.restored)
        assertEquals(SignalValue.of(3), car.values[SignalIds.HVAC_FAN_SPEED])
    }

    @Test
    fun `H28 what moves along with a write is reported as a coupling`() = runTest {
        val (_, rows) = run()
        assertEquals(setOf(SignalIds.HVAC_FAN_SPEED), rows.getValue(SignalIds.HVAC_MAX_DEFROST).coupled)
        assertTrue(rows.getValue(SignalIds.HVAC_FAN_SPEED).coupled.isEmpty())
    }

    @Test
    fun `a write without an echo is reported, not hidden, and its restore is not claimed`() = runTest {
        val (_, rows) = run()
        assertEquals(false, rows.getValue(SignalIds.MIRROR_HEAT).echoed)
        assertNull(rows.getValue(SignalIds.MIRROR_HEAT).restored, "nothing moved, so nothing proves it was put back")
    }

    @Test
    fun `a reading that drifts on its own is not reported as a coupling`() = runTest {
        // The cabin temperature changes every few seconds whatever we write.
        val car = object : SelfTestCar {
            val inner = Car(access + (SignalIds.CABIN_TEMPERATURE to Access.READ), start + (SignalIds.CABIN_TEMPERATURE to SignalValue.num(19.0)))
            var listener: (SignalId, SignalValue) -> Unit = { _, _ -> }
            var t = 19.0
            override suspend fun capabilities() = inner.capabilities()
            override fun subscribe(onChange: (SignalId, SignalValue) -> Unit) {
                listener = onChange
                inner.subscribe(onChange)
            }
            override suspend fun write(signal: SignalId, value: SignalValue): Result<Unit> {
                t += 0.1
                listener(SignalIds.CABIN_TEMPERATURE, SignalValue.num(t))
                return inner.write(signal, value)
            }
        }
        val rows = SignalSelfTest(catalog, car).run().associateBy { it.signal }
        assertTrue(rows.values.none { SignalIds.CABIN_TEMPERATURE in it.coupled })
    }

    @Test
    fun `a coupled signal without write access is not written back`() = runTest {
        val car = Car(access + (SignalIds.HVAC_FAN_SPEED to Access.READ), start)
        SignalSelfTest(catalog, car).run()
        assertTrue(car.writes.none { it.first == SignalIds.HVAC_FAN_SPEED })
    }

    @Test
    fun `a coupled signal that could not be put back is reported`() = runTest {
        // Max defrost moves the fan to 8; once it has, putting the fan back fails.
        val car = object : SelfTestCar {
            val inner = Car(access, start)
            var defrosted = false
            override suspend fun capabilities() = inner.capabilities()
            override fun subscribe(onChange: (SignalId, SignalValue) -> Unit) = inner.subscribe(onChange)
            override suspend fun write(signal: SignalId, value: SignalValue): Result<Unit> =
                if (signal == SignalIds.HVAC_FAN_SPEED && defrosted) Result.failure(IllegalStateException("busy"))
                else inner.write(signal, value).also { if (signal == SignalIds.HVAC_MAX_DEFROST) defrosted = true }
        }
        val rows = SignalSelfTest(catalog, car).run().associateBy { it.signal }
        val note = rows.getValue(SignalIds.HVAC_MAX_DEFROST).note.orEmpty()
        assertTrue(SignalIds.HVAC_FAN_SPEED.value in note, "note was: $note")
    }

    @Test
    fun `a self-test stopped half way still puts the value back`() = runTest {
        // The service may be destroyed between the write and the restore.
        val car = Car(access, start)
        val job = launch { SignalSelfTest(catalog, car).run() }
        advanceTimeBy(3_000 + 500)
        job.cancel()
        job.join()
        assertTrue(car.writes.isNotEmpty(), "the first write happened")
        val first = car.writes.first()
        assertEquals(start[first.first], car.values[first.first])
    }

    @Test
    fun `a value at the top of its range is tested by going one step down`() = runTest {
        val (_, rows) = run()
        assertEquals(SignalValue.of(2), rows.getValue(SignalIds.SEAT_HEAT_DRIVER).wrote)
    }

    @Test
    fun `readings and signals without access are listed but never written`() = runTest {
        val (car, rows) = run()
        assertEquals(SignalValue.of(70), rows.getValue(SignalIds.EV_SOC).firstValue)
        assertNull(rows.getValue(SignalIds.EV_SOC).wrote)
        assertEquals(Access.NONE, rows.getValue(SignalIds.HVAC_AC).access)
        assertTrue(car.writes.none { it.first == SignalIds.EV_SOC || it.first == SignalIds.HVAC_AC })
    }

    @Test
    fun `only comfort controls are ever written, never body or excluded signals`() = runTest {
        val car = Car(catalog.all.associate { it.id to Access.READ_WRITE }, mapOf(SignalIds.DOOR_LOCK to SignalValue.Bool(false), SignalIds.WINDOW_POSITION_DRIVER to SignalValue.of(30)))
        SignalSelfTest(catalog, car).run()
        assertTrue(car.writes.none { it.first == SignalIds.DOOR_LOCK || it.first == SignalIds.WINDOW_POSITION_DRIVER || it.first == SignalIds.WIPER_FRONT_SWITCH })
    }

    @Test
    fun `the report is a markdown table with one row per signal`() = runTest {
        val (_, rows) = run()
        val table = rows.values.toList().toMarkdown()
        assertTrue(table.lines().first().startsWith("| 신호 |"))
        assertTrue(table.contains("| hvac.maxDefrost |"))
        assertTrue(table.contains("hvac.fan.speed"))
    }
}
