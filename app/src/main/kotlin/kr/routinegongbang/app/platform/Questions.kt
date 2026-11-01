package kr.routinegongbang.app.platform

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The "실행할까요?" question as the app's own screen shows it. AAOS shows no heads-up for the app in front, so with the
 * app open the notification alone goes unseen and every question times out. The question is
 * shown here and as a notification; [NotificationConfirmer] takes whichever answer comes first.
 */
internal class Questions {

    data class Shown(val id: Int, val content: ConfirmContent)

    private val state = MutableStateFlow<Shown?>(null)
    private var nextId = 1
    private var pending: Pair<Int, CompletableDeferred<Boolean>>? = null

    /** On screen while someone may answer it; never more than one, the executor asks one at a time. */
    val current: StateFlow<Shown?> = state.asStateFlow()

    /** Suspends until answered; cancelled when the run stops waiting, which also takes it off the screen. */
    suspend fun ask(content: ConfirmContent): Boolean {
        val answer = CompletableDeferred<Boolean>()
        val shown = synchronized(this) {
            Shown(nextId++, content).also {
                pending = it.id to answer
                state.value = it
            }
        }
        try {
            return answer.await()
        } finally {
            synchronized(this) {
                if (pending?.first == shown.id) pending = null
                if (state.value?.id == shown.id) state.value = null
            }
        }
    }

    /** A late tap on an older question changes nothing. */
    fun answer(id: Int, yes: Boolean) {
        val target = synchronized(this) { pending?.takeIf { it.first == id }?.second } ?: return
        target.complete(yes)
    }

    companion object {
        /** One per process: the service asks, the activity shows. */
        val app = Questions()
    }
}
