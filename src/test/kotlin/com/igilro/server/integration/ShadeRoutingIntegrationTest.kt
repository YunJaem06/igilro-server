package com.igilro.server.integration

import com.igilro.server.route.application.*
import com.igilro.server.route.domain.*
import com.igilro.server.route.domain.shade.*
import com.igilro.server.route.infrastructure.shade.GeoJsonBuildingReader
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.testcontainers.junit.jupiter.*
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.math.ceil
import kotlin.test.*

/** Isolated synthetic ground-level network. Never imports fixture data into a development DB. */
@Tag("integration")
@Testcontainers
@SpringBootTest(properties = ["igilro.shade-routing.ground-verified-datasets=shade-routing-graph"])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ShadeRoutingIntegrationTest {
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var importer: ImportBuildingShadeUseCase
    @Autowired private lateinit var evaluator: RouteShadeEvaluator
    @Autowired private lateinit var batch: RouteShadeBatchEvaluator
    @Autowired private lateinit var mvc: MockMvc
    @TempDir lateinit var directory: Path
    private lateinit var dataset: BuildingShadeImport
    private val a=Coordinate(127.03,37.54)
    private val b=Coordinate(127.032,37.54)
    private val c=Coordinate(127.03,37.5401)
    private val d=Coordinate(127.032,37.5401)
    private val noon=Instant.parse("2026-06-21T03:00:00Z")
    private val mapper=JsonMapper.builder().build()

    @BeforeEach
    fun prepare() {
        jdbc.execute("DELETE FROM building_shade_area")
        jdbc.execute("DELETE FROM building_shade_footprint")
        jdbc.execute("DELETE FROM building_shade_dataset")
        jdbc.execute("DELETE FROM route_service_area")
        jdbc.execute("DELETE FROM route_edge")
        jdbc.execute("DELETE FROM route_node")
        jdbc.execute("DELETE FROM route_dataset")
        val file=directory.resolve("buildings.geojson")
        javaClass.getResourceAsStream("/shade/routing-buildings.geojson")!!.use { Files.copy(it,file) }
        dataset=GeoJsonBuildingReader().read(file)
        importer.execute(dataset)
        jdbc.update("""INSERT INTO route_dataset(id,area_id,source,source_timestamp,checksum,node_count,edge_count)
            VALUES ('shade-routing-graph',?,'synthetic-ground-fixture',now(),?,4,4)""",dataset.areaId,"a".repeat(64))
        jdbc.update("""INSERT INTO route_service_area(id,name,west,south,east,north,boundary,active_dataset_id)
            SELECT ?, 'Synthetic ground fixture',127.02,37.53,127.05,37.56,ST_Envelope(coverage),'shade-routing-graph'
            FROM building_shade_dataset WHERE id=?""",dataset.areaId,dataset.dataVersion)
        listOf(a,b,c,d).forEachIndexed { index,p -> jdbc.update(
            "INSERT INTO route_node(dataset_id,osm_id,location) VALUES ('shade-routing-graph',?,ST_SetSRID(ST_MakePoint(?,?),4326))",index+1,p.longitude,p.latitude) }
        listOf(1 to 2,1 to 3,3 to 4,4 to 2).forEachIndexed { index,(from,to) -> jdbc.update("""
            INSERT INTO route_edge(dataset_id,id,osm_way_id,from_node,to_node,kind,forward_allowed,backward_allowed,geometry,distance_meters)
            SELECT 'shade-routing-graph',?,1,?,?,'WALKWAY',true,true,ST_MakeLine(a.location,b.location),ST_Length(ST_MakeLine(a.location,b.location)::geography)
            FROM route_node a,route_node b WHERE a.dataset_id='shade-routing-graph' AND b.dataset_id='shade-routing-graph' AND a.osm_id=? AND b.osm_id=?
            """.trimIndent(),index+1,from,to,from,to) }
    }

    private fun request(mode: String="shade",departure: String="2026-06-21T12:00:00+09:00", id: String=UUID.randomUUID().toString()) =
        post("/api/v1/routes/search").contentType(MediaType.APPLICATION_JSON).header("X-Request-Id",id).content("""
            {"origin":{"longitude":127.03,"latitude":37.54},"destination":{"longitude":127.032,"latitude":37.54},"mode":"$mode","departureAt":"$departure"}
        """.trimIndent())

    @Test
    fun `actual PostGIS shadows choose a shaded detour and additional seconds use the real fastest baseline`() {
        val fast=mapper.readTree(mvc.perform(request("fastest")).andExpect(status().isOk).andReturn().response.contentAsString)
        val id=UUID.randomUUID().toString()
        val optimized=mapper.readTree(mvc.perform(request(id=id)).andExpect(status().isOk)
            .andExpect(header().string("X-Request-Id",id))
            .andExpect(jsonPath("$.routes[0].mode").value("shade"))
            .andExpect(jsonPath("$.routes[0].shadeEvaluation.status").value("AVAILABLE"))
            .andExpect(jsonPath("$.routes[0].shadeEvaluation.coverage").value(1.0))
            .andExpect(jsonPath("$.routes[0].shadeEvaluation.dataVersion").value(dataset.dataVersion))
            .andExpect(jsonPath("$.routingModelVersion").value(ShadeRouteOptimizer.VERSION))
            .andExpect(jsonPath("$.dataVersion").value("shade-routing-graph"))
            .andExpect(jsonPath("$.evaluatedAt").value(noon.toString()))
            .andReturn().response.contentAsString)
        val fastRoute=fast["routes"][0]
        val route=optimized["routes"][0]
        assertTrue(route["metrics"]["shadeRatio"].asDouble()>0.7)
        assertEquals(0.0,fastRoute["metrics"]["shadeRatio"].asDouble(),1e-9)
        assertTrue(route["distanceMeters"].asDouble()>fastRoute["distanceMeters"].asDouble())
        assertTrue(route["durationSeconds"].asLong()<=ceil(fastRoute["durationSeconds"].asLong()*1.3).toLong())
        assertEquals(fastRoute["durationSeconds"].asLong(),optimized["baselineDurationSeconds"].asLong())
        assertEquals(route["durationSeconds"].asLong()-fastRoute["durationSeconds"].asLong(),route["additionalDurationSeconds"].asLong())
        assertEquals(fast["snapping"],optimized["snapping"])
        val points=route["geometry"]["coordinates"]
        val coordinates=(0 until points.size()).map { Coordinate(points[it][0].asDouble(),points[it][1].asDouble()) }
        assertEquals(listOf(a,c,d,b),coordinates)
        // Aggregate of section measurements must equal the independently evaluated final LineString.
        assertEquals(batch.evaluate(dataset.areaId,listOf(coordinates),Coordinate(127.031,37.54),noon).single().evaluation.ratio!!,
            route["metrics"]["shadeRatio"].asDouble(),1e-8)
        val evidence=Path.of("build/verification/shade-routing")
        Files.createDirectories(evidence)
        Files.writeString(evidence.resolve("fastest-response.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(fast))
        Files.writeString(evidence.resolve("shade-response.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(optimized))
    }

    @Test
    fun `batch preserves duplicate traversals and independently measures partial edge shadows`() {
        val full=listOf(c,d)
        val middle=listOf(Coordinate(127.0305,37.5401),Coordinate(127.0315,37.5401))
        val paths=listOf(full,middle,full,listOf(c,d,c))
        val result=batch.evaluate(dataset.areaId,paths,Coordinate(127.031,37.5401),noon)
        assertEquals(4,result.size)
        assertTrue(result.all { it.evaluation.status==ShadeStatus.AVAILABLE && it.evaluation.dataVersion==dataset.dataVersion })
        assertEquals(result[0],result[2])
        assertEquals(result[0].lengthMeters!!*2,result[3].lengthMeters!!,1e-7)
        assertEquals(result[0].evaluation.ratio!!,result[3].evaluation.ratio!!,1e-8)
        assertTrue(result[1].evaluation.ratio!!>result[0].evaluation.ratio!!)
        assertEquals(1.0,result[1].evaluation.ratio!!,1e-8)
    }

    @Test
    fun `unknown alternative blocks optimization even when fastest route itself is fully evaluable`() {
        importer.execute(dataset.copy(dataVersion="buildings-v1-"+"b".repeat(64),checksum="b".repeat(64),
            buildings=dataset.buildings.map { it.copy(heightMeters=null,heightSource=BuildingHeightSource.UNKNOWN) }))
        assertEquals(ShadeStatus.AVAILABLE,evaluator.evaluate(dataset.areaId,listOf(a,b),noon).status)
        mvc.perform(request()).andExpect(status().isServiceUnavailable)
            .andExpect(content().contentTypeCompatibleWith("application/json"))
            .andExpect(jsonPath("$.code").value("SHADE_DATA_UNAVAILABLE"))
            .andExpect(jsonPath("$.requestId").isString)
        mvc.perform(request("fastest")).andExpect(status().isOk)
    }

    @Test
    fun `incomplete inventory remains unavailable and nights are distinguished from missing data`() {
        importer.execute(dataset.copy(dataVersion="buildings-v1-"+"c".repeat(64),checksum="c".repeat(64),inventoryComplete=false,maximumHeightMeters=null))
        val id=UUID.randomUUID().toString()
        mvc.perform(request(id=id)).andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("SHADE_DATA_UNAVAILABLE"))
            .andExpect(jsonPath("$.requestId").value(id))
        mvc.perform(request("fastest")).andExpect(status().isOk)
            .andExpect(jsonPath("$.routes[0].shadeEvaluation.status").value("INCOMPLETE_COVERAGE"))
            .andExpect(jsonPath("$.routes[0].metrics.shadeRatio").value(org.hamcrest.Matchers.nullValue()))
        for(time in listOf("2026-06-22T00:00:00+09:00","2026-03-21T06:45:00+09:00","2200-01-01T12:00:00+09:00")) {
            mvc.perform(request(departure=time)).andExpect(status().`is`(422))
                .andExpect(jsonPath("$.code").value("SHADE_TIME_UNSUPPORTED"))
        }
    }

    @Test
    fun `complete empty synthetic inventory returns fastest without a gratuitous detour`() {
        importer.execute(dataset.copy(dataVersion="buildings-v1-"+"d".repeat(64),checksum="d".repeat(64),buildings=emptyList()))
        mvc.perform(request()).andExpect(status().isOk)
            .andExpect(jsonPath("$.routes[0].metrics.shadeRatio").value(0.0))
            .andExpect(jsonPath("$.routes[0].additionalDurationSeconds").value(0))
            .andExpect(jsonPath("$.routes[0].geometry.coordinates.length()").value(2))
    }

    companion object {
        @Container @JvmField
        val postgres=PostgreSQLContainer(DockerImageName.parse(System.getenv("POSTGIS_TEST_IMAGE") ?: "igilro-postgis:local").asCompatibleSubstituteFor("postgres"))
        @DynamicPropertySource @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url",postgres::getJdbcUrl)
            registry.add("spring.datasource.username",postgres::getUsername)
            registry.add("spring.datasource.password",postgres::getPassword)
        }
    }
}
