package com.igilro.server.route.infrastructure.data

import com.igilro.server.route.application.ImportWalkingGraphUseCase
import com.igilro.server.route.domain.*
import com.igilro.server.route.infrastructure.osm.OsmGraphReader
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.mock.env.MockEnvironment
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class RouteDataImportRunnerTest {
    @TempDir lateinit var directory: Path

    private fun dataset(): PreparedRouteData {
        val file = directory.resolve("walking.osm")
        javaClass.getResourceAsStream("/osm/walking-network.osm")!!.use { Files.copy(it, file) }
        val bounds = Bounds(127.02, 37.53, 127.05, 37.56)
        val graph = OsmGraphReader().read(file, "fixture", "Fixture", bounds, "https://example.com/public.osm")
        return PreparedRouteData(directory, RouteDataManifest(emptyMap(),
            RouteDataGraph(graph.areaId, graph.areaName, bounds, graph.source, graph.dataVersion)))
    }

    @Test fun `nonweb initializer parses verified walking data with the guarded import policy`() {
        val data = dataset()
        var saved: GraphImport? = null
        val importer = ImportWalkingGraphUseCase { graph, policy ->
            assertEquals(GraphImportPolicy.INITIALIZE_OR_REUSE, policy)
            saved = graph
            GraphImportSummary(graph.dataVersion, graph.nodes.size, graph.edges.size, false)
        }
        RouteDataImportRunner(RouteDataFiles(data), importer,
            MockEnvironment().withProperty("spring.main.web-application-type", "none"))
            .run(DefaultApplicationArguments())
        assertEquals(data.manifest.graph.expectedDataVersion, saved?.dataVersion)
        assertTrue(saved!!.edges.isNotEmpty())
    }

    @Test fun `web mode conflicting import flags missing data and wrong graph version never persist`() {
        val data = dataset()
        val importer = ImportWalkingGraphUseCase { _, _ -> fail("Invalid initialization must not persist") }
        val nonweb = MockEnvironment().withProperty("spring.main.web-application-type", "none")
        val wrong = data.copy(manifest = data.manifest.copy(graph = data.manifest.graph.copy(expectedDataVersion = "osm-v1-" + "0".repeat(64))))
        listOf(RouteDataFiles(data) to MockEnvironment(),
            RouteDataFiles(data) to MockEnvironment().withProperty("spring.main.web-application-type", "none").withProperty("igilro.graph-import.file", "other.osm"),
            RouteDataFiles() to nonweb, RouteDataFiles(wrong) to nonweb).forEach { (files, env) ->
            assertFailsWith<IllegalArgumentException> { RouteDataImportRunner(files, importer, env).run(DefaultApplicationArguments()) }
        }
    }
}
