package com.igilro.server.place.infrastructure

import com.igilro.server.place.domain.PlaceSearchFailure
import com.igilro.server.place.domain.PlaceSearchFailureReason

/** Per-process admission limit, without waiting, user identifiers or a query/result cache. */
class PlaceSearchRateLimiter(requestsPerSecond: Int, private val nanoTime: () -> Long = System::nanoTime) {
    private val interval = 1_000_000_000L / requestsPerSecond.also { require(it in 1..1000) }
    private var lastAccepted: Long? = null

    @Synchronized
    fun acquire() {
        val now = nanoTime()
        if (lastAccepted?.let { now - it < interval } == true) {
            throw PlaceSearchFailure(PlaceSearchFailureReason.RATE_LIMITED, retryAfterSeconds = 1)
        }
        lastAccepted = now
    }
}
