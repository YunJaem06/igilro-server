package com.igilro.server.route.domain

import java.time.Instant
import com.igilro.server.route.domain.shade.ShadeEvaluation
import com.igilro.server.route.domain.shade.ShadeStatus

data class RouteQuery(
    val origin: Coordinate,
    val destination: Coordinate,
    val travelMode: String = "walking",
    val mode: String = "fastest",
    val departureAt: Instant? = null,
)

data class SnappedLocation(
    val edge: WalkingEdge,
    val fraction: Double,
    val coordinate: Coordinate,
    val distanceMeters: Double,
) {
    // All incident edge endpoints represent the same graph position at a shared node.
    internal val positionId: Long get() = when {
        fraction < 1e-9 -> edge.from
        fraction > 1 - 1e-9 -> edge.to
        else -> -edge.id
    }
}

data class WalkingRoute(
    val coordinates: List<Coordinate>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val origin: SnappedLocation,
    val destination: SnappedLocation,
    val traversals: List<RouteTraversal> = emptyList(),
)

data class RouteTraversal(val edgeId: Long, val fromFraction: Double, val toFraction: Double) {
    init {
        require(edgeId > 0 && fromFraction.isFinite() && toFraction.isFinite())
        require(fromFraction in 0.0..1.0 && toFraction in 0.0..1.0 && fromFraction != toFraction)
    }
}

data class RouteSearchResult(
    val route: WalkingRoute,
    val dataVersion: String,
    val evaluatedAt: Instant,
    val shade: ShadeEvaluation = ShadeEvaluation(ShadeStatus.DATA_UNAVAILABLE),
    val guidance: RouteGuidance? = null,
)
