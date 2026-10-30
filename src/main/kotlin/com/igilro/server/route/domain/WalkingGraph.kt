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

data class GraphArc(val to: Long, val distanceMeters: Double, val durationSeconds: Double, val traversal: RouteTraversal? = null)

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
    nodeGuidance: Map<Long, NodeGuidanceMetadata> = emptyMap(),
    edgeGuidance: Map<Long, EdgeGuidanceMetadata> = emptyMap(),
    val guidanceMetadataVersion: String? = null,
) {
    val nodes: Map<Long, Coordinate> = nodes.toMap()
    val edges: List<WalkingEdge> = edges.sortedBy { it.id }
    val nodeGuidance = nodeGuidance.toMap()
    val edgeGuidance = edgeGuidance.toMap()
    val adjacency: Map<Long, List<GraphArc>>
    /** Weak components use source node identities, never proximity or coordinate joins. */
    val connectedComponents: List<Set<Long>> by lazy {
        val neighbors=mutableMapOf<Long,MutableList<Long>>()
        this.edges.forEach { edge ->
            neighbors.getOrPut(edge.from) { mutableListOf() }.add(edge.to)
            neighbors.getOrPut(edge.to) { mutableListOf() }.add(edge.from)
        }
        val remaining=this.nodes.keys.toMutableSet();val components=mutableListOf<Set<Long>>()
        while(remaining.isNotEmpty()) {
            val queue=ArrayDeque<Long>();val component=mutableSetOf<Long>()
            val first=remaining.first();remaining.remove(first);queue.add(first)
            while(queue.isNotEmpty()) {
                val node=queue.removeFirst();component.add(node)
                neighbors[node].orEmpty().forEach { if(remaining.remove(it)) queue.add(it) }
            }
            components.add(component)
        }
        components.sortedWith(compareByDescending<Set<Long>> { it.size }.thenBy { it.min() })
    }

    init {
        require(dataVersion.isNotBlank() && areaId.isNotBlank())
        require(nodes.size in 2..100_000 && edges.size in 1..250_000)
        require(nodes.all { (id, point) -> id > 0 && bounds.contains(point) })
        val edgeIds=edges.map { it.id }.toSet()
        require(edgeIds.size == edges.size)
        require(nodeGuidance.keys.all { it in nodes })
        require(edgeGuidance.keys.all { it in edgeIds })
        val arcs = mutableMapOf<Long, MutableList<GraphArc>>()
        this.edges.forEach { edge ->
            require(edge.from in nodes && edge.to in nodes)
            val seconds = WalkingTimeModel.duration(edge.distanceMeters, edge.kind)
            if (edge.forwardAllowed) arcs.getOrPut(edge.from) { mutableListOf() }.add(GraphArc(edge.to, edge.distanceMeters, seconds, RouteTraversal(edge.id, 0.0, 1.0)))
            if (edge.backwardAllowed) arcs.getOrPut(edge.to) { mutableListOf() }.add(GraphArc(edge.from, edge.distanceMeters, seconds, RouteTraversal(edge.id, 1.0, 0.0)))
        }
        adjacency = arcs.mapValues { it.value.toList() }
    }
}

fun interface WalkingGraphRepository {
    fun findActiveGraphs(): List<WalkingGraph>
}
