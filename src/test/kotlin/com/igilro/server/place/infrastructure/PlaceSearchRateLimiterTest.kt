package com.igilro.server.place.infrastructure

import com.igilro.server.place.domain.PlaceSearchFailure
import com.igilro.server.place.domain.PlaceSearchFailureReason
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class PlaceSearchRateLimiterTest {
    @Test
    fun `rejects closely spaced requests and recovers at the interval boundary`() {
        var nanos = 0L
        val limiter = PlaceSearchRateLimiter(4) { nanos }
        limiter.acquire()
        nanos = 249_999_999L
        val failure = assertFailsWith<PlaceSearchFailure> { limiter.acquire() }
        assertEquals(PlaceSearchFailureReason.RATE_LIMITED, failure.reason)
        assertEquals(1, failure.retryAfterSeconds)
        nanos = 250_000_000L
        limiter.acquire()
    }

    @Test
    fun `concurrent requests cannot bypass admission control`() {
        val limiter = PlaceSearchRateLimiter(4) { 0L }
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = (1..20).map { pool.submit<Boolean> { runCatching { limiter.acquire() }.isSuccess } }
            assertEquals(1, results.count { it.get(2, TimeUnit.SECONDS) })
        } finally {
            pool.shutdownNow()
        }
    }
}
