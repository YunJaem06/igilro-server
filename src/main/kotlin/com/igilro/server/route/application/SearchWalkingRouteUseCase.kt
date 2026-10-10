package com.igilro.server.route.application

import com.igilro.server.route.domain.*
import java.time.Clock

class SearchWalkingRouteUseCase(
    private val repository: WalkingGraphRepository,
    private val planner: DijkstraRoutePlanner,
    private val clock: Clock,
) {
    fun execute(query: RouteQuery): RouteSearchResult {
        if (query.travelMode != "walking" || query.mode != "fastest" ||
            query.origin == query.destination
        ) throw RouteFailure(RouteFailureReason.INVALID_QUERY)
        val evaluatedAt = query.departureAt ?: clock.instant()
        val graphs = repository.findActiveGraphs()
        if (graphs.isEmpty()) throw RouteFailure(RouteFailureReason.DATA_UNAVAILABLE)
        val graph = graphs.firstOrNull { it.bounds.contains(query.origin) && it.bounds.contains(query.destination) }
            ?: throw RouteFailure(RouteFailureReason.OUTSIDE_SUPPORTED_AREA)
        if (query.origin.distanceTo(query.destination) > 5_000) throw RouteFailure(RouteFailureReason.INVALID_QUERY)
        return RouteSearchResult(planner.find(graph, query.origin, query.destination, 50.0), graph.dataVersion, evaluatedAt)
    }
}
