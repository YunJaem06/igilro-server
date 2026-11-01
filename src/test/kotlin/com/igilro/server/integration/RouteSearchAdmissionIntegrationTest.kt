package com.igilro.server.integration

import com.igilro.server.route.application.ImportWalkingGraphUseCase
import com.igilro.server.route.application.RouteSearchAdmission
import com.igilro.server.route.domain.Bounds
import com.igilro.server.route.infrastructure.RouteSearchProperties
import com.igilro.server.route.infrastructure.osm.OsmGraphReader
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Tag("integration")
@Testcontainers
@SpringBootTest(properties = [
    "igilro.route-search.requests-per-second=1000",
    "igilro.route-search.burst-capacity=1000",
    "igilro.route-search.max-concurrent-searches=1",
])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RouteSearchAdmissionIntegrationTest {
    @Autowired private lateinit var importer: ImportWalkingGraphUseCase
    @Autowired private lateinit var properties: RouteSearchProperties
    @Autowired private lateinit var admission: RouteSearchAdmission
    @Autowired private lateinit var mvc: MockMvc
    @TempDir lateinit var folder: Path

    @Test
    fun `bound limits protect both real Spring controllers while health and OpenAPI remain available`() {
        assertEquals(1000, properties.requestsPerSecond)
        assertEquals(1000, properties.burstCapacity)
        assertEquals(1, properties.maxConcurrentSearches)
        val file = folder.resolve("fixture.osm")
        javaClass.getResourceAsStream("/osm/walking-network.osm")!!.use { Files.copy(it,file) }
        importer.execute(OsmGraphReader().read(file,"fixture","Fixture",Bounds(127.02,37.53,127.05,37.56),"synthetic fixture"))
        val body = """{"origin":{"longitude":127.0302,"latitude":37.54002},"destination":{"longitude":127.0318,"latitude":37.54002}}"""
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        Executors.newSingleThreadExecutor().use { executor ->
            val running = executor.submit {
                admission.execute { entered.countDown(); assertTrue(release.await(15,TimeUnit.SECONDS)) }
            }
            try {
                assertTrue(entered.await(5,TimeUnit.SECONDS))
                for (path in listOf("/api/v1/routes/search","/api/v1/routes/predictions/search")) {
                    val id = UUID.randomUUID().toString()
                    mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).header("X-Request-Id",id).content(body))
                        .andExpect(status().isServiceUnavailable)
                        .andExpect(header().string("Retry-After","1"))
                        .andExpect(header().string("X-Request-Id",id))
                        .andExpect(jsonPath("$.code").value("ROUTE_SEARCH_BUSY"))
                        .andExpect(jsonPath("$.requestId").value(id))
                }
                mvc.perform(get("/api/v1/health")).andExpect(status().isOk)
                mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk)
                mvc.perform(get("/api/v1/routes/predictions/coverage"))
                    .andExpect(status().isServiceUnavailable)
                    .andExpect(jsonPath("$.code").value("PREDICTION_DATA_UNAVAILABLE"))
                mvc.perform(get("/v3/api-docs")).andExpect(status().isOk)
                    .andExpect(jsonPath("$.paths['/api/v1/routes/search'].post.responses['429']").exists())
                    .andExpect(jsonPath("$.paths['/api/v1/routes/predictions/search'].post.responses['429']").exists())
            } finally { release.countDown() }
            running.get(5,TimeUnit.SECONDS)
        }
        mvc.perform(post("/api/v1/routes/search").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk)
    }

    companion object {
        @Container @JvmField
        val postgres = PostgreSQLContainer(DockerImageName.parse(System.getenv("POSTGIS_TEST_IMAGE") ?: "igilro-postgis:local").asCompatibleSubstituteFor("postgres"))
        @DynamicPropertySource @JvmStatic
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url",postgres::getJdbcUrl)
            registry.add("spring.datasource.username",postgres::getUsername)
            registry.add("spring.datasource.password",postgres::getPassword)
        }
    }
}
