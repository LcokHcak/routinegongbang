package kr.routinegongbang.core.platform

/**
 * Exponential backoff for reconnecting to platform services (`Vehicle.initialize()` has no
 * connect callback, and the SDK gives up after its own 30 × 500 ms bind attempts).
 * [maxAttempts] null retries forever, which a long-running service wants.
 */
data class RetryPolicy(
    val initialDelayMs: Long = 1_000,
    val maxDelayMs: Long = 30_000,
    val maxAttempts: Int? = null,
) {
    init {
        require(initialDelayMs > 0 && maxDelayMs >= initialDelayMs) { "Invalid backoff delays" }
        require(maxAttempts == null || maxAttempts > 0) { "maxAttempts must be positive" }
    }

    /** Delay to wait after failed attempt number [attempt] (1-based), or null to give up. */
    fun delayBefore(attempt: Int): Long? {
        require(attempt >= 1) { "attempt is 1-based" }
        if (maxAttempts != null && attempt >= maxAttempts) return null
        val factor = 1L shl (attempt - 1).coerceAtMost(MAX_SHIFT)
        // Compare before multiplying so large delays cannot overflow.
        return if (initialDelayMs > maxDelayMs / factor) maxDelayMs else initialDelayMs * factor
    }

    companion object {
        private const val MAX_SHIFT = 30
        val SERVICE = RetryPolicy()
    }
}
