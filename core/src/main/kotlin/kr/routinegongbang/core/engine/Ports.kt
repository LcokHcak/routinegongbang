package kr.routinegongbang.core.engine

import kr.routinegongbang.core.model.Action
import kr.routinegongbang.core.model.AreaKey
import kr.routinegongbang.core.model.SignalId
import kr.routinegongbang.core.model.SignalValue

// Ports implemented by :app adapters (Pleos SDK, TTS, NaviHelper, UI). Fakes implement them in tests.

interface VehicleWriter {
    /** [area] is already resolved (combined where the SDK needs it); the adapter applies value corrections. */
    suspend fun write(signal: SignalId, area: AreaKey, value: SignalValue): Result<Unit>
}

/** [reason] is set when an action was downgraded; [action] lets the UI say what to do by hand. */
data class NotifyMessage(val textRes: String, val reason: Reason? = null, val action: Action? = null) {
    companion object {
        /** Text key for "please do this yourself" after a downgrade to NOTIFY. */
        const val DOWNGRADED: String = "notify.downgraded"
    }
}

interface Notifier {
    suspend fun notify(message: NotifyMessage)

    /** Shows [message], or keeps it until the car is parked: false when kept, so the run can say so. */
    suspend fun deliver(message: NotifyMessage): Boolean {
        notify(message)
        return true
    }
}

interface Navigator {
    suspend fun setDestination(placeId: String): Result<Unit>
}

/** [actions] are the ones that need approval, so the prompt can say what will happen. [voiceOnly] forbids on-screen buttons. */
data class ConfirmRequest(val routineId: String, val routineName: String, val voiceOnly: Boolean, val actions: List<Action>)

interface Confirmer {
    /** True to run, false to cancel. The executor cancels on its own after [Executor.CONFIRM_TIMEOUT_MS]. */
    suspend fun confirm(request: ConfirmRequest): Boolean
}
