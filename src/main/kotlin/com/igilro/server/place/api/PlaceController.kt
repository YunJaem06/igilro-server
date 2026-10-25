package com.igilro.server.place.api

import com.igilro.server.place.application.SearchPlacesUseCase
import com.igilro.server.shared.api.error.ApiErrorResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/places")
@Tag(name = "Places", description = "한국 장소명·주소 검색. 경로 지원 지역과 별개입니다.")
class PlaceController(private val search: SearchPlacesUseCase) {
    @GetMapping("/search", produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "장소명·주소 검색", operationId = "searchPlaces", description = "공백 정규화 후 1~100 Unicode 문자, 최대 후보 10개. 결과 없음은 200과 places 빈 배열입니다.")
    @ApiResponse(responseCode = "200", description = "검색 후보와 표시할 출처. 빈 결과는 places: []")
    @ApiResponse(responseCode = "400", description = "INVALID_REQUEST", content = [Content(schema = Schema(implementation = ApiErrorResponse::class))])
    @ApiResponse(responseCode = "429", description = "TOO_MANY_REQUESTS", content = [Content(schema = Schema(implementation = ApiErrorResponse::class))])
    @ApiResponse(responseCode = "502", description = "PLACE_SEARCH_UNAVAILABLE 또는 PLACE_SEARCH_BAD_RESPONSE", content = [Content(schema = Schema(implementation = ApiErrorResponse::class))])
    @ApiResponse(responseCode = "503", description = "PLACE_SEARCH_NOT_CONFIGURED 또는 PLACE_SEARCH_PROVIDER_AUTH_FAILED", content = [Content(schema = Schema(implementation = ApiErrorResponse::class))])
    @ApiResponse(responseCode = "504", description = "PLACE_SEARCH_TIMEOUT", content = [Content(schema = Schema(implementation = ApiErrorResponse::class))])
    fun search(@RequestParam("query") @Parameter(description = "장소명 또는 주소. 추가 필수 파라미터 없음", required = true) query: String): ResponseEntity<PlaceSearchResponse> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(PlaceSearchMapper.toResponse(search.execute(query)))
}
