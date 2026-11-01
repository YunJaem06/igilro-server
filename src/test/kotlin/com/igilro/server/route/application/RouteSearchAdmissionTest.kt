package com.igilro.server.route.application

import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

class RouteSearchAdmissionTest {
    @Test
    fun `default burst admits an immediate comparison and refills at the exact boundary`() {
        val now = AtomicLong()
        val admission = RouteSearchAdmission(nanoTime = now::get)
        repeat(4) { assertEquals(it, admission.execute { it }) }
        assertLimited(admission)
        now.set(249_999_999)
        assertLimited(admission)
        now.incrementAndGet()
        assertEquals("refilled", admission.execute { "refilled" })
        assertLimited(admission)
    }

    @Test
    fun `idle time cannot accumulate more than the configured burst or overflow refill arithmetic`() {
        val now = AtomicLong()
        val admission = RouteSearchAdmission(1000, 3, 1, now::get)
        repeat(3) { admission.execute {} }
        now.set(Long.MAX_VALUE)
        repeat(3) { admission.execute {} }
        assertLimited(admission)
    }

    @Test
    fun `nano time wraparound preserves elapsed refill time`() {
        val now = AtomicLong(Long.MAX_VALUE - 100_000_000)
        val admission = RouteSearchAdmission(4, 1, 1, now::get)
        admission.execute {}
        now.addAndGet(250_000_000)
        admission.execute {}
        assertLimited(admission)
    }

    @Test
    fun `a successful search releases its execution slot without refunding its rate token`() {
        val admission = RouteSearchAdmission(1, 2, 1) { 0L }
        admission.execute {}
        admission.execute {}
        assertLimited(admission)
    }

    @Test
    fun `exceptions and errors release slots while failed work still consumes rate budget`() {
        for (failure in listOf(IllegalStateException("search failed"), AssertionError("search failed"))) {
            val now = AtomicLong()
            val admission = RouteSearchAdmission(1, 1, 1, now::get)
            val caught = assertFails { admission.execute<Unit> { throw failure } }
            assertSame(failure, caught)
            assertLimited(admission)
            now.set(1_000_000_000)
            admission.execute {}
        }
    }

    @Test
    fun `busy requests never execute or consume the remaining token`() {
        val admission = RouteSearchAdmission(1, 2, 1) { 0L }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        Executors.newSingleThreadExecutor().use { executor ->
            val running = executor.submit {
                admission.execute { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)) }
            }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                repeat(3) {
                    val rejected = assertFailsWith<RouteSearchRejected> { admission.execute { fail("Busy work must not start") } }
                    assertEquals(RouteSearchRejectionReason.BUSY, rejected.reason)
                    assertEquals(1, rejected.retryAfterSeconds)
                }
            } finally { release.countDown() }
            running.get(5, TimeUnit.SECONDS)
        }
        admission.execute {}
        assertLimited(admission)
    }

    @Test
    fun `simultaneous callers cannot overbook slots and rejections do not wait for running searches`() {
        val admission = RouteSearchAdmission(1000, 100, 3) { 0L }
        val start = CountDownLatch(1)
        val entered = CountDownLatch(3)
        val rejected = CountDownLatch(17)
        val release = CountDownLatch(1)
        val active = AtomicInteger()
        val peak = AtomicInteger()
        Executors.newFixedThreadPool(20).use { executor ->
            val futures = (1..20).map {
                executor.submit {
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    try {
                        admission.execute {
                            val count = active.incrementAndGet()
                            peak.accumulateAndGet(count, ::maxOf)
                            entered.countDown()
                            try { assertTrue(release.await(10, TimeUnit.SECONDS)) }
                            finally { active.decrementAndGet() }
                        }
                    } catch (failure: RouteSearchRejected) {
                        assertEquals(RouteSearchRejectionReason.BUSY, failure.reason)
                        rejected.countDown()
                    }
                }
            }
            try {
                start.countDown()
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertTrue(rejected.await(5, TimeUnit.SECONDS), "Rejections must finish while all slots remain occupied")
                assertEquals(3, active.get())
            } finally { release.countDown() }
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
        }
        assertEquals(3, peak.get())
        repeat(3) { admission.execute {} }
    }

    @Test
    fun `simultaneous requests cannot exceed the burst even when work completes immediately`() {
        val admission = RouteSearchAdmission(1, 4, 64) { 0L }
        val start = CountDownLatch(1)
        val accepted = AtomicInteger()
        val rejected = AtomicInteger()
        Executors.newFixedThreadPool(20).use { executor ->
            val futures = (1..20).map {
                executor.submit {
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    try { admission.execute { accepted.incrementAndGet() } }
                    catch (failure: RouteSearchRejected) {
                        assertEquals(RouteSearchRejectionReason.RATE_LIMITED, failure.reason)
                        rejected.incrementAndGet()
                    }
                }
            }
            start.countDown()
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
        }
        assertEquals(4, accepted.get())
        assertEquals(16, rejected.get())
    }

    @Test
    fun `invalid admission limits fail before requests can be served`() {
        for (value in listOf(0, 1001)) {
            assertFailsWith<IllegalArgumentException> { RouteSearchAdmission(requestsPerSecond = value) }
            assertFailsWith<IllegalArgumentException> { RouteSearchAdmission(burstCapacity = value) }
        }
        for (value in listOf(0, 65)) {
            assertFailsWith<IllegalArgumentException> { RouteSearchAdmission(maxConcurrentSearches = value) }
        }
    }

    private fun assertLimited(admission: RouteSearchAdmission) {
        val failure = assertFailsWith<RouteSearchRejected> { admission.execute { fail("Rate-limited work must not start") } }
        assertEquals(RouteSearchRejectionReason.RATE_LIMITED, failure.reason)
        assertEquals(1, failure.retryAfterSeconds)
    }
}
