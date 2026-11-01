package kr.routinegongbang.app.pleos

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Checked against the NaviHelper 3.0.5 sources: onRouteStarted is only "route search begins";
 * success is onRoutingCompleted (or the deprecated onRouteEnded, same meaning), failure is onError.
 */
class RouteRequestTest {

    @Test
    fun `a search that began is not yet a route`() {
        val request = RouteRequest()
        request.onEvent(RouteRequest.Event.SEARCH_STARTED)
        assertNull(request.outcome)
    }

    @Test
    fun `a completed search is the route, and navigation is on`() {
        val request = RouteRequest()
        request.onEvent(RouteRequest.Event.SEARCH_STARTED)
        assertEquals(true, request.onEvent(RouteRequest.Event.ROUTING_COMPLETED))
        assertEquals(true, request.outcome?.isSuccess)
    }

    @Test
    fun `an error after the search began fails the request`() {
        val request = RouteRequest()
        request.onEvent(RouteRequest.Event.SEARCH_STARTED)
        request.onEvent(RouteRequest.Event.ERROR)
        assertTrue(request.outcome?.isFailure == true)
    }

    @Test
    fun `the first answer stays, a later one changes nothing`() {
        val request = RouteRequest()
        request.onEvent(RouteRequest.Event.ROUTING_COMPLETED)
        request.onEvent(RouteRequest.Event.ERROR)
        assertEquals(true, request.outcome?.isSuccess)
    }

    @Test
    fun `the deprecated onRouteEnded settles the request but leaves the navigation state to the route state`() {
        // An older runtime might still fire it at the end of guidance; "on" would then stick.
        val request = RouteRequest()
        assertNull(request.onEvent(RouteRequest.Event.ROUTE_ENDED))
        assertEquals(true, request.outcome?.isSuccess)
    }

    @Test
    fun `an error keeps the navigation's code for the log`() {
        val request = RouteRequest()
        request.onEvent(RouteRequest.Event.ERROR, detail = "NO_ROUTE")
        assertEquals("navigation error NO_ROUTE", request.outcome?.exceptionOrNull()?.message)
    }

    @Test
    fun `asking for the route state after errors is spaced out`() {
        // If each query is answered with another error, asking on every error loops without a pause.
        var now = 0L
        val throttle = Throttle(minIntervalMs = 5_000) { now }
        assertTrue(throttle.tryAcquire())
        now = 1_000
        assertTrue(!throttle.tryAcquire())
        now = 5_000
        assertTrue(throttle.tryAcquire())
    }

    @Test
    fun `navigation state follows the events, a search alone does not change it`() {
        val request = RouteRequest()
        assertNull(request.onEvent(RouteRequest.Event.SEARCH_STARTED))
        assertEquals(false, request.onEvent(RouteRequest.Event.CANCELLED))
        assertEquals(false, request.onEvent(RouteRequest.Event.ARRIVED))
    }
}
