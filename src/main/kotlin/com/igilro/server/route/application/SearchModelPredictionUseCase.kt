package com.igilro.server.route.application

import com.igilro.server.route.domain.*
import com.igilro.server.route.domain.prediction.*
import com.igilro.server.route.domain.shade.*
import java.time.Clock

interface ModelPredictionSource {
    fun current(): PredictionSceneInfo?
    fun measure(info: PredictionSceneInfo, paths: List<List<Coordinate>>, center: Coordinate, sun: SolarPosition): List<ShadePathMeasurement>
    fun trace(info: PredictionSceneInfo,path: List<Coordinate>,center: Coordinate,sun: SolarPosition): ModeledPathPrediction
}

data class ModelPredictionResult(val result: RouteSearchResult,val info: PredictionSceneInfo,val baselineModeledShadeRatio: Double,
    val sections: List<ModeledShadeSection>)

class SearchModelPredictionUseCase(private val source: ModelPredictionSource,private val clock: Clock) {
    fun execute(query: RouteQuery): ModelPredictionResult {
        if(query.travelMode!="walking" || query.mode !in setOf("fastest","shade") || query.origin==query.destination)
            throw RouteFailure(RouteFailureReason.INVALID_QUERY)
        val info=source.current() ?: throw RouteFailure(RouteFailureReason.PREDICTION_DATA_UNAVAILABLE)
        val graph=info.graph
        if(!graph.bounds.contains(query.origin) || !graph.bounds.contains(query.destination)) throw RouteFailure(RouteFailureReason.OUTSIDE_SUPPORTED_AREA)
        val at=query.departureAt ?: clock.instant()
        val baseline=DijkstraRoutePlanner().find(graph,query.origin,query.destination,MAXIMUM_SNAP_METERS)
        val center=Coordinate((baseline.coordinates.minOf { it.longitude }+baseline.coordinates.maxOf { it.longitude })/2,
            (baseline.coordinates.minOf { it.latitude }+baseline.coordinates.maxOf { it.latitude })/2)
        val sun=SolarPositionCalculator().calculate(center,at)
        if(sun==null || sun.altitudeDegrees<5) throw RouteFailure(RouteFailureReason.SHADE_TIME_UNSUPPORTED)
        val optimizer=ShadeRouteOptimizer()
        val network=if(query.mode=="shade") optimizer.prepare(graph,baseline) else null
        val measurements=source.measure(info,listOf(baseline.coordinates)+(network?.paths ?: emptyList()),center,sun)
        val result=if(network==null) RouteSearchResult(baseline,graph.dataVersion,at,measurements.first().evaluation)
        else optimizer.find(network,measurements).let { RouteSearchResult(it.route,graph.dataVersion,at,it.shade,"shade",baseline.durationSeconds) }
        val trace=source.trace(info,result.route.coordinates,center,sun)
        return ModelPredictionResult(result.copy(shade=trace.measurement.evaluation,guidance=RouteGuidanceGenerator().generate(graph,result.route)),info,
            if(network==null) trace.measurement.evaluation.ratio!! else measurements.first().evaluation.ratio!!,trace.sections)
    }
    fun coverage()=source.current() ?: throw RouteFailure(RouteFailureReason.PREDICTION_DATA_UNAVAILABLE)
    companion object { const val MAXIMUM_SNAP_METERS=50.0 }
}
