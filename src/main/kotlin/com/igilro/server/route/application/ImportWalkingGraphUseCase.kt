package com.igilro.server.route.application

import com.igilro.server.route.domain.GraphImport
import com.igilro.server.route.domain.GraphImportRepository
import com.igilro.server.route.domain.GraphImportSummary

class ImportWalkingGraphUseCase(private val repository: GraphImportRepository) {
    fun execute(graph: GraphImport): GraphImportSummary {
        require(graph.areaId.matches(Regex("[a-z0-9][a-z0-9-]{0,63}")))
        require(graph.dataVersion.matches(Regex("osm-v1-[a-f0-9]{64}")))
        require(graph.checksum.matches(Regex("[a-f0-9]{64}")))
        require(graph.areaName.isNotBlank() && graph.source.isNotBlank())
        require(graph.nodes.size in 2..100_000 && graph.edges.size in 1..250_000)
        require(graph.nodes.all { (id, point) -> id > 0 && graph.bounds.contains(point) })
        require(graph.edges.map { it.id }.toSet().size == graph.edges.size)
        require(graph.edges.all { edge ->
            edge.id > 0 && edge.wayId > 0 && edge.from in graph.nodes && edge.to in graph.nodes &&
                graph.nodes[edge.from] != graph.nodes[edge.to] && (edge.forwardAllowed || edge.backwardAllowed)
        })
        require(graph.nodeGuidance.keys.all { it in graph.nodes })
        if(graph.guidanceMetadataVersion==null) require(graph.nodeGuidance.isEmpty() && graph.edgeGuidance.isEmpty())
        else {
            require(graph.guidanceMetadataVersion==com.igilro.server.route.domain.GuidanceMetadataVersion.OSM_V1)
            val ways=graph.edges.associate { it.id to it.wayId }
            require(graph.edgeGuidance.keys==ways.keys && graph.edgeGuidance.all { (id,metadata) -> metadata.osmWayId==ways[id] })
        }
        return repository.save(graph)
    }
}
