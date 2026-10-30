package com.igilro.server.route.api

import com.igilro.server.route.domain.RouteGuidance
import io.swagger.v3.oas.annotations.media.Schema

data class GuidanceCrossingResponse(
    @field:Schema(description="원본에 명시된 횡단 신호 설치 여부. 미확인은 null, 현재 신호 색상은 제공하지 않음")
    val trafficSignals: Boolean?,
)

data class GuidanceStepResponse(
    val index: Int,
    @field:Schema(allowableValues=["DEPART","CONTINUE","KEEP_LEFT","KEEP_RIGHT","TURN_LEFT","TURN_RIGHT","U_TURN","CROSSING","STAIRS","ARRIVE"])
    val type: String,
    val coordinate: CoordinateResponse,
    @field:Schema(description="routes[].geometry.coordinates 안의 안내 지점 인덱스",minimum="0") val geometryIndex: Int,
    val distanceFromStartMeters: Double,
    val distanceToNextStepMeters: Double,
    val durationToNextStepSeconds: Double,
    val instruction: String,
    val streetName: String?,
    val bearingBeforeDegrees: Double?,
    val bearingAfterDegrees: Double?,
    val crossing: GuidanceCrossingResponse?,
)

data class RouteGuidanceResponse(
    @field:Schema(allowableValues=["AVAILABLE","UNAVAILABLE"]) val status: String,
    @field:Schema(allowableValues=["OSM_TAGS","TOPOLOGY_ONLY"]) val metadataStatus: String,
    val modelVersion: String,
    val language: String,
    val steps: List<GuidanceStepResponse>,
)

object RouteGuidanceMapper {
    fun toResponse(guidance: RouteGuidance?)=guidance?.let { value ->
        RouteGuidanceResponse(value.status,value.metadataStatus,value.modelVersion,value.language,value.steps.map { step ->
            GuidanceStepResponse(step.index,step.type.name,CoordinateResponse(step.coordinate.longitude,step.coordinate.latitude),
                step.geometryIndex,step.distanceFromStartMeters,step.distanceToNextStepMeters,step.durationToNextStepSeconds,
                step.instruction,step.streetName,step.bearingBeforeDegrees,step.bearingAfterDegrees,
                step.crossing?.let { GuidanceCrossingResponse(it.trafficSignals) })
        })
    }
}
