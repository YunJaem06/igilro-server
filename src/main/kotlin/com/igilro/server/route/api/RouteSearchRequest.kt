package com.igilro.server.route.api

import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import io.swagger.v3.oas.annotations.media.Schema
import java.time.OffsetDateTime

data class CoordinateRequest(
    @field:DecimalMin("-180") @field:DecimalMax("180")
    @field:Schema(example = "127.0374", requiredMode = Schema.RequiredMode.REQUIRED) val longitude: Double,
    @field:DecimalMin("-90") @field:DecimalMax("90")
    @field:Schema(example = "37.5445", requiredMode = Schema.RequiredMode.REQUIRED) val latitude: Double,
)

data class RouteSearchRequest(
    @field:Valid @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val origin: CoordinateRequest,
    @field:Valid @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val destination: CoordinateRequest,
    @field:NotBlank @field:Schema(allowableValues = ["walking"], defaultValue = "walking", requiredMode = Schema.RequiredMode.NOT_REQUIRED) val travelMode: String = "walking",
    @field:NotBlank @field:Schema(description = "shade는 전체 후보 구간의 그늘 평가가 가능할 때 최단 시간 대비 30% 이내에서 햇빛 노출 시간을 최소화", allowableValues = ["fastest", "shade"], defaultValue = "fastest", requiredMode = Schema.RequiredMode.NOT_REQUIRED) val mode: String = "fastest",
    @field:Schema(description = "오프셋을 포함한 출발 시각. 생략하면 서버의 현재 UTC 시각 사용", example = "2026-10-10T14:00:00+09:00")
    val departureAt: OffsetDateTime? = null,
)
