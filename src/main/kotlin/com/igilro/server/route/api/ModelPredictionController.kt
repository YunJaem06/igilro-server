package com.igilro.server.route.api

import com.igilro.server.route.application.SearchModelPredictionUseCase
import com.igilro.server.route.domain.prediction.ModelScene
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*
import java.time.Instant

data class ModeledShadeSectionResponse(val state: String,val geometry: LineStringResponse,
    val fromDistanceMeters: Double,val toDistanceMeters: Double,val distanceMeters: Double)
data class ModeledShadePrediction(val status: String="MODEL_BASED_PREDICTION",val modeledShadeRatio: Double,
    val baselineModeledShadeRatio: Double,val physicalShadeCoverage: Double?=null)
data class PredictedRouteResponse(val id: String,val mode: String,val geometry: LineStringResponse,
    val distanceMeters: Double,val durationSeconds: Long,val additionalDurationSeconds: Long,val prediction: ModeledShadePrediction,
    val shadeSegments: List<ModeledShadeSectionResponse>,val guidance: RouteGuidanceResponse? = null)
data class PredictionNetworkResponse(val type: String="MultiLineString",val coordinates: List<List<List<Double>>>)
data class PredictionAccessPointResponse(val id: String,val name: String,val coordinate: CoordinateResponse)
data class PredictionConnectivityResponse(val nodeCount: Int,val edgeCount: Int,val weakComponentCount: Int,
    val largestComponentNodeCount: Int,val componentPolicy: String,val sourceWeakComponentCount: Int,
    val excludedDisconnectedNodeCount: Int)
data class PredictionCoverageResponse(val areaId: String,val name: String,val bounds: List<Double>,val center: CoordinateResponse,
    val maximumSnappingDistanceMeters: Double,val dataVersion: String,val predictionDataVersion: String,
    val walkingNetwork: PredictionNetworkResponse,val accessPoints: List<PredictionAccessPointResponse>,val connectivity: PredictionConnectivityResponse,
    val attribution: String,val quality: String="EXPERIMENTAL_PARTIAL_MODEL")
data class ModelRoutePredictionResponse(val routes: List<PredictedRouteResponse>,val evaluatedAt: Instant,
    val dataVersion: String,val predictionDataVersion: String,val modelVersion: String,val routingModelVersion: String,
    val baselineDurationSeconds: Long,val snapping: SnappingResponse,
    val quality: String="EXPERIMENTAL_PARTIAL_MODEL",val attribution: String,val limitations: List<String>,
    val buildingModelCount: Int,val terrainTileCount: Int,val sampleStepMeters: Double,val projectionMaxErrorMeters: Double,
    val omittedBuildingModelCount: Int,val sceneBufferMetersApprox: Double,
    val timeModelVersion: String="walking-v1-flat-1.4-steps-0.9-haversine-distance")

@RestController
@RequestMapping("/api/v1/routes/predictions")
@Tag(name="Route predictions",description="공공 3D 모델 기반 예측. 직접 높이 기반 평가와 별도 계약")
class ModelPredictionController(private val search: SearchModelPredictionUseCase) {
    @PostMapping("/search",consumes=[MediaType.APPLICATION_JSON_VALUE],produces=[MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary="3D 모델 기반 도보 경로 예측",operationId="searchModelRoutePrediction",
        description="coverage가 반환하는 보행망에서 모델 높이·DEM·고정 태양을 사용합니다. shadeSegments는 경로 순서대로 이어지는 SHADE/SUNLIGHT LineString입니다. modeledShadeRatio는 실제 관측 커버리지가 아닌 모델 예측입니다. shade는 같은 스냅에서 최단 시간 대비 30% 이내로 모델 햇빛 노출 시간을 최소화합니다.")
    fun search(@Valid @RequestBody request: RouteSearchRequest): ModelRoutePredictionResponse {
        val prediction=search.execute(RouteMapper.toQuery(request));val response=RouteMapper.toResponse(prediction.result)
        return ModelRoutePredictionResponse(response.routes.map { r -> PredictedRouteResponse(r.id,r.mode,r.geometry,r.distanceMeters,
            r.durationSeconds,r.additionalDurationSeconds,ModeledShadePrediction(modeledShadeRatio=prediction.result.shade.ratio!!,
                baselineModeledShadeRatio=prediction.baselineModeledShadeRatio),prediction.sections.map { section ->
                    ModeledShadeSectionResponse(section.state.name,LineStringResponse(coordinates=section.coordinates.map { listOf(it.longitude,it.latitude) }),
                        section.fromDistanceMeters,section.toDistanceMeters,section.distanceMeters)
                },r.guidance) },response.evaluatedAt,response.dataVersion,
            prediction.info.dataVersion,ModelScene.MODEL_VERSION,"model-prediction-v2-snap50-"+response.routingModelVersion,
            response.baselineDurationSeconds,response.snapping,attribution=prediction.info.attribution,
            limitations=prediction.info.limitations,buildingModelCount=prediction.info.buildingCount,
            terrainTileCount=prediction.info.terrainTileCount,sampleStepMeters=ModelScene.SAMPLE_STEP_METERS,
            projectionMaxErrorMeters=prediction.info.projectionErrorMeters,omittedBuildingModelCount=prediction.info.omittedBuildingCount,
            sceneBufferMetersApprox=prediction.info.sceneBufferMetersApprox)
    }
    @GetMapping("/coverage",produces=[MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary="모델 경로 검색 범위와 실제 보행망",operationId="getModelPredictionCoverage",
        description="bounds는 카메라 맞춤용 범위입니다. 그 안의 모든 지점이 연결된다는 뜻은 아닙니다. walkingNetwork의 길에서 최대 50m까지 스냅하며 연결되지 않은 요청은 ROUTE_NOT_FOUND입니다.")
    fun coverage(): PredictionCoverageResponse {
        val info=search.coverage();val graph=info.graph;val bounds=graph.bounds
        return PredictionCoverageResponse(graph.areaId,info.areaName,listOf(bounds.west,bounds.south,bounds.east,bounds.north),
            CoordinateResponse((bounds.west+bounds.east)/2,(bounds.south+bounds.north)/2),SearchModelPredictionUseCase.MAXIMUM_SNAP_METERS,
            graph.dataVersion,info.dataVersion,PredictionNetworkResponse(coordinates=graph.edges.map { edge ->
                listOf(graph.nodes.getValue(edge.from),graph.nodes.getValue(edge.to)).map { listOf(it.longitude,it.latitude) }
            }),info.accessPoints.map { PredictionAccessPointResponse(it.id,it.name,CoordinateResponse(it.coordinate.longitude,it.coordinate.latitude)) },
            PredictionConnectivityResponse(graph.nodes.size,graph.edges.size,graph.connectedComponents.size,graph.connectedComponents.first().size,
                info.componentPolicy,info.sourceComponentCount,info.excludedDisconnectedNodeCount),info.attribution)
    }
}
