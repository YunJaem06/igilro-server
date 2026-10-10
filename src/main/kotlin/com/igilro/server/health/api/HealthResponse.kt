package com.igilro.server.health.api

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

data class HealthResponse(
    @field:Schema(description = "공간 DB 사용 가능 (장애는 HTTP 503 공통 오류 응답)", allowableValues = ["UP"])
    val status: String,
    @field:Schema(description = "상태 확인 시각 (UTC, RFC 3339)", example = "2026-10-10T03:00:00Z")
    val checkedAt: Instant,
)
