package com.igilro.server.route.domain

import kotlin.math.cos

/** Local projection is used only to locate a point on a small pilot area's segments. */
class EdgeSnapper {
    fun candidates(point: Coordinate, graph: WalkingGraph, maximumDistanceMeters: Double): List<SnappedLocation> {
        val scale = cos(Math.toRadians(point.latitude))
        val candidates = graph.edges.asSequence().map { edge ->
            val from = graph.nodes.getValue(edge.from)
            val to = graph.nodes.getValue(edge.to)
            val x = (to.longitude - from.longitude) * scale
            val y = to.latitude - from.latitude
            val lengthSquared = x * x + y * y
            val fraction = if (lengthSquared == 0.0) 0.0 else (
                ((point.longitude - from.longitude) * scale * x + (point.latitude - from.latitude) * y) / lengthSquared
            ).coerceIn(0.0, 1.0)
            val coordinate = when {
                fraction < 1e-9 -> from
                fraction > 1 - 1e-9 -> to
                else -> Coordinate(from.longitude + (to.longitude - from.longitude) * fraction,
                    from.latitude + (to.latitude - from.latitude) * fraction)
            }
            SnappedLocation(edge, fraction, coordinate, point.distanceTo(coordinate))
        }.filter { it.distanceMeters <= maximumDistanceMeters }
            .sortedWith(compareBy<SnappedLocation> { it.distanceMeters }.thenBy { it.edge.id })
            .distinctBy { it.positionId }.take(8).toList()
        if (candidates.isEmpty()) throw RouteFailure(RouteFailureReason.NOT_FOUND)
        return candidates
    }
}
