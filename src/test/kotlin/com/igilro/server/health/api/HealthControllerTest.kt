package com.igilro.server.health.api

import com.igilro.server.health.application.CheckHealthUseCase
import com.igilro.server.health.domain.HealthRepository
import com.igilro.server.shared.api.RequestIdFilter
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class HealthControllerTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-10T03:00:00Z"), ZoneOffset.UTC)

    private fun mvc(ready: Boolean) = MockMvcBuilders
        .standaloneSetup(HealthController(CheckHealthUseCase(HealthRepository { ready }, clock)))
        .addFilters<org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder>(RequestIdFilter())
        .build()

    @Test
    fun `health returns 200 and a UTC timestamp`() {
        mvc(true).perform(get("/api/v1/health"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.checkedAt").value("2026-10-10T03:00:00Z"))
            .andExpect(header().exists(RequestIdFilter.HEADER))
    }

    @Test
    fun `unavailable database returns 503 with the same health contract`() {
        mvc(false).perform(get("/api/v1/health"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.status").value("DOWN"))
    }
}
