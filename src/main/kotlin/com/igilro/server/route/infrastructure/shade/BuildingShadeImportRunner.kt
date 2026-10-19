package com.igilro.server.route.infrastructure.shade

import com.igilro.server.route.application.ImportBuildingShadeUseCase
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.core.env.Environment
import java.nio.file.Path

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = ["igilro.building-import.file"])
class BuildingShadeImportRunner(
    private val importer: ImportBuildingShadeUseCase,
    @Value("\${igilro.building-import.file}") private val file: String,
    private val environment: Environment,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        require(!environment.containsProperty("igilro.graph-import.file")) { "Run graph and building imports separately" }
        val result = importer.execute(GeoJsonBuildingReader().read(Path.of(file)))
        LoggerFactory.getLogger(javaClass).info("건물 자료 적재 완료: version={}, buildings={}, knownHeights={}, reused={}",
            result.dataVersion, result.buildingCount, result.knownHeightCount, result.reused)
    }
}
