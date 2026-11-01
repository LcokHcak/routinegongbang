package kr.routinegongbang.core.model

/**
 * A routine that passed [RoutineValidator]. Only the validator can create one, so the executor
 * never runs an unchecked routine. Equality follows the wrapped routine.
 */
class ValidatedRoutine internal constructor(val routine: Routine) {
    override fun equals(other: Any?): Boolean = other is ValidatedRoutine && other.routine == routine
    override fun hashCode(): Int = routine.hashCode()
    override fun toString(): String = "ValidatedRoutine(${routine.id})"
}

sealed interface ValidationOutcome {
    data class Valid(val routine: ValidatedRoutine) : ValidationOutcome
    data class Invalid(val issues: List<ValidationIssue>) : ValidationOutcome
}
