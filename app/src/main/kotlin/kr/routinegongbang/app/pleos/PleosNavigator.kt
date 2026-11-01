package kr.routinegongbang.app.pleos

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.constants.BookmarkType
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kr.routinegongbang.core.engine.EngineEvent
import kr.routinegongbang.core.engine.Navigator
import kr.routinegongbang.core.platform.RetryPolicy
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kr.routinegongbang.app.BuildConfig
import kotlin.coroutines.cancellation.CancellationException

/**
 * NaviHelper adapter. Place ids `home` / `office` resolve to the OEM navigation's own bookmarks, so the user never
 * enters an address in this app. Route state feeds `navigating` (a new destination during guidance is asked first),
 * and destination arrival feeds the `NaviArrived` trigger.
 */
class PleosNavigator(
    private val context: Context,
    private val log: (String) -> Unit,
    private val retry: RetryPolicy = RetryPolicy.SERVICE,
) : Navigator {

    @Volatile private var helper: NaviHelper? = null
    @Volatile private var post: (EngineEvent) -> Unit = {}
    @Volatile private var bookmarks: CompletableDeferred<List<BookmarkInfo>>? = null
    @Volatile private var routeOutcome: CompletableDeferred<Result<Unit>>? = null
    @Volatile private var route: RouteRequest? = null
    @Volatile private var stopped = false
    /** One destination request at a time: bookmark and route answers are not correlated by the SDK. */
    private val requests = Mutex()

    suspend fun start(sink: (EngineEvent) -> Unit): Boolean {
        post = sink
        var attempt = 1
        while (true) {
            val candidate = NaviHelper(context)
            val connected = try {
                connect(candidate)
            } catch (e: CancellationException) {
                runCatching { candidate.release() }
                throw e
            }
            if (connected && !stopped) {
                candidate.addListener(listener)
                helper = candidate
                candidate.getRouteStateInfo()
                log("navi connected (attempt $attempt)")
                return true
            }
            if (stopped) return false.also { runCatching { candidate.release() } }
            runCatching { candidate.release() }
            val wait = retry.delayBefore(attempt) ?: return false.also { log("navi connection given up") }
            delay(wait)
            attempt++
        }
    }

    private suspend fun connect(candidate: NaviHelper): Boolean = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            candidate.initialize(
                onConnected = { if (cont.isActive) cont.resume(true) },
                onFailed = { e ->
                    log("navi connect failed: ${e.message}")
                    if (cont.isActive) cont.resume(false)
                },
            )
        }
    } ?: false

    override suspend fun setDestination(placeId: String): Result<Unit> = requests.withLock {
        try {
            request(placeId)
        } finally {
            bookmarks = null
            routeOutcome = null
            route = null
        }
    }

    private suspend fun request(placeId: String): Result<Unit> {
        val navi = helper ?: return Result.failure(IllegalStateException("navigation not connected"))
        val route = resolve(navi, placeId) ?: return Result.failure(NoSuchElementException("'$placeId' is not registered in the navigation bookmarks"))
        val outcome = CompletableDeferred<Result<Unit>>().also { routeOutcome = it }
        this.route = RouteRequest()
        navi.requestRoute(route)
        return withTimeoutOrNull(ROUTE_TIMEOUT_MS) { outcome.await() }
            ?: Result.failure(IllegalStateException("navigation did not complete a route"))
    }

    private suspend fun resolve(navi: NaviHelper, placeId: String): RouteInfo? {
        val category = when (placeId.lowercase()) {
            "home" -> BookmarkType.HOME
            "office" -> BookmarkType.OFFICE
            else -> null
        }
        val pending = CompletableDeferred<List<BookmarkInfo>>().also { bookmarks = it }
        navi.getBookmarkInfo()
        val list = withTimeoutOrNull(QUERY_TIMEOUT_MS) { pending.await() } ?: return null.also { log("navi bookmarks: no answer") }
        // Home and office are personal data: release builds only log whether they are set.
        log("navi bookmarks: ${list.size} (set: ${list.filter { it.isSet() }.map { it.category }})")
        if (BuildConfig.DEBUG) log("navi bookmarks (debug): ${list.joinToString { "${it.category}:${it.name}@%.2f,%.2f".format(it.latitude, it.longitude) }}")
        // The navigation lists HOME / OFFICE even when the user never set them (empty name at 0,0 on the emulator).
        val match = list.firstOrNull { (if (category != null) it.category == category else it.poiId == placeId) && it.isSet() } ?: return null
        return RouteInfo(
            latitude = match.latitude,
            longitude = match.longitude,
            displayLatitude = match.displayLatitude,
            displayLongitude = match.displayLongitude,
            poiName = match.name,
            poiId = match.poiId,
            address = match.address,
            poiSubId = match.poiSubId ?: "0",
            poiProvider = match.poiProvider,
        )
    }

    private fun BookmarkInfo.isSet(): Boolean = name.isNotBlank() && !(latitude == 0.0 && longitude == 0.0)

    fun stop() {
        stopped = true
        runCatching { helper?.removeListener(listener) }
        runCatching { helper?.release() }
        helper = null
    }

    /** After an error, at most every few seconds: an error may answer each query. */
    private val errorQueries = Throttle(STATE_QUERY_INTERVAL_MS)

    /** Asks for the route state instead of guessing. */
    private fun queryRouteState() {
        runCatching { helper?.getRouteStateInfo() }
    }

    /** Feeds the pending request, completes it on its first answer, and reports navigation on or off. */
    private fun onRouteEvent(event: RouteRequest.Event, detail: String? = null) {
        val pending = route
        val navigating = (pending ?: RouteRequest()).onEvent(event, detail)
        pending?.outcome?.let { routeOutcome?.complete(it) }
        navigating?.let { post(EngineEvent.NavigationChanged(it)) }
    }

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) = post(EngineEvent.NavigationChanged(routeStateInfo.isRouting))

        /** Only the search began; the route is set at onRoutingCompleted (NaviHelper 3.0.5). */
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) = onRouteEvent(RouteRequest.Event.SEARCH_STARTED)

        override fun onRoutingCompleted() = onRouteEvent(RouteRequest.Event.ROUTING_COMPLETED)

        /**
         * Deprecated name of onRoutingCompleted: the route search finished, not the end of navigation. It settles the
         * request; whether navigation is on comes from the route state, in case an older runtime means the end.
         */
        @Deprecated("NaviHelper: use onRoutingCompleted()")
        override fun onRouteEnded() {
            onRouteEvent(RouteRequest.Event.ROUTE_ENDED)
            queryRouteState()
        }

        override fun onRouteCancelled() = onRouteEvent(RouteRequest.Event.CANCELLED)

        /** Someone set another destination: ask for the route state again rather than guess. */
        override fun onDestinationChanged() = queryRouteState()

        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
            log("navi arrived")
            post(EngineEvent.NaviArrived)
            onRouteEvent(RouteRequest.Event.ARRIVED)
        }

        override fun onBookmarkInfo(bookmarkInfoList: List<BookmarkInfo>) {
            bookmarks?.complete(bookmarkInfoList)
        }

        override fun onError(errorCode: NaviErrorCode) {
            log("navi error $errorCode")
            onRouteEvent(RouteRequest.Event.ERROR, errorCode.toString())
            // Whatever was running before still is, or not: ask instead of assuming.
            if (errorQueries.tryAcquire()) queryRouteState()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20_000L
        const val QUERY_TIMEOUT_MS = 5_000L
        const val ROUTE_TIMEOUT_MS = 15_000L
        const val STATE_QUERY_INTERVAL_MS = 5_000L
    }
}
