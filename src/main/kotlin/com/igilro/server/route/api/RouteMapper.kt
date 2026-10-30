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
        val baselineSeconds = ceil(route.durationSeconds).toLong()
        val seconds = ceil(route.durationSeconds).toLong()
        fun snap(value: SnappedLocation) = SnapResponse(CoordinateResponse(value.coordinate.longitude, value.coordinate.latitude), round(value.distanceMeters * 10) / 10)
        return RouteSearchResponse(
            routes = listOf(RouteResponse(
                id = UUID.randomUUID().toString(),
                geometry = LineStringResponse(coordinates = route.coordinates.map { listOf(it.longitude, it.latitude) }),
                distanceMeters = round(route.distanceMeters * 10) / 10,
                durationSeconds = seconds,
                additionalDurationSeconds = (seconds - baselineSeconds).coerceAtLeast(0),
                metrics = RouteMetricsResponse(shadeRatio = result.shade.ratio),
                shadeEvaluation = ShadeEvaluationResponse(result.shade.status.name, result.shade.coverage,
                    result.shade.dataVersion, ShadeEvaluation.MODEL_VERSION, result.shade.attribution, result.shade.solarAltitudeDegrees),
                guidance = RouteGuidanceMapper.toResponse(result.guidance),
            )),
            evaluatedAt = result.evaluatedAt,
            dataVersion = result.dataVersion,
            timeModelVersion = WalkingTimeModel.VERSION,
            routingModelVersion = DijkstraRoutePlanner.VERSION,
            baselineDurationSeconds = baselineSeconds,
            snapping = SnappingResponse(snap(route.origin), snap(route.destination)),
        )
    }
}
