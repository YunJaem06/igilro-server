package com.igilro.server.route.api

import com.igilro.server.route.domain.*
import com.igilro.server.route.domain.shade.ShadeEvaluation
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.round

object RouteMapper {
    fun toQuery(request: RouteSearchRequest) = RouteQuery(
        Coordinate(request.origin.longitude, request.origin.latitude),
        Coordinate(request.destination.longitude, request.destination.latitude),
        request.travelMode, request.mode, request.departureAt?.toInstant(),
    )

    fun toResponse(result: RouteSearchResult): RouteSearchResponse {
        val route = result.route
        val baselineSeconds = ceil(result.baselineDurationSeconds).toLong()
        fun snap(value: SnappedLocation) = SnapResponse(CoordinateResponse(value.coordinate.longitude, value.coordinate.latitude), round(value.distanceMeters * 10) / 10)
        val comparison = result.runningComparison
        return RouteSearchResponse(
            routes = if (comparison == null) {
                listOf(toRoute(route, result.mode, baselineSeconds, result.guidance, shade = result.shade))
            } else {
                listOf(
                    toRoute(comparison.fastest, "fastest", baselineSeconds, comparison.fastestGuidance, running = comparison.fastestMetrics),
                    toRoute(route, result.mode, baselineSeconds, result.guidance, running = comparison.runningMetrics),
                )
            },
            evaluatedAt = result.evaluatedAt,
            dataVersion = result.dataVersion,
            timeModelVersion = WalkingTimeModel.VERSION,
            routingModelVersion = when (result.mode) {
                "shade" -> ShadeRouteOptimizer.VERSION
                "sunlight" -> ShadeRouteOptimizer.SUNLIGHT_VERSION
                "running" -> RunningRouteOptimizer.VERSION
                else -> DijkstraRoutePlanner.VERSION
            },
            baselineDurationSeconds = baselineSeconds,
            snapping = SnappingResponse(snap(route.origin), snap(route.destination)),
        )
    }

    private fun toRoute(
        route: WalkingRoute,
        mode: String,
        baselineSeconds: Long,
        guidance: RouteGuidance?,
        shade: ShadeEvaluation? = null,
        running: RunningRouteMetrics? = null,
    ): RouteResponse {
        val seconds = ceil(route.durationSeconds).toLong()
        return RouteResponse(
            id = UUID.randomUUID().toString(),
            mode = mode,
            geometry = LineStringResponse(coordinates = route.coordinates.map { listOf(it.longitude, it.latitude) }),
            distanceMeters = round(route.distanceMeters * 10) / 10,
            durationSeconds = seconds,
            additionalDurationSeconds = (seconds - baselineSeconds).coerceAtLeast(0),
            metrics = RouteMetricsResponse(
                shadeRatio = shade?.ratio,
                stairsMeters = running?.let { round(it.stairsMeters * 10) / 10 },
                mappedCrossingCount = running?.mappedCrossingCount,
            ),
            shadeEvaluation = shade?.let {
                ShadeEvaluationResponse(it.status.name, it.coverage, it.dataVersion, ShadeEvaluation.MODEL_VERSION,
                    it.attribution, it.solarAltitudeDegrees)
            },
            guidance = RouteGuidanceMapper.toResponse(guidance),
        )
    }
}
