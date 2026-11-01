package kr.routinegongbang.core.platform

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kr.routinegongbang.core.engine.Access
import kr.routinegongbang.core.model.Rounding
import kr.routinegongbang.core.model.SafetyClass
import kr.routinegongbang.core.model.SignalCatalog
import kr.routinegongbang.core.model.SignalId
import kr.routinegongbang.core.model.SignalSpec
import kr.routinegongbang.core.model.SignalValue
import kr.routinegongbang.core.model.ValueType

/** What the self-test needs from a car: the debug build implements it with the Pleos adapter. */
interface SelfTestCar {
    suspend fun capabilities(): Map<SignalId, Access?>

    /** Every readable signal, values already corrected per catalog; the current values arrive first. */
    fun subscribe(onChange: (SignalId, SignalValue) -> Unit)

    suspend fun write(signal: SignalId, value: SignalValue): Result<Unit>
}

/** One signal's result. [coupled]: other signals that changed while our write settled. */
data class SelfTestRow(
    val signal: SignalId,
    val access: Access?,
    val firstValue: SignalValue?,
    val wrote: SignalValue? = null,
    val echoed: Boolean? = null,
    val restored: Boolean? = null,
    val coupled: Set<SignalId> = emptySet(),
    val note: String? = null,
)

/**
 * On the emulator, checks every signal the car reports — its access, whether a value arrives — and
 * writes each comfort control one step away and back, recording whether the write echoed and what else moved
 * with it. That is where couplings and wrong areas or corrections show up. Never writes anything but COMFORT
 * signals. Runs with the routine engine stopped, so nothing reacts to its writes.
 */
