package com.igilro.server.route.domain

import java.util.PriorityQueue

/** Least-cost route within a walking time budget, using the baseline's snapped endpoints. */
internal class BoundedRouteSearch(
    private val graph: WalkingGraph,
    private val baseline: WalkingRoute,
) {
    data class Arc(val from: Long, val to: Long, val meters: Double, val seconds: Double, val traversal: RouteTraversal?)
    private data class TimedNode(val node: Long, val seconds: Double)
    private data class State(val node: Long, val arrivalContext: Long)
    private class Label(
        val node: Long, val seconds: Double, val cost: Double,
        val previous: Label?, val arc: Arc?, val order: Int,
    ) {
        var active = true
    }

    private val remainingSeconds: Map<Long, Double>
    private val budget = baseline.durationSeconds * MAXIMUM_DURATION_FACTOR
    val arcs: List<Arc>

    init {
        val start = baseline.origin
        val end = baseline.destination
        val arcs = mutableListOf<Arc>()
        fun add(from: Long, to: Long, edge: WalkingEdge, a: Double, b: Double) {
            val fraction = kotlin.math.abs(b - a).coerceIn(0.0, 1.0)
            val meters = edge.distanceMeters * fraction
            arcs.add(Arc(from, to, meters, WalkingTimeModel.duration(meters, edge.kind),
                if (fraction == 0.0) null else RouteTraversal(edge.id,a,b)))
        }
        graph.edges.forEach { edge ->
            if (edge.forwardAllowed) add(edge.from, edge.to, edge, 0.0, 1.0)
            if (edge.backwardAllowed) add(edge.to, edge.from, edge, 1.0, 0.0)
        }
        // Keep exactly the fastest route's snapped endpoints and legal walking directions.
        if (start.fraction < 1e-9) add(START, start.edge.from, start.edge, 0.0, 0.0)
        if (start.fraction > 1 - 1e-9) add(START, start.edge.to, start.edge, 1.0, 1.0)
        if (end.fraction < 1e-9) add(end.edge.from, END, end.edge, 0.0, 0.0)
        if (end.fraction > 1 - 1e-9) add(end.edge.to, END, end.edge, 1.0, 1.0)
        if (start.edge.forwardAllowed) add(START, start.edge.to, start.edge, start.fraction, 1.0)
        if (start.edge.backwardAllowed) add(START, start.edge.from, start.edge, start.fraction, 0.0)
        if (end.edge.forwardAllowed) add(end.edge.from, END, end.edge, 0.0, end.fraction)
        if (end.edge.backwardAllowed) add(end.edge.to, END, end.edge, 1.0, end.fraction)
        if (start.edge.id == end.edge.id) {
            val delta = end.fraction - start.fraction
            if (delta >= 0 && start.edge.forwardAllowed || delta <= 0 && start.edge.backwardAllowed)
                add(START, END, start.edge, start.fraction, end.fraction)
        }
        val fromStart = shortestTimes(arcs, START, false)
        val toEnd = shortestTimes(arcs, END, true)
        this.arcs = arcs.filter {
            (fromStart[it.from] ?: Double.POSITIVE_INFINITY) + it.seconds +
                (toEnd[it.to] ?: Double.POSITIVE_INFINITY) <= budget + EPSILON
        }
        remainingSeconds = toEnd
    }

    fun find(
        maximumLabels: Int = 100_000,
        arrivalContext: (Arc) -> Long = { 0L },
        cost: (Arc?, Arc) -> Double,
    ): WalkingRoute {
        val deadline = System.nanoTime() + 2_000_000_000L
        val adjacency = arcs.groupBy { it.from }
        val queue = PriorityQueue(compareBy<Label> { it.cost }.thenBy { it.seconds }.thenBy { it.order })
        val start = Label(START, 0.0, 0.0, null, null, 0)
        val labels = mutableMapOf(State(START, 0L) to mutableListOf(start))
        queue.add(start)
        var count = 1
        while (queue.isNotEmpty()) {
            if (System.nanoTime() > deadline) throw RouteFailure(RouteFailureReason.SEARCH_LIMIT_EXCEEDED)
            val current = queue.remove()
            if (!current.active) continue
            if (current.node == END) return reconstruct(current)
            for (arc in adjacency[current.node].orEmpty()) {
                val seconds = current.seconds + arc.seconds
                if (seconds + (remainingSeconds[arc.to] ?: Double.POSITIVE_INFINITY) > budget + EPSILON) continue
                val totalCost = current.cost + cost(current.arc, arc)
                val state = State(arc.to, arrivalContext(arc))
                val existing = labels.getOrPut(state) { mutableListOf() }
                // Only compare labels with the same future costs and time budget.
                if (existing.any { it.seconds <= seconds && it.cost <= totalCost }) continue
                existing.removeAll {
                    if (seconds <= it.seconds && totalCost <= it.cost) {
                        it.active = false
                        true
                    } else false
                }
                if (count >= maximumLabels) throw RouteFailure(RouteFailureReason.SEARCH_LIMIT_EXCEEDED)
                val next = Label(arc.to, seconds, totalCost, current, arc, count++)
                existing.add(next)
                queue.add(next)
            }
        }
        throw RouteFailure(RouteFailureReason.NOT_FOUND)
    }

    private fun reconstruct(end: Label): WalkingRoute {
        val reversed = mutableListOf<Arc>()
        var cursor = end
        while (cursor.previous != null) {
            reversed.add(requireNotNull(cursor.arc))
            cursor = requireNotNull(cursor.previous)
        }
        val routeArcs = reversed.asReversed()
        val coordinates = mutableListOf(baseline.origin.coordinate)
        routeArcs.forEach { arc ->
            val point = if (arc.to == END) baseline.destination.coordinate else graph.nodes.getValue(arc.to)
            if (coordinates.last() != point) coordinates.add(point)
        }
        return WalkingRoute(coordinates, routeArcs.sumOf { it.meters }, end.seconds,
            baseline.origin, baseline.destination, routeArcs.mapNotNull { it.traversal })
    }

    private fun shortestTimes(arcs: List<Arc>, source: Long, reverse: Boolean): Map<Long, Double> {
        val adjacency = arcs.groupBy { if (reverse) it.to else it.from }
        val times = mutableMapOf(source to 0.0)
        val queue = PriorityQueue(compareBy<TimedNode> { it.seconds }.thenBy { it.node })
        queue.add(TimedNode(source, 0.0))
        while (queue.isNotEmpty()) {
            val current = queue.remove()
            if (current.seconds > times.getValue(current.node)) continue
            adjacency[current.node].orEmpty().forEach { arc ->
                val node = if (reverse) arc.from else arc.to
                val time = current.seconds + arc.seconds
                if (time < (times[node] ?: Double.POSITIVE_INFINITY)) {
                    times[node] = time
                    queue.add(TimedNode(node, time))
                }
            }
        }
        return times
    }

    companion object {
        const val MAXIMUM_DURATION_FACTOR = 1.3
        private const val START = -1L
        private const val END = -2L
        private const val EPSILON = 1e-8
    }
}
