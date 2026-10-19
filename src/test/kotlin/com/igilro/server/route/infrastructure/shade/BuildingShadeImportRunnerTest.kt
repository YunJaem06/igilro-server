package com.igilro.server.route.infrastructure.shade

import com.igilro.server.route.application.ImportBuildingShadeUseCase
import com.igilro.server.route.domain.shade.BuildingShadeImportRepository
import com.igilro.server.route.domain.shade.BuildingShadeImportSummary
import org.junit.jupiter.api.Test
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.mock.env.MockEnvironment
import java.nio.file.Path
import kotlin.test.*

class BuildingShadeImportRunnerTest {
    @Test
    fun `environment based graph import conflicts are rejected before reading or saving buildings`() {
        val importer = ImportBuildingShadeUseCase(BuildingShadeImportRepository { error("Must not save on conflicting import configuration") })
        val runner = BuildingShadeImportRunner(importer, "not-a-file", MockEnvironment().withProperty("igilro.graph-import.file", "graph.osm"))
        val failure = assertFailsWith<IllegalArgumentException> { runner.run(DefaultApplicationArguments()) }
        assertEquals("Run graph and building imports separately", failure.message)
    }

    @Test
    fun `CLI reads provenance geometry and heights before invoking the import use case`() {
        var imported = false
        val importer = ImportBuildingShadeUseCase(BuildingShadeImportRepository { dataset ->
            imported = true
            assertEquals("shade-test", dataset.areaId)
            assertEquals(20.0, dataset.buildings.single().heightMeters)
            BuildingShadeImportSummary(dataset.dataVersion, 1, 1, false)
        })
        val file = Path.of(javaClass.getResource("/shade/buildings.geojson")!!.toURI())
        BuildingShadeImportRunner(importer, file.toString(), MockEnvironment()).run(DefaultApplicationArguments())
        assertTrue(imported)
    }
}
