package com.igilro.server.route.domain

import java.time.Instant

data class ImportedEdge(
    val id: Long, val wayId: Long, val from: Long, val to: Long,
    val kind: EdgeKind, val forwardAllowed: Boolean, val backwardAllowed: Boolean,
)

data class GraphImport(
    val dataVersion: String,
    val areaId: String,
    val areaName: String,
    val bounds: Bounds,
    val source: String,
    val sourceTimestamp: Instant,
    val checksum: String,
    val nodes: Map<Long, Coordinate>,
    val edges: List<ImportedEdge>,
)

data class GraphImportSummary(val dataVersion: String, val nodeCount: Int, val edgeCount: Int, val reused: Boolean)

fun interface GraphImportRepository {
    fun save(graph: GraphImport): GraphImportSummary
}
