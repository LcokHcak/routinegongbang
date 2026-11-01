package kr.routinegongbang.core.engine

/** Circuit breaker: at most [maxPerWindow] vehicle writes per sliding window. Immutable. */
class WriteRateLimiter private constructor(
    val maxPerWindow: Int,
    val windowMs: Long,
    private val timestamps: List<Long>,
) {
    constructor(maxPerWindow: Int, windowMs: Long) : this(maxPerWindow, windowMs, emptyList())

    init {
        require(maxPerWindow > 0 && windowMs > 0) { "Rate limit and window must be positive" }
    }

    fun allows(nowMs: Long): Boolean = recent(nowMs).size < maxPerWindow

    fun record(nowMs: Long): WriteRateLimiter = WriteRateLimiter(maxPerWindow, windowMs, recent(nowMs) + nowMs)

    private fun recent(nowMs: Long): List<Long> = timestamps.filter { nowMs - it < windowMs }

    companion object {
        const val DEFAULT_MAX_PER_MINUTE: Int = 10
        fun default(): WriteRateLimiter = WriteRateLimiter(DEFAULT_MAX_PER_MINUTE, 60_000)
    }
}
