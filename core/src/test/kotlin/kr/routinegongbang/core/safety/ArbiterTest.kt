package kr.routinegongbang.core.safety

import kr.routinegongbang.core.model.Action
import kr.routinegongbang.core.model.Routine
import kr.routinegongbang.core.model.SignalCatalog
import kr.routinegongbang.core.model.SignalId
import kr.routinegongbang.core.model.SignalIds
import kr.routinegongbang.core.model.SignalValue
import kr.routinegongbang.core.model.Trigger
import kotlin.test.Test
import kotlin.test.assertEquals

/** One writer per signal, without turning couplings into a chain. */
class ArbiterTest {

    /** "Max defrost" switches recirculation and the temperature: two siblings with a common source. */
    private val catalog = SignalCatalog.default().let { base ->
        SignalCatalog(
            base.all.map {
                if (it.id == SignalIds.DEFROSTER_FRONT) {
                    it.copy(profile = it.profile!!.copy(coupledSignals = setOf(SignalIds.RECIRCULATION, SignalIds.HVAC_TEMP_DRIVER)))
                } else {
                    it
                }
            },
        )
    }
    private val arbiter = Arbiter(catalog)

    private fun routine(id: String, vararg writes: Pair<SignalId, SignalValue>) =
        Routine(id = id, name = id, trigger = Trigger.NaviArrived, actions = writes.map { Action.Write(it.first, it.second) })

    private val on = SignalValue.Bool(true)

    @Test
    fun `H28 a write loses to an earlier write of a signal it is coupled with`() {
        val suppressed = arbiter.suppressed(listOf(routine("a", SignalIds.DEFROSTER_FRONT to on), routine("b", SignalIds.RECIRCULATION to on)))
        assertEquals(listOf(emptySet(), setOf(0)), suppressed)
    }

    @Test
    fun `two signals that only share a coupling source do not compete`() {
        val suppressed = arbiter.suppressed(listOf(routine("a", SignalIds.RECIRCULATION to on), routine("b", SignalIds.HVAC_TEMP_DRIVER to SignalValue.of(22))))
        assertEquals(listOf(emptySet<Int>(), emptySet()), suppressed)
    }

    @Test
    fun `within one routine a later write may deliberately correct what a coupling changed`() {
        val rain = routine("rain", SignalIds.DEFROSTER_FRONT to on, SignalIds.RECIRCULATION to on)
        assertEquals(listOf(emptySet<Int>()), arbiter.suppressed(listOf(rain)))
        val findings = RoutineLinter(catalog).lint(rain)
        assertEquals(listOf(Severity.WARNING), findings.filter { it.rule == "L1-3" }.map { it.severity })
    }

    @Test
    fun `the same signal twice in one routine still loses its second write`() {
        val twice = routine("twice", SignalIds.RECIRCULATION to on, SignalIds.RECIRCULATION to SignalValue.Bool(false))
        assertEquals(listOf(setOf(1)), arbiter.suppressed(listOf(twice)))
    }
}
