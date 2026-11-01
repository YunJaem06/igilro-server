package com.igilro.server.route.infrastructure.data

import com.igilro.server.route.application.ImportWalkingGraphUseCase
import com.igilro.server.route.domain.GraphImportPolicy
import com.igilro.server.route.infrastructure.osm.OsmGraphReader
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(name = ["igilro.route-data.import-graph"], havingValue = "true")
class RouteDataImportRunner(
    private val files: RouteDataFiles,
    private val importer: ImportWalkingGraphUseCase,
    private val environment: Environment,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        require(environment.getProperty("spring.main.web-application-type") == "none") { "Route data import must be a non-web task" }
        require(!environment.containsProperty("igilro.graph-import.file") && !environment.containsProperty("igilro.building-import.file"))
        val data = requireNotNull(files.prepared) { "Route data import requires a pinned dataset" }
        val graph = data.manifest.graph
        val prepared = OsmGraphReader().read(data.directory.resolve("walking.osm"),graph.areaId,graph.areaName,graph.bounds,graph.source)
        require(prepared.dataVersion == graph.expectedDataVersion) { "Walking graph version differs from the release manifest" }
        val result = importer.execute(prepared, GraphImportPolicy.INITIALIZE_OR_REUSE)
        LoggerFactory.getLogger(javaClass).info("배포 보행 자료 확인: version={}, nodes={}, edges={}, reused={}",
            result.dataVersion,result.nodeCount,result.edgeCount,result.reused)
    }
}
