package com.igilro.server.integration

import com.igilro.server.route.application.ImportWalkingGraphUseCase
import com.igilro.server.route.domain.*
import com.igilro.server.route.infrastructure.osm.OsmGraphReader
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.testcontainers.junit.jupiter.*
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*

@Tag("integration")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RouteIntegrationTest {
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var importer: ImportWalkingGraphUseCase
    @Autowired private lateinit var repository: WalkingGraphRepository
    @Autowired private lateinit var mvc: MockMvc
    @TempDir lateinit var directory: Path
    private lateinit var graph: GraphImport
    private val mapper = JsonMapper.builder().build()

    @BeforeEach
    fun prepare() {
        jdbc.execute("DELETE FROM route_service_area")
        val file = directory.resolve("fixture.osm")
        javaClass.getResourceAsStream("/osm/walking-network.osm")!!.use { Files.copy(it, file) }
        graph = OsmGraphReader().read(file, "test", "Test", Bounds(127.02,37.53,127.05,37.56), "synthetic-fixture")
        importer.execute(graph)
    }

    private fun query(origin: String = "127.0302,37.54002", destination: String = "127.0318,37.54002", extra: String = ""): String {
        val a = origin.split(',')
        val b = destination.split(',')
        return """{"origin":{"longitude":${a[0]},"latitude":${a[1]}},"destination":{"longitude":${b[0]},"latitude":${b[1]}}$extra}"""
    }
    private fun request(body: String) = post("/api/v1/routes/search").contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON).content(body)

    @Test
    fun `place search without a provider key does not disable coordinate based routes`() {
        mvc.perform(get("/api/v1/places/search").param("query", "서울숲"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("PLACE_SEARCH_NOT_CONFIGURED"))
            .andExpect(jsonPath("$.requestId").isString)
        mvc.perform(request(query())).andExpect(status().isOk)
        mvc.perform(request(query(origin = "126.97,37.57")))
            .andExpect(status().`is`(422))
            .andExpect(jsonPath("$.code").value("OUTSIDE_SUPPORTED_AREA"))
    }

    @Test
    fun `real DB route uses mid-edge geometry metric units UTC and null environmental scores`() {
        val response = mvc.perform(request(query(extra = ",\"departureAt\":\"2026-10-10T14:00:00+09:00\"")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.evaluatedAt").value("2026-10-10T05:00:00Z"))
            .andExpect(jsonPath("$.dataVersion").value(graph.dataVersion))
            .andExpect(jsonPath("$.routes[0].geometry.type").value("LineString"))
            .andExpect(jsonPath("$.routes[0].additionalDurationSeconds").value(0))
            .andExpect(jsonPath("$.routes[0].metrics.shadeRatio").value(org.hamcrest.Matchers.nullValue()))
            .andReturn().response.contentAsString
        val tree = mapper.readTree(response)
        val route = tree["routes"][0]
        val first = route["geometry"]["coordinates"][0]
        assertEquals(127.0302, first[0].asDouble(), 1e-8)
        assertEquals(37.54, first[1].asDouble(), 1e-8)
        assertTrue(route["distanceMeters"].asDouble() in 140.0..145.0)
        assertTrue(route["durationSeconds"].asLong() in 100..105)
        assertTrue(tree["snapping"]["origin"]["distanceMeters"].asDouble() in 2.0..2.5)
        assertEquals(route["durationSeconds"].asLong(), tree["baselineDurationSeconds"].asLong())
        val measured = jdbc.queryForObject("SELECT ST_Length(ST_GeomFromGeoJSON(?)::geography)", Double::class.java, route["geometry"].toString())!!
        assertEquals(measured, route["distanceMeters"].asDouble(), 0.11)
    }

    @Test
    fun `business errors retain top-level code requestId and HTTP status`() {
        val id = UUID.randomUUID().toString()
        mvc.perform(request(query(destination = "127.051,37.54")).header("X-Request-Id", id))
            .andExpect(status().`is`(422))
            .andExpect(jsonPath("$.code").value("OUTSIDE_SUPPORTED_AREA"))
            .andExpect(jsonPath("$.requestId").value(id))
            .andExpect(header().string("X-Request-Id", id))
        mvc.perform(request(query(destination = "127.034,37.543")))
            .andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("ROUTE_NOT_FOUND"))
        mvc.perform(request(query(extra = ",\"mode\":\"shade\"")))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("INVALID_ROUTE_QUERY"))
        jdbc.execute("DELETE FROM route_service_area")
        mvc.perform(request(query())).andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("ROUTING_DATA_UNAVAILABLE"))
    }

    @Test
    fun `malformed requests fail validation before graph search`() {
        listOf(query(origin = "127.03,91"), query(extra = ",\"departureAt\":\"2026-10-10T14:00:00\""), "{}", "{bad-json", query(origin = "\"NaN\",37.54"), query(extra = ",\"conditions\":{\"shade\":1}")).forEach { body ->
            mvc.perform(request(body)).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        }
    }

    @Test
    fun `import is idempotent and invalid replacement does not remove active data`() {
        val before = jdbc.queryForObject("SELECT count(*) FROM route_node WHERE dataset_id=?", Int::class.java, graph.dataVersion)
        assertTrue(importer.execute(graph).reused)
        assertEquals(before, jdbc.queryForObject("SELECT count(*) FROM route_node WHERE dataset_id=?", Int::class.java, graph.dataVersion))
        assertFailsWith<IllegalArgumentException> { importer.execute(graph.copy(edges = emptyList())) }
        assertEquals(graph.dataVersion, repository.findActiveGraphs().single().dataVersion)
    }

    @Test
    fun `reusing a version with different snapshot contents fails without replacing active data`() {
        val changedNode = graph.nodes.entries.first()
        val changedEdge = graph.edges.first()
        val conflicts = listOf(
            graph.copy(areaId = "other"),
            graph.copy(checksum = "f".repeat(64)),
            graph.copy(source = "other-source"),
            graph.copy(sourceTimestamp = graph.sourceTimestamp.plusSeconds(1)),
            graph.copy(nodes = graph.nodes + (changedNode.key to changedNode.value.copy(latitude = changedNode.value.latitude + 0.00001))),
            graph.copy(edges = graph.edges.map { if (it == changedEdge) it.copy(kind = EdgeKind.STEPS) else it }),
        )
        conflicts.forEach { conflicting ->
            assertFailsWith<IllegalArgumentException> { importer.execute(conflicting) }
            assertEquals(graph.dataVersion, repository.findActiveGraphs().single().dataVersion)
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM route_service_area", Int::class.java))
        }
        assertTrue(importer.execute(graph).reused)
    }

    @Test
    fun `cached graph follows service area metadata changes`() {
        repository.findActiveGraphs()
        val expanded = graph.bounds.copy(east = graph.bounds.east + 0.001)
        importer.execute(graph.copy(bounds = expanded))
        assertEquals(expanded, repository.findActiveGraphs().single().bounds)
    }

    @Test
    fun `invalid persisted graph data returns routing unavailable and can recover`() {
        val version = "osm-v1-" + "c".repeat(64)
        importer.execute(graph.copy(dataVersion = version))
        val node = graph.nodes.entries.first()
        jdbc.update("UPDATE route_node SET location=ST_SetSRID(ST_MakePoint(?,?),4326) WHERE dataset_id=? AND osm_id=?",
            127.5, 37.54, version, node.key)
        val id = UUID.randomUUID().toString()
        mvc.perform(request(query()).header("X-Request-Id", id)).andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("ROUTING_DATA_UNAVAILABLE"))
            .andExpect(jsonPath("$.requestId").value(id))
            .andExpect(header().string("X-Request-Id", id))
        jdbc.update("UPDATE route_node SET location=ST_SetSRID(ST_MakePoint(?,?),4326) WHERE dataset_id=? AND osm_id=?",
            node.value.longitude, node.value.latitude, version, node.key)
        mvc.perform(request(query())).andExpect(status().isOk)
    }

    @Test
    fun `SQL failure rolls back a new dataset and leaves the prior active graph usable`() {
        val replacement = graph.copy(dataVersion = "osm-v1-" + "a".repeat(64), edges = graph.edges.mapIndexed { index, edge -> if (index == 0) edge.copy(kind = EdgeKind.WALKWAY, wayId = 0) else edge })
        // Bypass the application validation to exercise the database transaction itself.
        val writer = org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.dataSource!!)
        val direct = com.igilro.server.route.infrastructure.JdbcGraphImportRepository(jdbc, writer,
            com.igilro.server.route.infrastructure.JdbcGuidanceMetadataRepository(jdbc))
        // A negative node ID violates the SQL CHECK after the dataset insert has executed.
        val invalid = replacement.copy(nodes = replacement.nodes + (-10L to Coordinate(127.03,37.54)))
        assertFails { direct.save(invalid) }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM route_dataset WHERE id=?", Int::class.java, replacement.dataVersion))
        assertEquals(graph.dataVersion, repository.findActiveGraphs().single().dataVersion)
    }

    @Test
    fun `guidance metadata enriches legacy snapshots once and invalidates only the metadata cache key`() {
        val version="osm-v1-"+"d".repeat(64)
        val legacy=graph.copy(dataVersion=version,nodeGuidance=emptyMap(),edgeGuidance=emptyMap(),guidanceMetadataVersion=null)
        importer.execute(legacy)
        val before=repository.findActiveGraphs().single()
        assertNull(before.guidanceMetadataVersion)
        val enriched=graph.copy(dataVersion=version)
        assertTrue(importer.execute(enriched).reused)
        val after=repository.findActiveGraphs().single()
        assertEquals(before.dataVersion,after.dataVersion);assertEquals(before.nodes,after.nodes);assertEquals(before.edges,after.edges)
        assertEquals(enriched.edgeGuidance,after.edgeGuidance);assertEquals(GuidanceMetadataVersion.OSM_V1,after.guidanceMetadataVersion)
        assertTrue(importer.execute(enriched).reused)
        val first=enriched.edgeGuidance.entries.first()
        assertFailsWith<IllegalArgumentException> { importer.execute(enriched.copy(edgeGuidance=enriched.edgeGuidance+
            (first.key to first.value.copy(streetName="changed source")))) }
        assertEquals(enriched.edgeGuidance,repository.findActiveGraphs().single().edgeGuidance)
    }

    @Test
    fun `failed metadata enrichment rolls back all auxiliary rows and retains usable legacy routing`() {
        val version="osm-v1-"+"e".repeat(64)
        importer.execute(graph.copy(dataVersion=version,nodeGuidance=emptyMap(),edgeGuidance=emptyMap(),guidanceMetadataVersion=null))
        val writer=org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.dataSource!!)
        val direct=com.igilro.server.route.infrastructure.JdbcGraphImportRepository(jdbc,writer,
            com.igilro.server.route.infrastructure.JdbcGuidanceMetadataRepository(jdbc))
        val invalid=graph.copy(dataVersion=version,nodeGuidance=mapOf(2L to NodeGuidanceMetadata(CrossingMetadata(true))),
            edgeGuidance=graph.edgeGuidance+(999L to EdgeGuidanceMetadata(999)))
        assertFails { direct.save(invalid) }
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM route_node_guidance WHERE dataset_id=?",Int::class.java,version))
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM route_guidance_dataset WHERE dataset_id=?",Int::class.java,version))
        assertEquals(version,repository.findActiveGraphs().single().dataVersion)
        mvc.perform(request(query())).andExpect(status().isOk)
            .andExpect(jsonPath("$.routes[0].guidance.metadataStatus").value("TOPOLOGY_ONLY"))
    }

    @Test
    fun `source names and explicit crossing signals survive DB roundtrip and appear as additive API guidance`() {
        val file=directory.resolve("named-crossing.osm")
        val original=Files.readString(directory.resolve("fixture.osm"))
        Files.writeString(file,original.replace("<node id=\"2\" lon=\"127.031\" lat=\"37.540\"/>",
            """<node id="2" lon="127.031" lat="37.540"><tag k="highway" v="crossing"/><tag k="crossing:signals" v="yes"/></node>""")
            .replaceFirst("<tag k=\"highway\" v=\"footway\"/>","""<tag k="highway" v="footway"/><tag k="name:ko" v="서울숲길"/>"""))
        val named=OsmGraphReader().read(file,"test","Test",graph.bounds,"synthetic-fixture")
        importer.execute(named)
        assertEquals(named.nodeGuidance,repository.findActiveGraphs().single().nodeGuidance)
        assertEquals(named.edgeGuidance,repository.findActiveGraphs().single().edgeGuidance)
        mvc.perform(request(query())).andExpect(status().isOk)
            .andExpect(jsonPath("$.routes[0].guidance.status").value("AVAILABLE"))
            .andExpect(jsonPath("$.routes[0].guidance.steps[0].type").value("DEPART"))
            .andExpect(jsonPath("$.routes[0].guidance.steps[1].type").value("CROSSING"))
            .andExpect(jsonPath("$.routes[0].guidance.steps[1].crossing.trafficSignals").value(true))
            .andExpect(jsonPath("$.routes[0].guidance.steps[1].streetName").value("서울숲길"))
            .andExpect(jsonPath("$.routes[0].geometry.type").value("LineString"))
            .andExpect(jsonPath("$.routes[0].metrics.shadeRatio").value(org.hamcrest.Matchers.nullValue()))
    }

    @Test
    fun `activation of another immutable snapshot refreshes cached graph`() {
        val second = graph.copy(dataVersion = "osm-v1-" + "b".repeat(64))
        repository.findActiveGraphs()
        importer.execute(second)
        assertEquals(second.dataVersion, repository.findActiveGraphs().single().dataVersion)
    }

    @Test
    fun `OpenAPI describes the route endpoint and business errors`() {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk)
            .andExpect(jsonPath("$.paths['/api/v1/routes/search'].post.operationId").value("searchWalkingRoute"))
            .andExpect(jsonPath("$.paths['/api/v1/routes/search'].post.responses['422']").exists())
            .andExpect(jsonPath("$.paths['/api/v1/routes/search'].post.responses['503']").exists())
            .andExpect(jsonPath("$.components.schemas.RouteSearchRequest.required").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("mode"))))
            .andExpect(jsonPath("$.components.schemas.RouteSearchResponse.required").value(org.hamcrest.Matchers.hasItems("routes", "evaluatedAt", "dataVersion")))
    }

    companion object {
        @Container @JvmField
        val postgres = PostgreSQLContainer(DockerImageName.parse(System.getenv("POSTGIS_TEST_IMAGE") ?: "igilro-postgis:local").asCompatibleSubstituteFor("postgres"))
        @DynamicPropertySource @JvmStatic
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
