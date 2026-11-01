package kr.routinegongbang.core.engine

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WriteRateLimiterTest {

    @Test
    fun `allows writes until the limit within the window`() {
        var limiter = WriteRateLimiter(maxPerWindow = 3, windowMs = 60_000)
        repeat(3) { i ->
            assertTrue(limiter.allows(nowMs = i * 1_000L))
            limiter = limiter.record(nowMs = i * 1_000L)
        }
        assertFalse(limiter.allows(nowMs = 3_000))
    }

    @Test
    fun `old writes leave the window`() {
        var limiter = WriteRateLimiter(maxPerWindow = 1, windowMs = 60_000)
        limiter = limiter.record(nowMs = 0)
        assertFalse(limiter.allows(nowMs = 59_999))
        assertTrue(limiter.allows(nowMs = 60_000))
    }

    @Test
    fun `record is immutable`() {
        val empty = WriteRateLimiter(maxPerWindow = 1, windowMs = 60_000)
        empty.record(nowMs = 0)
        assertTrue(empty.allows(nowMs = 1))
    }
}
