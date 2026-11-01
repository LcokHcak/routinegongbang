package kr.routinegongbang.app.service

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kr.routinegongbang.app.BuildConfig
import kr.routinegongbang.core.engine.ActionResult
import kr.routinegongbang.core.engine.Decision
import kr.routinegongbang.core.engine.EngineObserver
import kr.routinegongbang.core.engine.ResultUpdate
import kr.routinegongbang.core.model.SignalId
import kr.routinegongbang.core.model.SignalValue
import java.time.Instant

/** Recent engine activity for logcat (`RW_ENGINE`) and the status screen. Thread-safe, bounded. */
object EngineLog {
    const val TAG = "RW_ENGINE"
    private const val MAX_LINES = 200
    private val buffer = MutableStateFlow<List<String>>(emptyList())

    /** Newest last; observed by the debug panel's event stream. */
    val lines: StateFlow<List<String>> = buffer.asStateFlow()

    fun add(line: String) {
        Log.i(TAG, line)
        buffer.update { (it + line).takeLast(MAX_LINES) }
    }

    fun snapshot(): List<String> = buffer.value
}

/** Writes every decision and result to [EngineLog]; the debug panel builds on the same stream later. */
internal object LogObserver : EngineObserver {
    override fun onDecision(decision: Decision) = EngineLog.add(
        when (decision) {
            is Decision.Ran -> "RAN ${decision.report.routineId}${if (decision.report.simulated) " (모의)" else ""}${if (decision.report.dryRun) " DRY_RUN" else ""}: " +
                decision.report.results.joinToString { it.label() }
            is Decision.LimitBlocked -> "BLOCKED ${decision.routineId}: ${decision.reason}"
            is Decision.ConditionNotMet -> "CONDITION ${decision.routineId}: not met" +
                if (decision.missing.isEmpty()) "" else " (missing ${decision.missing.joinToString { it.value }})"
            is Decision.ReminderShown -> "REMINDER ${decision.routineId}: ${decision.textRes}"
            is Decision.StoreFailed -> "STORE FAILED: ${decision.detail}"
            is Decision.EventFailed -> "EVENT FAILED ${decision.event}: ${decision.detail}"
            is Decision.TimerFired -> "TIMER ${decision.kind} ${decision.routineId}"
        },
    )

    override fun onResult(update: ResultUpdate) = EngineLog.add("RESULT ${update.routineId}#${update.actionIndex}: ${update.result.label()}")

    override fun onTripStarted(tripId: String, at: Instant) = EngineLog.add("TRIP started")

    /** What the driver does by hand is personal data: only debug builds log it. */
    override fun onUserChange(tripId: String?, signal: SignalId, value: SignalValue, at: Instant) {
        if (BuildConfig.DEBUG) EngineLog.add("USER ${signal.value}=$value")
    }

    private fun ActionResult.label(): String = when (this) {
        is ActionResult.Sent -> "SENT ${signal.value}"
        ActionResult.NoChange -> "NO_CHANGE"
        is ActionResult.Failed -> "FAILED $reason ${detail.orEmpty()}"
        is ActionResult.Notified -> "NOTIFIED${reason?.let { " $it" }.orEmpty()}${if (held) " HELD" else ""}"
        is ActionResult.Cancelled -> "CANCELLED $reason"
        is ActionResult.Skipped -> "SKIPPED $reason"
        ActionResult.Deferred -> "DEFERRED"
        ActionResult.Requested -> "REQUESTED"
        is ActionResult.Confirmed -> "CONFIRMED ${signal.value}"
        is ActionResult.SentUnconfirmed -> "SENT_UNCONFIRMED ${signal.value}"
        ActionResult.Scheduled -> "SCHEDULED"
        ActionResult.Waited -> "WAITED"
    }
}

/** Fans one engine stream out to several observers (log, habit book). */
internal class CompositeObserver(private val observers: List<EngineObserver>) : EngineObserver {
    override fun onDecision(decision: Decision) = observers.forEach { it.onDecision(decision) }
    override fun onResult(update: ResultUpdate) = observers.forEach { it.onResult(update) }
    override fun onTripStarted(tripId: String, at: Instant) = observers.forEach { it.onTripStarted(tripId, at) }
    override fun onTripStarted(tripId: String, at: Instant, values: Map<SignalId, SignalValue>) =
        observers.forEach { it.onTripStarted(tripId, at, values) }
    override fun onUserChange(tripId: String?, signal: SignalId, value: SignalValue, at: Instant) =
        observers.forEach { it.onUserChange(tripId, signal, value, at) }
}
