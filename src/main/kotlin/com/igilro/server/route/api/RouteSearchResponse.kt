package com.igilro.server.route.api

import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

data class LineStringResponse(
    @field:Schema(allowableValues = ["LineString"], requiredMode = Schema.RequiredMode.REQUIRED) val type: String = "LineString",
    @field:ArraySchema(minItems = 2, arraySchema = Schema(description = "WGS84 [경도, 위도] 좌표 배열", requiredMode = Schema.RequiredMode.REQUIRED))
    val coordinates: List<List<Double>>,
)

data class RouteMetricsResponse(
    @field:Schema(description = "전체 경로를 평가한 건물 그늘 길이 비율. 미평가 시 null이며 shadeEvaluation에 사유 제공", minimum = "0", maximum = "1")
    val shadeRatio: Double? = null,
    val quietScore: Double? = null,
    val lightingScore: Double? = null,
    val trafficSignalCount: Int? = null,
)

data class ShadeEvaluationResponse(
    @field:Schema(allowableValues = ["AVAILABLE", "DATA_UNAVAILABLE", "INCOMPLETE_COVERAGE", "NIGHT", "LOW_SUN", "UNSUPPORTED_TIME"])
    val status: String,
    @field:Schema(description = "전체 경로 중 건물 목록·높이·주변 범위를 모두 평가할 수 있는 길이 비율. 자료 없음은 null", minimum = "0", maximum = "1")
    val coverage: Double?,
    val dataVersion: String?,
    val modelVersion: String,
    val attribution: String?,
    val solarAltitudeDegrees: Double?,
)

data class RouteResponse(
    @field:Schema(description = "응답 안에서 경로를 식별하는 값. 저장·재조회 기능은 미지원", requiredMode = Schema.RequiredMode.REQUIRED) val id: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val mode: String = "fastest",
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val geometry: LineStringResponse,
    @field:Schema(description = "스냅된 두 지점 사이의 보행 그래프 거리(m)", requiredMode = Schema.RequiredMode.REQUIRED) val distanceMeters: Double,
    @field:Schema(description = "보행/계단 속도 모델의 예상 소요 시간(초), 올림 정수", requiredMode = Schema.RequiredMode.REQUIRED) val durationSeconds: Long,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val additionalDurationSeconds: Long,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val metrics: RouteMetricsResponse = RouteMetricsResponse(),
    @field:Schema(description = "출발 시각의 건물 그림자 평가 상태·커버리지·버전") val shadeEvaluation: ShadeEvaluationResponse? = null,
)

data class CoordinateResponse(val longitude: Double, val latitude: Double)
data class SnapResponse(val coordinate: CoordinateResponse, val distanceMeters: Double)
data class SnappingResponse(val origin: SnapResponse, val destination: SnapResponse)

data class RouteSearchResponse(
    @field:ArraySchema(minItems = 1, arraySchema = Schema(requiredMode = Schema.RequiredMode.REQUIRED)) val routes: List<RouteResponse>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val evaluatedAt: Instant,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val dataVersion: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val timeModelVersion: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val routingModelVersion: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val baselineDurationSeconds: Long,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val snapping: SnappingResponse,
    val attribution: String = "© OpenStreetMap contributors",
)
