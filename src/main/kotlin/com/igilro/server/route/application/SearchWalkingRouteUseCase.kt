package com.igilro.server.route.application

import com.igilro.server.route.domain.*
import java.time.Clock
import com.igilro.server.route.domain.shade.*

class SearchWalkingRouteUseCase(
    private val repository: WalkingGraphRepository,
    private val planner: DijkstraRoutePlanner,
    private val clock: Clock,
    private val shadeEvaluator: RouteShadeEvaluator,
    private val batchEvaluator: RouteShadeBatchEvaluator = RouteShadeBatchEvaluator { _, paths, _, _ ->
        paths.map { ShadePathMeasurement(ShadeEvaluation(ShadeStatus.DATA_UNAVAILABLE)) }
    },
    private val groundVerifiedDatasets: Set<String> = emptySet(),
) {
    fun execute(query: RouteQuery): RouteSearchResult {
        if (query.travelMode != "walking" || query.mode !in setOf("fastest", "shade") ||
            query.origin == query.destination
        ) throw RouteFailure(RouteFailureReason.INVALID_QUERY)
        val evaluatedAt = query.departureAt ?: clock.instant()
        val graphs = repository.findActiveGraphs()
        if (graphs.isEmpty()) throw RouteFailure(RouteFailureReason.DATA_UNAVAILABLE)
        val graph = graphs.filter { it.bounds.contains(query.origin) && it.bounds.contains(query.destination) }
            .minWithOrNull(compareBy<WalkingGraph> {
                (it.bounds.east - it.bounds.west) * (it.bounds.north - it.bounds.south)
            }.thenBy { it.areaId })
            ?: throw RouteFailure(RouteFailureReason.OUTSIDE_SUPPORTED_AREA)
        if (query.origin.distanceTo(query.destination) > 5_000) throw RouteFailure(RouteFailureReason.INVALID_QUERY)
        val route = planner.find(graph, query.origin, query.destination, 50.0)
        val shade = shadeEvaluator.evaluate(graph.areaId, route.coordinates, evaluatedAt)
        if (query.mode == "fastest") return RouteSearchResult(route, graph.dataVersion, evaluatedAt, shade,
            guidance=RouteGuidanceGenerator().generate(graph,route))
        when (shade.status) {
            ShadeStatus.NIGHT, ShadeStatus.LOW_SUN, ShadeStatus.UNSUPPORTED_TIME ->
                throw RouteFailure(RouteFailureReason.SHADE_TIME_UNSUPPORTED)
            ShadeStatus.AVAILABLE -> Unit
            else -> throw RouteFailure(RouteFailureReason.SHADE_DATA_UNAVAILABLE)
        }
        // Building completeness cannot establish that a walking network is on flat ground.
        if (graph.dataVersion !in groundVerifiedDatasets) throw RouteFailure(RouteFailureReason.SHADE_DATA_UNAVAILABLE)
        val optimizer = ShadeRouteOptimizer()
        val network = optimizer.prepare(graph, route)
        val center = Coordinate((route.coordinates.minOf { it.longitude } + route.coordinates.maxOf { it.longitude }) / 2,
            (route.coordinates.minOf { it.latitude } + route.coordinates.maxOf { it.latitude }) / 2)
        // The baseline is measured again with every feasible section in one database snapshot.
        val measurements = batchEvaluator.evaluate(graph.areaId, listOf(route.coordinates) + network.paths, center, evaluatedAt)
        val optimized = optimizer.find(network, measurements)
        return RouteSearchResult(optimized.route, graph.dataVersion, evaluatedAt, optimized.shade, "shade", route.durationSeconds,
            RouteGuidanceGenerator().generate(graph,optimized.route))
    }
}
