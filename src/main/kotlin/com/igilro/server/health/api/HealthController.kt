package com.igilro.server.health.api

import com.igilro.server.health.application.CheckHealthUseCase
import com.igilro.server.health.domain.HealthStatus
import com.igilro.server.shared.api.error.ApiErrorResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/v1/health")
@Tag(name = "Health", description = "서비스 상태 확인")
class HealthController(private val checkHealth: CheckHealthUseCase) {
    @GetMapping
    @Operation(summary = "PostgreSQL/PostGIS를 포함한 서비스 상태 확인", operationId = "checkHealth")
    @ApiResponse(responseCode = "200", description = "공간 DB 사용 가능")
    @ApiResponse(
        responseCode = "503",
        description = "공간 DB 사용 불가",
        content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiErrorResponse::class))],
    )
    @ApiResponse(
        responseCode = "500",
        description = "서버 오류",
        content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiErrorResponse::class))],
    )
    fun check(): HealthResponse {
        val health = checkHealth.execute()
        if (health.status == HealthStatus.DOWN) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)
        return HealthResponseMapper.toResponse(health)
    }
}
