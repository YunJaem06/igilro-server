package com.igilro.server.shared.api.error

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

@Schema(description = "Flutter 공통 오류 계약. HTTP 오류 상태와 함께 application/json으로 응답합니다.")
data class ApiErrorResponse(
    @field:Schema(
        description = "클라이언트 분기에 사용하는 안정적인 오류 코드",
        example = "INVALID_REQUEST",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    val code: String,
    @field:Schema(description = "진단용 메시지. 앱은 표시 문구를 오류 코드로 결정합니다.")
    val message: String,
    @field:Schema(
        description = "X-Request-Id 응답 헤더 및 서버 로그와 동일한 UUID",
        format = "uuid",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    val requestId: String,
    @field:Schema(description = "오류 응답 시각 (UTC, RFC 3339)")
    val timestamp: Instant,
    val errors: List<FieldViolation> = emptyList(),
)
