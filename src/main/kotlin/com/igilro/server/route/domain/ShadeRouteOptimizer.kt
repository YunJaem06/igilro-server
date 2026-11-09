package com.igilro.server.route.domain

import com.igilro.server.route.domain.shade.*

/** Minimizes time in the selected exposure within the walking time budget. */
class ShadeRouteOptimizer(private val maximumLabels: Int = 100_000) {
    enum class Exposure { SUNLIGHT, SHADE }

    data class Section(val edgeId: Long, val from: Double, val to: Double)
    class Network internal constructor(
        internal val search: BoundedRouteSearch,
        val sections: List<Section>,
        val paths: List<List<Coordinate>>,
    )
    data class Result(val route: WalkingRoute, val shade: ShadeEvaluation)

    fun prepare(graph: WalkingGraph, baseline: WalkingRoute): Network {
        val search = BoundedRouteSearch(graph, baseline)
        val sections = search.arcs.mapNotNull { it.traversal?.section() }.distinct()
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
        return Network(search, sections, sections.map {
            val edge = edges.getValue(it.edgeId)
            listOf(coordinate(edge, it.from), coordinate(edge, it.to))
        })
    }

    fun find(
        network: Network,
        measurements: List<ShadePathMeasurement>,
        minimize: Exposure = Exposure.SUNLIGHT,
    ): Result {
        if (measurements.size != network.sections.size + 1) unavailable()
        val baselineShade = measurements.first().evaluation
        measurements.forEach { measurement ->
            val evaluation = measurement.evaluation
            if (evaluation.status != ShadeStatus.AVAILABLE || evaluation.dataVersion != baselineShade.dataVersion ||
                evaluation.attribution != baselineShade.attribution || evaluation.solarAltitudeDegrees != baselineShade.solarAltitudeDegrees) unavailable()
        }
        val costs = network.sections.zip(measurements.drop(1)).toMap()
        val route = network.search.find(maximumLabels = maximumLabels) { _, arc ->
            val ratio = arc.traversal?.let { costs.getValue(it.section()).evaluation.ratio!! } ?: 0.0
            arc.seconds * when (minimize) {
                Exposure.SUNLIGHT -> 1.0 - ratio
                Exposure.SHADE -> ratio
            }
        }
        var length = 0.0
        var shaded = 0.0
        route.traversals.forEach { traversal ->
            val measurement = costs.getValue(traversal.section())
            length += measurement.lengthMeters!!
            shaded += measurement.lengthMeters * measurement.evaluation.ratio!!
        }
        if (route.coordinates.size < 2 || length <= 0) unavailable()
        return Result(route, baselineShade.copy(ratio = (shaded / length).coerceIn(0.0, 1.0)))
    }

    private fun RouteTraversal.section() = Section(edgeId, minOf(fromFraction, toFraction), maxOf(fromFraction, toFraction))
    private fun unavailable(): Nothing = throw RouteFailure(RouteFailureReason.SHADE_DATA_UNAVAILABLE)

    companion object {
        const val VERSION = "shade-v1-min-sun-seconds-detour30-pareto100k-sections12k"
        const val SUNLIGHT_VERSION = "sunlight-v1-min-shade-seconds-detour30-pareto100k-sections12k"
        const val MAXIMUM_SECTIONS = 12_000
    }
}
