package com.igilro.server.route.domain

import java.util.PriorityQueue

class DijkstraRoutePlanner(private val snapper: EdgeSnapper = EdgeSnapper()) {
    private data class QueueItem(val node: Long, val seconds: Double)
    private data class Previous(val node: Long, val arc: GraphArc)

    fun find(graph: WalkingGraph, origin: Coordinate, destination: Coordinate, snapLimitMeters: Double): WalkingRoute {
        val starts = snapper.candidates(origin, graph, snapLimitMeters)
        val ends = snapper.candidates(destination, graph, snapLimitMeters)
        if (starts.first().positionId == ends.first().positionId &&
            starts.first().coordinate.distanceTo(ends.first().coordinate) < 0.01
        ) throw RouteFailure(RouteFailureReason.INVALID_QUERY)
        val pairs = starts.flatMap { a -> ends.map { b -> a to b } }
            .distinctBy { (a, b) -> a.positionId to b.positionId }
            .sortedBy { (a, b) -> a.distanceMeters + b.distanceMeters }
        var best: WalkingRoute? = null
        var bestSnapDistance = Double.POSITIVE_INFINITY
        var collapsed = false
        for ((start, end) in pairs) {
            val snapDistance = start.distanceMeters + end.distanceMeters
            if (best != null && snapDistance > bestSnapDistance + 1e-6) break
            try {
                val candidate = findConnected(graph, start, end)
                if (best == null || candidate.durationSeconds < best.durationSeconds) {
                    best = candidate
                    bestSnapDistance = snapDistance
                }
            } catch (failure: RouteFailure) {
                when (failure.reason) {
                    RouteFailureReason.NOT_FOUND -> Unit
                    RouteFailureReason.INVALID_QUERY -> collapsed = true
                    else -> throw failure
                }
            }
        }
        return best ?: throw RouteFailure(if (collapsed) RouteFailureReason.INVALID_QUERY else RouteFailureReason.NOT_FOUND)
    }

    private fun findConnected(graph: WalkingGraph, start: SnappedLocation, end: SnappedLocation): WalkingRoute {
        if (start.coordinate.distanceTo(end.coordinate) < 0.01) throw RouteFailure(RouteFailureReason.INVALID_QUERY)
        val additions = mutableMapOf<Long, MutableList<GraphArc>>()
        fun add(from: Long, to: Long, edge: WalkingEdge, fraction: Double) {
            val metres = edge.distanceMeters * fraction.coerceIn(0.0, 1.0)
            additions.getOrPut(from) { mutableListOf() }.add(GraphArc(to, metres, WalkingTimeModel.duration(metres, edge.kind)))
        }
        // Endpoint snaps connect to all incident ways, including at one-way foot edges.
        if (start.fraction < 1e-9) add(START, start.edge.from, start.edge, 0.0)
        if (start.fraction > 1 - 1e-9) add(START, start.edge.to, start.edge, 0.0)
        if (end.fraction < 1e-9) add(end.edge.from, END, end.edge, 0.0)
        if (end.fraction > 1 - 1e-9) add(end.edge.to, END, end.edge, 0.0)
        if (start.edge.forwardAllowed) add(START, start.edge.to, start.edge, 1 - start.fraction)
        if (start.edge.backwardAllowed) add(START, start.edge.from, start.edge, start.fraction)
        if (end.edge.forwardAllowed) add(end.edge.from, END, end.edge, end.fraction)
        if (end.edge.backwardAllowed) add(end.edge.to, END, end.edge, 1 - end.fraction)
        if (start.edge.id == end.edge.id) {
            val delta = end.fraction - start.fraction
            if (delta >= 0 && start.edge.forwardAllowed) add(START, END, start.edge, delta)
            if (delta <= 0 && start.edge.backwardAllowed) add(START, END, start.edge, -delta)
        }

        val queue = PriorityQueue(compareBy<QueueItem> { it.seconds }.thenBy { it.node })
        val seconds = mutableMapOf(START to 0.0)
        val previous = mutableMapOf<Long, Previous>()
        queue.add(QueueItem(START, 0.0))
        while (queue.isNotEmpty()) {
            val current = queue.remove()
            if (current.seconds > seconds.getValue(current.node)) continue
            if (current.node == END) break
            (graph.adjacency[current.node].orEmpty() + additions[current.node].orEmpty()).forEach { arc ->
                val candidate = current.seconds + arc.durationSeconds
                if (candidate < (seconds[arc.to] ?: Double.POSITIVE_INFINITY)) {
                    seconds[arc.to] = candidate
                    previous[arc.to] = Previous(current.node, arc)
                    queue.add(QueueItem(arc.to, candidate))
                }
            }
        }
        if (END !in seconds) throw RouteFailure(RouteFailureReason.NOT_FOUND)
        val reversed = mutableListOf(END)
        var node = END
        var metres = 0.0
        while (node != START) {
            val step = previous.getValue(node)
            metres += step.arc.distanceMeters
            node = step.node
            reversed.add(node)
        }
        fun coordinate(id: Long) = when (id) {
            START -> start.coordinate
            END -> end.coordinate
            else -> graph.nodes.getValue(id)
        }
        val coordinates = mutableListOf<Coordinate>()
        reversed.asReversed().map(::coordinate).forEach { point ->
            if (coordinates.lastOrNull() != point) coordinates.add(point)
        }
        if (coordinates.size < 2 || metres <= 0) throw RouteFailure(RouteFailureReason.INVALID_QUERY)
        return WalkingRoute(coordinates, metres, seconds.getValue(END), start, end)
    }

    companion object {
        const val VERSION = "dijkstra-v2-distinct8-snap50-direct5km"
        private const val START = -1L
        private const val END = -2L
    }
}
