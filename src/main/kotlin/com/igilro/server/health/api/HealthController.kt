package com.igilro.server.health.api

import com.igilro.server.health.application.CheckHealthUseCase
import com.igilro.server.health.domain.HealthStatus
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

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
        content = [Content(schema = Schema(implementation = HealthResponse::class))],
    )
    @ApiResponse(responseCode = "500", ref = "#/components/responses/InternalServerError")
    fun check(): ResponseEntity<HealthResponse> {
        val health = checkHealth.execute()
        val status = if (health.status == HealthStatus.UP) HttpStatus.OK else HttpStatus.SERVICE_UNAVAILABLE
        return ResponseEntity.status(status).body(HealthResponseMapper.toResponse(health))
    }
}
