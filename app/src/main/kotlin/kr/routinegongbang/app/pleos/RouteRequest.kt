package kr.routinegongbang.app.pleos

/**
 * One destination request and what the navigation said about it, as NaviHelper 3.0.5 defines its events:
 * onRouteStarted is only "route search begins"; the route is set at onRoutingCompleted (or the deprecated
 * onRouteEnded, the same moment), and onError fails it. The first answer stays.
 */
internal class RouteRequest {
    /**
     * [ROUTE_ENDED] is the deprecated onRouteEnded: it settles the request like [ROUTING_COMPLETED], but says nothing
     * about navigation being on, as an older runtime might still send it at the end of guidance.
     */
    enum class Event { SEARCH_STARTED, ROUTING_COMPLETED, ROUTE_ENDED, ERROR, CANCELLED, ARRIVED }

    @Volatile var outcome: Result<Unit>? = null
        private set

    /**
     * Handles [event]; returns whether navigation is now on, off, or null when the event says nothing about it.
     * [detail] is the navigation's error code, kept for the log.
     */
    @Synchronized
    fun onEvent(event: Event, detail: String? = null): Boolean? = when (event) {
        Event.SEARCH_STARTED -> null
        Event.ROUTING_COMPLETED -> true.also { settle(Result.success(Unit)) }
        Event.ROUTE_ENDED -> null.also { settle(Result.success(Unit)) }
        Event.ERROR -> null.also { settle(Result.failure(IllegalStateException(listOfNotNull("navigation error", detail).joinToString(" ")))) }
        Event.CANCELLED, Event.ARRIVED -> false
    }

    private fun settle(result: Result<Unit>) {
        if (outcome == null) outcome = result
    }
}

/** At most one go per [minIntervalMs]: a query answered by another error must not loop without a pause. */
internal class Throttle(private val minIntervalMs: Long, private val now: () -> Long = System::currentTimeMillis) {
    private var last: Long? = null

    @Synchronized
    fun tryAcquire(): Boolean {
        val at = now()
        val previous = last
        if (previous != null && at - previous < minIntervalMs) return false
        last = at
        return true
    }
}
