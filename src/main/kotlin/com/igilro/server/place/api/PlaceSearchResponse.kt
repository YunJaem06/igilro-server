package com.igilro.server.place.api

import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema

data class PlaceCoordinateResponse(
    @field:Schema(description = "WGS84 경도", minimum = "-180", maximum = "180", requiredMode = Schema.RequiredMode.REQUIRED)
    val longitude: Double,
    @field:Schema(description = "WGS84 위도", minimum = "-90", maximum = "90", requiredMode = Schema.RequiredMode.REQUIRED)
    val latitude: Double,
)

data class PlaceResponse(
    @field:Schema(description = "공급자 접두사를 포함한 opaque ID. 재조회·파싱하지 않습니다.", requiredMode = Schema.RequiredMode.REQUIRED)
    val id: String,
    @field:Schema(minLength = 1, requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
    @field:Schema(description = "주소가 없으면 빈 문자열", requiredMode = Schema.RequiredMode.REQUIRED) val address: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val coordinate: PlaceCoordinateResponse,
)

data class PlaceAttributionResponse(val text: String, val url: String?)

data class PlaceSearchResponse(
    @field:ArraySchema(maxItems = 10, arraySchema = Schema(requiredMode = Schema.RequiredMode.REQUIRED)) val places: List<PlaceResponse>,
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    @field:Schema(description = "결과가 있을 때 제공하는 출처 표시. 검색 목록과 선택된 핀에 보존·표시합니다.")
    val attributions: List<PlaceAttributionResponse> = emptyList(),
)
