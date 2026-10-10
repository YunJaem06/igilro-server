package com.igilro.server.health.api

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

data class HealthResponse(
    @field:Schema(description = "공간 DB 사용 가능 여부", allowableValues = ["UP", "DOWN"])
    val status: String,
    @field:Schema(description = "상태 확인 시각 (UTC, RFC 3339)", example = "2026-10-10T03:00:00Z")
    val checkedAt: Instant,
)
