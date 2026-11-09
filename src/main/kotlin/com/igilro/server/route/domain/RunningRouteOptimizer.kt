package com.igilro.server.route.domain

import kotlin.math.abs

data class RunningRouteMetrics(val stairsMeters: Double, val mappedCrossingCount: Int)

/** Prefers fewer interruptions within the same walking time budget as shade routing. */
class RunningRouteOptimizer {
    fun find(graph: WalkingGraph, baseline: WalkingRoute): WalkingRoute {
        val stairs = graph.edges.filter { it.kind == EdgeKind.STEPS }.map { it.id }.toSet()
        val search = BoundedRouteSearch(graph, baseline)
        return search.find(
            arrivalContext = { arc ->
                val traversal = arc.traversal
                if (traversal == null) 0L
                else graph.edgeGuidance[traversal.edgeId]?.takeIf { it.crossing != null }?.osmWayId ?: -1L
            },
        ) { before, after ->
            val edgeId = after.traversal?.edgeId
            if (edgeId == null) 0.0
            else {
                val previousEdge = before?.traversal?.edgeId
                val crossing = graph.crossingAt(
                    after.from.takeIf { previousEdge != null },
                    previousEdge?.let { graph.edgeGuidance[it] },
                    graph.edgeGuidance[edgeId],
                )
                val stairsMeters = if (edgeId in stairs) after.meters else 0.0
                stairsMeters + if (crossing != null) CROSSING_PENALTY else 0.0
            }
        }
    }

    fun metrics(graph: WalkingGraph, route: WalkingRoute, guidance: RouteGuidance): RunningRouteMetrics {
        val stairs = graph.edges.filter { it.kind == EdgeKind.STEPS }.associateBy { it.id }
        val stairsMeters = route.traversals.sumOf { trace ->
            (stairs[trace.edgeId]?.distanceMeters ?: 0.0) * abs(trace.toFraction - trace.fromFraction)
        }
        return RunningRouteMetrics(stairsMeters, guidance.steps.count { it.crossing != null })
    }

    companion object {
        const val VERSION = "running-v1-stairs-m-crossing30-detour30-pareto100k"
        private const val CROSSING_PENALTY = 30.0
    }
}
