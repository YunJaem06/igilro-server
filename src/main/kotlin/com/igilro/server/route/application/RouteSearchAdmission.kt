package com.igilro.server.route.application

enum class RouteSearchRejectionReason { RATE_LIMITED, BUSY }

class RouteSearchRejected(
    val reason: RouteSearchRejectionReason,
    val retryAfterSeconds: Long,
) : RuntimeException(reason.name)

/** One process-wide token bucket and execution budget shared by both route searches. */
class RouteSearchAdmission(
    private val requestsPerSecond: Int = 4,
    private val burstCapacity: Int = 4,
    private val maxConcurrentSearches: Int = 2,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    init {
        require(requestsPerSecond in 1..1000) { "Route search rate must be 1..1000 per second" }
        require(burstCapacity in 1..1000) { "Route search burst capacity must be 1..1000" }
        require(maxConcurrentSearches in 1..64) { "Concurrent route searches must be 1..64" }
    }

    // Fixed-point tokens preserve the exact refill boundary without floating-point drift.
    private val capacity = burstCapacity.toLong() * TOKEN_UNITS
    private var tokens = capacity
    private var lastRefill = nanoTime()
    private var running = 0

    fun <T> execute(search: () -> T): T {
        acquire()
        return try {
            search()
        } finally {
            release()
        }
    }

    @Synchronized
    private fun acquire() {
        val now = nanoTime()
        val elapsed = now - lastRefill
        if (elapsed > 0) {
            val missing = capacity - tokens
            tokens = if (elapsed >= ceilingDivide(missing, requestsPerSecond.toLong())) capacity
                else tokens + elapsed * requestsPerSecond
            lastRefill = now
        }
        if (tokens < TOKEN_UNITS) {
            val retry = ceilingDivide(TOKEN_UNITS - tokens, requestsPerSecond * TOKEN_UNITS).coerceAtLeast(1)
            throw RouteSearchRejected(RouteSearchRejectionReason.RATE_LIMITED, retry)
        }
        if (running >= maxConcurrentSearches) {
            throw RouteSearchRejected(RouteSearchRejectionReason.BUSY, 1)
        }
        // Rejected requests consume neither a token nor a running slot.
        tokens -= TOKEN_UNITS
        running++
    }

    @Synchronized
    private fun release() {
        running--
    }

    private fun ceilingDivide(value: Long, divisor: Long) = (value + divisor - 1) / divisor

    private companion object { const val TOKEN_UNITS = 1_000_000_000L }
}
