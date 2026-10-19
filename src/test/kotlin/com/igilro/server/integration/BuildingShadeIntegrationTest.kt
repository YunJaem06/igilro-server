package com.igilro.server.integration

import com.igilro.server.route.application.ImportBuildingShadeUseCase
import com.igilro.server.route.application.RouteShadeEvaluator
import com.igilro.server.route.domain.Coordinate
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
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

@Tag("integration")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BuildingShadeIntegrationTest {
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var importer: ImportBuildingShadeUseCase
    @Autowired private lateinit var evaluator: RouteShadeEvaluator
    @Autowired private lateinit var mvc: MockMvc
    @TempDir lateinit var directory: Path
    private lateinit var dataset: BuildingShadeImport
    private val route = listOf(Coordinate(127.03, 37.54), Coordinate(127.032, 37.54))
    private val noon = Instant.parse("2026-06-21T03:00:00Z")

    @BeforeEach
    fun prepare() {
        jdbc.execute("DELETE FROM building_shade_area")
        jdbc.execute("DELETE FROM building_shade_footprint")
        jdbc.execute("DELETE FROM building_shade_dataset")
        val file = directory.resolve("buildings.geojson")
        javaClass.getResourceAsStream("/shade/buildings.geojson")!!.use { Files.copy(it, file) }
        dataset = GeoJsonBuildingReader().read(file)
    }

    private fun evaluate(time: Instant = noon) = evaluator.evaluate(dataset.areaId, route, time)

    @Test
    fun `overlapping building shadows are counted once and dataset replacement is immediately visible`() {
        importer.execute(dataset)
        val first = evaluate()
        assertEquals(ShadeStatus.AVAILABLE, first.status)
        assertEquals(1.0, first.coverage)
        assertTrue(first.ratio!! in 0.1..0.5)
        assertEquals(dataset.dataVersion, first.dataVersion)
        val second = dataset.copy(dataVersion = "buildings-v1-" + "b".repeat(64), checksum = "b".repeat(64),
            buildings = dataset.buildings + dataset.buildings.single().copy(id = "duplicate-footprint"))
        importer.execute(second)
        assertEquals(first.ratio, evaluate().ratio)
        assertEquals(second.dataVersion, evaluate().dataVersion)
    }

    @Test
    fun `unknown height and incomplete inventories do not return a fabricated shade ratio`() {
        importer.execute(dataset.copy(buildings = dataset.buildings.map { it.copy(heightMeters = null) }))
        val unknown = evaluate()
        assertEquals(ShadeStatus.INCOMPLETE_COVERAGE, unknown.status)
        assertNull(unknown.ratio)
        assertTrue(unknown.coverage!! in 0.1..0.99)
        val incomplete = dataset.copy(dataVersion = "buildings-v1-" + "c".repeat(64), checksum = "c".repeat(64), inventoryComplete = false)
        importer.execute(incomplete)
        assertEquals(0.0, evaluate().coverage)
        assertNull(evaluate().ratio)
    }

    @Test
    fun `coverage edge accounts for shadows of buildings beyond the inventory boundary`() {
        importer.execute(dataset)
        val result = evaluator.evaluate(dataset.areaId, listOf(Coordinate(127.02001, 37.54), Coordinate(127.021, 37.54)), noon)
        assertEquals(ShadeStatus.INCOMPLETE_COVERAGE, result.status)
        assertNull(result.ratio)
        assertTrue(result.coverage!! < 1.0)
    }

    @Test
    fun `no dataset night low sun and unsupported times have distinct states`() {
        assertEquals(ShadeStatus.DATA_UNAVAILABLE, evaluate().status)
        assertEquals(ShadeStatus.NIGHT, evaluate(Instant.parse("2026-06-21T15:00:00Z")).status)
        assertEquals(ShadeStatus.LOW_SUN, evaluate(Instant.parse("2026-03-20T21:45:00Z")).status)
        assertEquals(ShadeStatus.UNSUPPORTED_TIME, evaluate(Instant.parse("2200-01-01T03:00:00Z")).status)
    }

    @Test
    fun `a complete empty inventory can report measured zero shade`() {
        importer.execute(dataset.copy(buildings = emptyList()))
        assertEquals(ShadeStatus.AVAILABLE, evaluate().status)
        assertEquals(0.0, evaluate().ratio)
    }

    @Test
    fun `corrupt metadata and a spatial query failure degrade only shade and recover`() {
        importer.execute(dataset)
        jdbc.update("UPDATE building_shade_dataset SET maximum_height_meters=10 WHERE id=?", dataset.dataVersion)
        assertEquals(ShadeStatus.DATA_UNAVAILABLE, evaluate().status)
        jdbc.update("UPDATE building_shade_dataset SET maximum_height_meters=20 WHERE id=?", dataset.dataVersion)
        jdbc.execute("ALTER FUNCTION igilro_building_shadow(geometry,double precision,double precision) RENAME TO disabled_shadow")
        try {
            val unavailable = evaluate()
            assertEquals(ShadeStatus.DATA_UNAVAILABLE, unavailable.status)
            assertNull(unavailable.ratio)
        } finally {
            jdbc.execute("ALTER FUNCTION disabled_shadow(geometry,double precision,double precision) RENAME TO igilro_building_shadow")
        }
        assertEquals(ShadeStatus.AVAILABLE, evaluate().status)
    }

    @Test
    fun `building import is idempotent and rejects conflicting reused contents`() {
        assertFalse(importer.execute(dataset).reused)
        assertTrue(importer.execute(dataset).reused)
        assertFailsWith<IllegalArgumentException> { importer.execute(dataset.copy(buildings = dataset.buildings.map { it.copy(heightMeters = 10.0) })) }
        assertEquals(dataset.dataVersion, evaluate().dataVersion)
    }

    @Test
    fun `invalid polygon rolls back without replacing the active dataset`() {
        importer.execute(dataset)
        val bowtie = FootprintPolygon(listOf(listOf(Coordinate(127.03,37.54), Coordinate(127.031,37.541),
            Coordinate(127.03,37.541), Coordinate(127.031,37.54), Coordinate(127.03,37.54))))
        val invalid = dataset.copy(dataVersion = "buildings-v1-" + "d".repeat(64), checksum = "d".repeat(64),
            buildings = listOf(BuildingFootprint("invalid", listOf(bowtie), 10.0)))
        assertFails { importer.execute(invalid) }
        assertEquals(dataset.dataVersion, evaluate().dataVersion)
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM building_shade_dataset WHERE id=?", Int::class.java, invalid.dataVersion))
    }

    @Test
    fun `swept polygons preserve concavities courtyards and zero-offset footprints`() {
        val concave = "POLYGON((0 0,4 0,4 1,1 1,1 4,0 4,0 0))"
        val courtyard = "POLYGON((0 0,10 0,10 10,0 10,0 0),(2 2,2 8,8 8,8 2,2 2))"
        fun area(wkt: String, dx: Double) = jdbc.queryForObject("SELECT ST_Area(igilro_building_shadow(ST_GeomFromText(?,5179),?,0))", Double::class.java, wkt, dx)!!
        assertEquals(11.0, area(concave, 1.0), 1e-8)
        assertEquals(80.0, area(courtyard, 1.0), 1e-8)
        assertEquals(64.0, area(courtyard, 0.0), 1e-8)
    }

    @Test
    fun `HTTP response maps calculated shade and retains graph metadata`() {
        importer.execute(dataset)
        jdbc.update("""INSERT INTO route_dataset(id,area_id,source,source_timestamp,checksum,node_count,edge_count)
            VALUES ('shade-graph',?,'fixture',now(),?,2,1)""".trimIndent(), dataset.areaId, "a".repeat(64))
        jdbc.update("""INSERT INTO route_service_area(id,name,west,south,east,north,boundary,active_dataset_id)
            SELECT ?, 'Shade fixture',127.02,37.53,127.05,37.56,ST_Envelope(coverage),'shade-graph' FROM building_shade_dataset WHERE id=?""".trimIndent(), dataset.areaId, dataset.dataVersion)
        route.forEachIndexed { index, p -> jdbc.update("INSERT INTO route_node(dataset_id,osm_id,location) VALUES ('shade-graph',?,ST_SetSRID(ST_MakePoint(?,?),4326))", index + 1, p.longitude, p.latitude) }
        jdbc.execute("""INSERT INTO route_edge(dataset_id,id,osm_way_id,from_node,to_node,kind,forward_allowed,backward_allowed,geometry,distance_meters)
            SELECT 'shade-graph',1,1,1,2,'WALKWAY',true,true,ST_MakeLine(a.location,b.location),ST_Length(ST_MakeLine(a.location,b.location)::geography)
            FROM route_node a,route_node b WHERE a.dataset_id='shade-graph' AND b.dataset_id='shade-graph' AND a.osm_id=1 AND b.osm_id=2""".trimIndent())
        mvc.perform(post("/api/v1/routes/search").contentType(MediaType.APPLICATION_JSON).content("""
            {"origin":{"longitude":127.03,"latitude":37.54},"destination":{"longitude":127.032,"latitude":37.54},"departureAt":"2026-06-21T12:00:00+09:00"}
        """.trimIndent())).andExpect(status().isOk)
            .andExpect(jsonPath("$.routes[0].metrics.shadeRatio").value(evaluate().ratio))
            .andExpect(jsonPath("$.routes[0].shadeEvaluation.status").value("AVAILABLE"))
            .andExpect(jsonPath("$.routes[0].shadeEvaluation.coverage").value(1.0))
            .andExpect(jsonPath("$.routes[0].shadeEvaluation.dataVersion").value(dataset.dataVersion))
            .andExpect(jsonPath("$.routes[0].metrics.quietScore").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.routes[0].additionalDurationSeconds").value(0))
            .andExpect(jsonPath("$.dataVersion").value("shade-graph"))
            .andExpect(jsonPath("$.evaluatedAt").value("2026-06-21T03:00:00Z"))
    }

    companion object {
        @Container @JvmField
        val postgres = PostgreSQLContainer(DockerImageName.parse(System.getenv("POSTGIS_TEST_IMAGE") ?: "igilro-postgis:local").asCompatibleSubstituteFor("postgres"))
        @DynamicPropertySource @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
