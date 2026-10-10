package com.igilro.server.route.domain

import java.time.Instant

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
)

data class WalkingRoute(
    val coordinates: List<Coordinate>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val origin: SnappedLocation,
    val destination: SnappedLocation,
)

data class RouteSearchResult(val route: WalkingRoute, val dataVersion: String, val evaluatedAt: Instant)
