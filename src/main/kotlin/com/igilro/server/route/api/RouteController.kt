package com.igilro.server.route.api

import com.igilro.server.route.application.SearchWalkingRouteUseCase
import com.igilro.server.shared.api.error.ApiErrorResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/routes")
@Tag(name = "Routes", description = "실제 보행 그래프 기반 fastest 및 검증된 건물 자료의 shade 경로")
class RouteController(private val search: SearchWalkingRouteUseCase) {
    @PostMapping("/search", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "도보 경로 검색", operationId = "searchWalkingRoute", description = "지원 지역 안에서 5km 이하의 두 지점을 최대 50m 범위의 보행 구간에 연결합니다. shade는 같은 스냅 지점에서 최단 시간 대비 30% 이내로 햇빛 노출 시간을 최소화하며 자료 부족 시 503을 반환합니다.")
    @ApiResponse(responseCode = "200", description = "요청한 mode의 도보 경로 1개")
    @ApiResponse(responseCode = "400", description = "INVALID_REQUEST 또는 INVALID_ROUTE_QUERY", content = [Content(schema = Schema(implementation = ApiErrorResponse::class))])
    @ApiResponse(responseCode = "404", description = "ROUTE_NOT_FOUND", content = [Content(schema = Schema(implementation = ApiErrorResponse::class))])
    @ApiResponse(responseCode = "422", description = "OUTSIDE_SUPPORTED_AREA 또는 SHADE_TIME_UNSUPPORTED", content = [Content(schema = Schema(implementation = ApiErrorResponse::class))])
    @ApiResponse(responseCode = "503", description = "ROUTING_DATA_UNAVAILABLE / SHADE_DATA_UNAVAILABLE / ROUTE_SEARCH_LIMIT_EXCEEDED", content = [Content(schema = Schema(implementation = ApiErrorResponse::class))])
    @ApiResponse(responseCode = "500", description = "INTERNAL_SERVER_ERROR", content = [Content(schema = Schema(implementation = ApiErrorResponse::class))])
    fun search(@Valid @RequestBody request: RouteSearchRequest) = RouteMapper.toResponse(search.execute(RouteMapper.toQuery(request)))
}
