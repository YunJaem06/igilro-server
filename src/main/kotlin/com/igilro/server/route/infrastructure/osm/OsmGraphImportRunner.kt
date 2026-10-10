package com.igilro.server.route.infrastructure.osm

import com.igilro.server.route.application.ImportWalkingGraphUseCase
import com.igilro.server.route.domain.Bounds
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.nio.file.Path

@Component
@ConditionalOnProperty(name = ["igilro.graph-import.file"])
class OsmGraphImportRunner(
    private val importer: ImportWalkingGraphUseCase,
    @Value("\${igilro.graph-import.file}") private val file: String,
    @Value("\${igilro.graph-import.area-id:seoul-forest}") private val areaId: String,
    @Value("\${igilro.graph-import.area-name:서울숲 주변}") private val areaName: String,
    @Value("\${igilro.graph-import.west:127.027}") private val west: Double,
    @Value("\${igilro.graph-import.south:37.533}") private val south: Double,
    @Value("\${igilro.graph-import.east:127.055}") private val east: Double,
    @Value("\${igilro.graph-import.north:37.558}") private val north: Double,
    @Value("\${igilro.graph-import.source:https://download.geofabrik.de/asia/south-korea-latest.osm.pbf}") private val source: String,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        val prepared = OsmGraphReader().read(Path.of(file), areaId, areaName, Bounds(west, south, east, north), source)
        val result = importer.execute(prepared)
        LoggerFactory.getLogger(javaClass).info("보행 그래프 적재 완료: version={}, nodes={}, edges={}, reused={}",
            result.dataVersion, result.nodeCount, result.edgeCount, result.reused)
    }
}
