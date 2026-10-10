package com.igilro.server.integration

import org.flywaydb.core.Flyway
import org.hamcrest.Matchers.hasItems
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Tag("integration")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ServerIntegrationTest {
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var flyway: Flyway
    @Autowired private lateinit var mvc: MockMvc

    @Test
    fun `Flyway enables PostGIS and is safe to run again`() {
        assertEquals("1", flyway.info().current().version.version)
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM pg_extension WHERE extname = 'postgis'", Int::class.java))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Int::class.java))
        flyway.validate()
        assertEquals(0, flyway.migrate().migrationsExecuted)
    }

    @Test
    fun `spatial functions preserve longitude latitude and calculate metres`() {
        val point = "ST_SetSRID(ST_MakePoint(126.9780, 37.5665), 4326)"
        assertEquals(126.9780, jdbc.queryForObject("SELECT ST_X($point)", Double::class.java))
        assertEquals(37.5665, jdbc.queryForObject("SELECT ST_Y($point)", Double::class.java))
        assertEquals(4326, jdbc.queryForObject("SELECT ST_SRID($point)", Int::class.java))
        val length = jdbc.queryForObject(
            "SELECT ST_Distance($point::geography, ST_SetSRID(ST_MakePoint(126.9790, 37.5665), 4326)::geography)",
            Double::class.java,
        )!!
        assertTrue(length in 80.0..100.0)
    }

    @Test
    fun `application wiring and readiness include the spatial database`() {
        mvc.perform(get("/api/v1/health")).andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.checkedAt").isString)
        mvc.perform(get("/actuator/health")).andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("UP"))
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.components").doesNotExist())
    }

    @Test
    fun `missing spatial extension makes readiness DOWN while liveness stays UP`() {
        jdbc.execute("DROP EXTENSION postgis")
        try {
            mvc.perform(get("/api/v1/health")).andExpect(status().isServiceUnavailable)
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.requestId").isString)
            mvc.perform(get("/actuator/health")).andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.status").value("DOWN"))
            mvc.perform(get("/actuator/health/readiness")).andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.status").value("DOWN"))
            mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk)
        } finally {
            jdbc.execute("CREATE EXTENSION postgis")
        }
    }

    @Test
    fun `OpenAPI describes health and its 503 response and serves Swagger UI`() {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk)
            .andExpect(jsonPath("$.paths['/api/v1/health'].get.operationId").value("checkHealth"))
            .andExpect(jsonPath("$.paths['/api/v1/health'].get.responses['503']").exists())
            .andExpect(jsonPath("$.components.schemas.HealthResponse.properties.checkedAt.format").value("date-time"))
            .andExpect(jsonPath("$.components.schemas.ApiErrorResponse.properties.requestId.format").value("uuid"))
            .andExpect(jsonPath("$.components.schemas.ApiErrorResponse.required").value(hasItems("code", "requestId")))
            .andExpect(jsonPath("$.paths['/api/v1/health'].get.responses['503'].content['application/json'].schema['\$ref']")
                .value("#/components/schemas/ApiErrorResponse"))
            .andExpect(jsonPath("$.paths['/api/v1/health'].get.responses['500'].content['application/json']").exists())
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith("text/html"))
    }

    @Test
    fun `unknown production resource uses the Flutter JSON error contract`() {
        mvc.perform(get("/api/v1/missing")).andExpect(status().isNotFound)
            .andExpect(content().contentTypeCompatibleWith("application/json"))
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
            .andExpect(jsonPath("$.requestId").isString)
    }

    companion object {
        @Container
        @JvmField
        val postgres = PostgreSQLContainer(
            DockerImageName.parse(System.getenv("POSTGIS_TEST_IMAGE") ?: "igilro-postgis:local")
                .asCompatibleSubstituteFor("postgres"),
        )

        @DynamicPropertySource
        @JvmStatic
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
