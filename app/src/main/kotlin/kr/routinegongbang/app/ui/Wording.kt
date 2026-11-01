package kr.routinegongbang.app.ui

import kr.routinegongbang.app.R
import kr.routinegongbang.core.model.CompareOp
import kr.routinegongbang.core.model.SignalValue

/** Which words say a comparison or a state: words a driver uses, never ≥ or "켜기" for a state. */
internal object Wording {

    fun compare(op: CompareOp): Int = when (op) {
        CompareOp.EQ -> R.string.compare_eq
        CompareOp.NE -> R.string.compare_ne
        CompareOp.LT -> R.string.compare_lt
        CompareOp.LE -> R.string.compare_le
        CompareOp.GT -> R.string.compare_gt
        CompareOp.GE -> R.string.compare_ge
    }

    /** A switch as it is (켜짐 / 꺼짐) in triggers and conditions; actions keep 켜기 / 끄기. */
    fun state(value: SignalValue.Bool): Int = if (value.value) R.string.value_state_on else R.string.value_state_off
}
