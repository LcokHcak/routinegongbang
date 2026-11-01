package kr.routinegongbang.core.fake

import kr.routinegongbang.core.engine.ConfirmRequest
import kr.routinegongbang.core.engine.Confirmer
import kr.routinegongbang.core.engine.Navigator
import kr.routinegongbang.core.engine.Notifier
import kr.routinegongbang.core.engine.NotifyMessage

/** Records notifications instead of showing them. */
class FakeNotifier : Notifier {
    private val lock = Any()
    private val log = mutableListOf<NotifyMessage>()
    val messages: List<NotifyMessage> get() = synchronized(lock) { log.toList() }

    override suspend fun notify(message: NotifyMessage) {
        synchronized(lock) { log += message }
    }
}

/** Records destinations instead of starting guidance. */
class FakeNavigator : Navigator {
    private val lock = Any()
    private val log = mutableListOf<String>()
    val destinations: List<String> get() = synchronized(lock) { log.toList() }

    override suspend fun setDestination(placeId: String): Result<Unit> {
        synchronized(lock) { log += placeId }
        return Result.success(Unit)
    }
}

/** Answers every confirmation with [answer] right away. */
class FakeConfirmer(@Volatile var answer: Boolean = true) : Confirmer {
    private val lock = Any()
    private val log = mutableListOf<ConfirmRequest>()
    val requests: List<ConfirmRequest> get() = synchronized(lock) { log.toList() }

    override suspend fun confirm(request: ConfirmRequest): Boolean {
        synchronized(lock) { log += request }
        return answer
    }
}