class SignalSelfTest(
    private val catalog: SignalCatalog,
    private val car: SelfTestCar,
    private val settleMs: Long = 3_000,
    private val pollMs: Long = 100,
) {
    private val latest = mutableMapOf<SignalId, SignalValue>()
    private val changes = mutableListOf<SignalId>()
    private var noisy = emptySet<SignalId>()
    private val lock = Any()

    suspend fun run(onProgress: (String) -> Unit = {}): List<SelfTestRow> {
        val access = car.capabilities()
        car.subscribe { signal, value ->
            synchronized(lock) {
                if (latest[signal] != value) changes += signal
                latest[signal] = value
            }
        }
        delay(settleMs / 2)
        // What moves while nothing is written is noise, never a coupling.
        val quiet = synchronized(lock) { changes.size }
        delay(settleMs / 2)
        val first = synchronized(lock) { latest.toMap() }
        noisy = synchronized(lock) { changes.drop(quiet).toSet() }
        return access.keys.sortedBy { it.value }.map { signal ->
            val spec = catalog[signal]
            val row = SelfTestRow(signal, access[signal], first[signal])
            if (spec == null || !testable(spec, access[signal])) row else writeAndBack(spec, row, access).also { onProgress(describe(it)) }
        }
    }

    private fun testable(spec: SignalSpec, access: Access?) =
        spec.writable && spec.safetyClass == SafetyClass.COMFORT && (access == Access.WRITE || access == Access.READ_WRITE)

    private suspend fun writeAndBack(spec: SignalSpec, row: SelfTestRow, access: Map<SignalId, Access?>): SelfTestRow {
        val current = synchronized(lock) { latest[spec.id] } ?: return row.copy(note = "값이 오지 않아 쓰지 않음")
        val target = nextTo(spec, current) ?: return row.copy(note = "다른 값이 없음")
        val (mark, before) = synchronized(lock) { changes.size to latest.toMap() }
        car.write(spec.id, target).exceptionOrNull()?.let { return row.copy(wrote = target, note = "쓰기 실패: ${it.message}") }
        var echoed = false
        var coupled = emptySet<SignalId>()
        var back = Restore(restored = false)
        try {
            echoed = await(spec, target)
        } finally {
            // The car is put back even when the service stops half way.
            coupled = movedSince(mark) - spec.id
            back = withContext(NonCancellable) { putBack(spec, current, before, coupled, access) }
        }
        delay(pollMs * 5)
        return row.copy(
            wrote = target,
            echoed = echoed,
            // Without an echo nothing moved, so nothing proves it was put back.
            restored = if (echoed) back.restored else null,
            coupled = coupled,
            note = back.note(),
        )
    }

    /** How putting the car back went: the tested signal, and the coupled ones left changed. */
    private data class Restore(val restored: Boolean, val failure: String? = null, val left: List<SignalId> = emptyList()) {
        fun note(): String? = listOfNotNull(
            failure?.let { "복원 쓰기 실패: $it" },
            left.takeIf { it.isNotEmpty() }?.let { ids -> "복원 안 됨: ${ids.joinToString { it.value }}" },
        ).joinToString("; ").ifEmpty { null }
    }

    /** Signals this app may write that changed after [mark], leaving out those that drift on their own. */
    private fun movedSince(mark: Int): Set<SignalId> =
        synchronized(lock) { changes.drop(mark).toSet() }.filter { it !in noisy && catalog[it]?.writable == true }.toSet()

    /** Writes [spec] back to [current], and what moved along back to its value before, where this app may write it. */
    private suspend fun putBack(
        spec: SignalSpec,
        current: SignalValue,
        before: Map<SignalId, SignalValue>,
        coupled: Set<SignalId>,
        access: Map<SignalId, Access?>,
    ): Restore {
        val failure = car.write(spec.id, current).exceptionOrNull()?.message
        val restored = failure == null && await(spec, current)
        val left = coupled.mapNotNull { catalog[it] }.filter { testable(it, access[it.id]) }.filter { other ->
            val was = before[other.id] ?: return@filter false
            if (synchronized(lock) { latest[other.id] }?.sameAs(was, other.rounding) == true) return@filter false
            car.write(other.id, was).isFailure || !await(other, was)
        }.map { it.id }
        return Restore(restored, failure, left)
    }

    private suspend fun await(spec: SignalSpec, value: SignalValue): Boolean {
        var waited = 0L
        while (waited < settleMs) {
            if (synchronized(lock) { latest[spec.id] }?.sameAs(value, spec.rounding) == true) {
                // Let couplings that follow the echo arrive too.
                delay(settleMs / 3)
                return true
            }
            delay(pollMs)
            waited += pollMs
        }
        return false
    }

    /** One step away from [current], staying inside the signal's range. */
    private fun nextTo(spec: SignalSpec, current: SignalValue): SignalValue? = when (spec.valueType) {
        ValueType.BOOLEAN -> (current as? SignalValue.Bool)?.let { SignalValue.Bool(!it.value) }
        ValueType.ENUM -> spec.allowedValues?.firstOrNull { it != current }
        ValueType.NUMBER -> {
            val now = (current as? SignalValue.Num)?.value
            val step = (spec.rounding as? Rounding.Step)?.step ?: 1.0
            val range = spec.range
            when {
                now == null -> null
                range == null || now + step <= range.endInclusive -> SignalValue.num(now + step)
                now - step >= range.start -> SignalValue.num(now - step)
                else -> null
            }
        }
    }

    private fun describe(row: SelfTestRow) = "SELFTEST ${row.signal.value} ${row.wrote} echoed=${row.echoed} restored=${row.restored} coupled=${row.coupled.map { it.value }}"
}

/** The report as a Markdown table: one row per signal. */
fun List<SelfTestRow>.toMarkdown(): String = buildString {
    appendLine("| 신호 | 접근 | 처음 값 | 시험 값 | 회신 | 복원 | 함께 바뀐 신호 | 비고 |")
    appendLine("|---|---|---|---|---|---|---|---|")
    this@toMarkdown.forEach { r ->
        appendLine(
            "| ${r.signal.value} | ${r.access ?: "확인 실패"} | ${r.firstValue?.display() ?: "—"} | ${r.wrote?.display() ?: "—"} | " +
                "${r.echoed.mark()} | ${r.restored.mark()} | ${r.coupled.joinToString { it.value }.ifEmpty { "—" }} | ${r.note ?: ""} |",
        )
    }
}

private fun SignalValue.display(): String = when (this) {
    is SignalValue.Bool -> value.toString()
    is SignalValue.Num -> if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
    is SignalValue.Text -> value
}

private fun Boolean?.mark(): String = when (this) {
    true -> "✅"
    false -> "❌"
    null -> "—"
}
