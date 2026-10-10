package com.igilro.server.route.domain

enum class EdgeKind { WALKWAY, STEPS }

data class WalkingEdge(
    val id: Long,
    val from: Long,
    val to: Long,
    val distanceMeters: Double,
    val kind: EdgeKind = EdgeKind.WALKWAY,
    val forwardAllowed: Boolean = true,
    val backwardAllowed: Boolean = true,
) {
    init {
        require(id > 0 && from > 0 && to > 0 && from != to)
        require(distanceMeters.isFinite() && distanceMeters > 0)
        require(forwardAllowed || backwardAllowed)
    }
}

data class GraphArc(val to: Long, val distanceMeters: Double, val durationSeconds: Double)

object WalkingTimeModel {
    const val VERSION = "walking-v1-flat-1.4-steps-0.9"
    fun duration(distanceMeters: Double, kind: EdgeKind) = distanceMeters / if (kind == EdgeKind.STEPS) 0.9 else 1.4
}

class WalkingGraph(
    val dataVersion: String,
    val areaId: String,
    val bounds: Bounds,
    nodes: Map<Long, Coordinate>,
    edges: List<WalkingEdge>,
) {
    val nodes: Map<Long, Coordinate> = nodes.toMap()
    val edges: List<WalkingEdge> = edges.sortedBy { it.id }
    val adjacency: Map<Long, List<GraphArc>>

    init {
        require(dataVersion.isNotBlank() && areaId.isNotBlank())
        require(nodes.size in 2..100_000 && edges.size in 1..250_000)
        require(nodes.all { (id, point) -> id > 0 && bounds.contains(point) })
        require(edges.map { it.id }.toSet().size == edges.size)
        val arcs = mutableMapOf<Long, MutableList<GraphArc>>()
        this.edges.forEach { edge ->
            require(edge.from in nodes && edge.to in nodes)
            val seconds = WalkingTimeModel.duration(edge.distanceMeters, edge.kind)
            if (edge.forwardAllowed) arcs.getOrPut(edge.from) { mutableListOf() }.add(GraphArc(edge.to, edge.distanceMeters, seconds))
            if (edge.backwardAllowed) arcs.getOrPut(edge.to) { mutableListOf() }.add(GraphArc(edge.from, edge.distanceMeters, seconds))
        }
        adjacency = arcs.mapValues { it.value.toList() }
    }
}

fun interface WalkingGraphRepository {
    fun findActiveGraphs(): List<WalkingGraph>
}
