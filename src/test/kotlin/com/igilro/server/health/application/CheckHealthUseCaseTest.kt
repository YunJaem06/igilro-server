package com.igilro.server.health.application

import com.igilro.server.health.domain.HealthRepository
import com.igilro.server.health.domain.HealthStatus
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals

class CheckHealthUseCaseTest {
    private val checkedAt = Instant.parse("2026-10-10T03:00:00Z")
    private val clock = Clock.fixed(checkedAt, ZoneOffset.UTC)

    @Test
    fun `usable spatial database returns UP at the injected time`() {
        val health = CheckHealthUseCase(HealthRepository { true }, clock).execute()
        assertEquals(HealthStatus.UP, health.status)
        assertEquals(checkedAt, health.checkedAt)
    }

    @Test
    fun `unusable spatial database returns DOWN`() {
        val health = CheckHealthUseCase(HealthRepository { false }, clock).execute()
        assertEquals(HealthStatus.DOWN, health.status)
        assertEquals(checkedAt, health.checkedAt)
    }
}
