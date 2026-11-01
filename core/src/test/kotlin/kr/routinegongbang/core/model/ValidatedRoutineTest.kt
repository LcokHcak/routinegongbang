package kr.routinegongbang.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ValidatedRoutineTest {

    private val validator = RoutineValidator(SignalCatalog.default())
    private val routine = Routine(
        id = "r_heat",
        name = "겨울 출근",
        trigger = Trigger.RideStart,
        actions = listOf(Action.Write(SignalIds.STEERING_WHEEL_HEAT, SignalValue.of(1))),
    )

    @Test
    fun `valid routine becomes a validated routine`() {
        val outcome = assertIs<ValidationOutcome.Valid>(validator.validated(routine))
        assertEquals(routine, outcome.routine.routine)
    }

    @Test
    fun `invalid routine returns its issues and no validated routine`() {
        val bad = routine.copy(actions = listOf(Action.Write(SignalIds.LOW_BEAM, SignalValue.Bool(true))))
        val outcome = assertIs<ValidationOutcome.Invalid>(validator.validated(bad))
        assertEquals(listOf<ValidationIssue>(ValidationIssue.Excluded(SignalIds.LOW_BEAM)), outcome.issues)
    }
}
