package com.igilro.server.route.domain

import com.igilro.server.route.domain.shade.*
import java.util.PriorityQueue

/** Exact Pareto-label search: minimum sun exposure time subject to a walking time budget. */
class ShadeRouteOptimizer(private val maximumLabels: Int = 100_000) {
    data class Section(val edgeId: Long, val from: Double, val to: Double)
    data class Arc(val from: Long, val to: Long, val meters: Double, val seconds: Double, val section: Section?, val traversal: RouteTraversal? = null)
    class Network internal constructor(
        internal val graph: WalkingGraph,
        internal val baseline: WalkingRoute,
        internal val arcs: List<Arc>,
        internal val remainingSeconds: Map<Long, Double>,
        val sections: List<Section>,
        val paths: List<List<Coordinate>>,
    )
    data class Result(val route: WalkingRoute, val shade: ShadeEvaluation)
    private data class TimedNode(val node: Long, val seconds: Double)
    private class Label(val node: Long, val seconds: Double, val exposed: Double,
        val previous: Label?, val arc: Arc?, val order: Int) { var active = true }

    fun prepare(graph: WalkingGraph, baseline: WalkingRoute): Network {
        val start = baseline.origin
        val end = baseline.destination
        val arcs = mutableListOf<Arc>()
        fun add(from: Long, to: Long, edge: WalkingEdge, a: Double, b: Double) {
            val fraction = kotlin.math.abs(b - a).coerceIn(0.0, 1.0)
            val meters = edge.distanceMeters * fraction
            arcs.add(Arc(from, to, meters, WalkingTimeModel.duration(meters, edge.kind),
                if (fraction == 0.0) null else Section(edge.id, minOf(a, b), maxOf(a, b)),
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
        val budget = baseline.durationSeconds * MAXIMUM_DURATION_FACTOR
        val feasible = arcs.filter {
            (fromStart[it.from] ?: Double.POSITIVE_INFINITY) + it.seconds +
                (toEnd[it.to] ?: Double.POSITIVE_INFINITY) <= budget + EPSILON
        }
        val sections = feasible.mapNotNull { it.section }.distinct()
        if (sections.size > MAXIMUM_SECTIONS) throw RouteFailure(RouteFailureReason.SEARCH_LIMIT_EXCEEDED)
        val edges = graph.edges.associateBy { it.id }
        fun coordinate(edge: WalkingEdge, fraction: Double): Coordinate {
            val a = graph.nodes.getValue(edge.from)
            val b = graph.nodes.getValue(edge.to)
            return when (fraction) {
                0.0 -> a
                1.0 -> b
                else -> Coordinate(a.longitude + (b.longitude - a.longitude) * fraction,
                    a.latitude + (b.latitude - a.latitude) * fraction)
            }
        }
        return Network(graph, baseline, feasible, toEnd, sections, sections.map {
            val edge = edges.getValue(it.edgeId)
            listOf(coordinate(edge, it.from), coordinate(edge, it.to))
        })
    }

    fun find(network: Network, measurements: List<ShadePathMeasurement>): Result {
        val deadline = System.nanoTime() + 2_000_000_000L
        if (measurements.size != network.sections.size + 1) unavailable()
        val baselineShade = measurements.first().evaluation
        measurements.forEach { measurement ->
            val evaluation = measurement.evaluation
            if (evaluation.status != ShadeStatus.AVAILABLE || evaluation.dataVersion != baselineShade.dataVersion ||
                evaluation.attribution != baselineShade.attribution || evaluation.solarAltitudeDegrees != baselineShade.solarAltitudeDegrees) unavailable()
        }
        val costs = network.sections.zip(measurements.drop(1)).toMap()
        val adjacency = network.arcs.groupBy { it.from }
        val budget = network.baseline.durationSeconds * MAXIMUM_DURATION_FACTOR
        val labels = mutableMapOf<Long, MutableList<Label>>()
        val queue = PriorityQueue(compareBy<Label> { it.exposed }.thenBy { it.seconds }.thenBy { it.order })
        val start = Label(START, 0.0, 0.0, null, null, 0)
        labels[START] = mutableListOf(start)
        queue.add(start)
        var count = 1
        while (queue.isNotEmpty()) {
            if (System.nanoTime() > deadline) throw RouteFailure(RouteFailureReason.SEARCH_LIMIT_EXCEEDED)
            val current = queue.remove()
            if (!current.active) continue
            if (current.node == END) return reconstruct(network, current, costs, baselineShade)
            for (arc in adjacency[current.node].orEmpty()) {
                val seconds = current.seconds + arc.seconds
                if (seconds + (network.remainingSeconds[arc.to] ?: Double.POSITIVE_INFINITY) > budget + EPSILON) continue
                val exposed = current.exposed + arc.seconds * (1.0 - (arc.section?.let { costs.getValue(it).evaluation.ratio!! } ?: 1.0))
                val existing = labels.getOrPut(arc.to) { mutableListOf() }
                // Both resources matter: a slightly sunnier but faster prefix may be needed later.
                if (existing.any { it.seconds <= seconds && it.exposed <= exposed }) continue
                existing.removeAll {
                    if (seconds <= it.seconds && exposed <= it.exposed) { it.active = false; true } else false
                }
                if (count >= maximumLabels) throw RouteFailure(RouteFailureReason.SEARCH_LIMIT_EXCEEDED)
                val next = Label(arc.to, seconds, exposed, current, arc, count++)
                existing.add(next)
                queue.add(next)
            }
        }
        throw RouteFailure(RouteFailureReason.NOT_FOUND)
    }

    private fun reconstruct(network: Network, end: Label, costs: Map<Section, ShadePathMeasurement>, shade: ShadeEvaluation): Result {
        val reversed = mutableListOf<Arc>()
        var cursor = end
        while (cursor.previous != null) { reversed.add(cursor.arc!!); cursor = cursor.previous }
        val arcs = reversed.asReversed()
        fun coordinate(node: Long) = when (node) {
            START -> network.baseline.origin.coordinate
            END -> network.baseline.destination.coordinate
            else -> network.graph.nodes.getValue(node)
        }
        val coordinates = mutableListOf(coordinate(START))
        var length = 0.0
        var shaded = 0.0
        arcs.forEach { arc ->
            val point = coordinate(arc.to)
            if (coordinates.last() != point) coordinates.add(point)
            arc.section?.let {
                val measurement = costs.getValue(it)
                length += measurement.lengthMeters!!
                shaded += measurement.lengthMeters * measurement.evaluation.ratio!!
            }
        }
        if (coordinates.size < 2 || length <= 0) unavailable()
        val route = WalkingRoute(coordinates, arcs.sumOf { it.meters }, end.seconds,
            network.baseline.origin, network.baseline.destination, arcs.mapNotNull { it.traversal })
        return Result(route, shade.copy(ratio = (shaded / length).coerceIn(0.0, 1.0)))
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

    private fun unavailable(): Nothing = throw RouteFailure(RouteFailureReason.SHADE_DATA_UNAVAILABLE)

    companion object {
        const val VERSION = "shade-v1-min-sun-seconds-detour30-pareto100k-sections12k"
        const val MAXIMUM_DURATION_FACTOR = 1.3
        const val MAXIMUM_SECTIONS = 12_000
        private const val START = -1L
        private const val END = -2L
        private const val EPSILON = 1e-8
    }
}
