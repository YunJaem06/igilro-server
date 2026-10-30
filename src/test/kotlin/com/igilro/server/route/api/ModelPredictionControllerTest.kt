package com.igilro.server.route.api

import com.igilro.server.route.application.*
import com.igilro.server.route.domain.*
import com.igilro.server.route.domain.prediction.*
import com.igilro.server.route.domain.shade.*
import com.igilro.server.shared.api.RequestIdFilter
import com.igilro.server.shared.api.error.GlobalExceptionHandler
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import java.time.*
import kotlin.test.*

class ModelPredictionControllerTest {
    private val clock=Clock.fixed(Instant.parse("2026-10-28T03:00:00Z"),ZoneOffset.UTC)
    private val points=mapOf(1L to Coordinate(127.0374,37.5483),2L to Coordinate(127.0376,37.5484))
    private val graph=WalkingGraph("fixture-graph","fixture",Bounds(127.037,37.548,127.038,37.549),points,listOf(WalkingEdge(1,1,2,20.0)))
    private val info=PredictionSceneInfo("model-fixture",graph,"synthetic fixture",listOf("MODEL_HEIGHTS_ARE_NOT_ACTUAL_HEIGHTS","PARTIAL_PROVIDER_SCENE"),1,1,.01)
    private fun source(enabled: Boolean=true)=object: ModelPredictionSource {
        override fun current()=if(enabled) info else null
        override fun measure(info: PredictionSceneInfo,paths: List<List<Coordinate>>,center: Coordinate,sun: SolarPosition)=paths.map {
            ShadePathMeasurement(ShadeEvaluation(ShadeStatus.AVAILABLE,.5,1.0,info.dataVersion,info.attribution,sun.altitudeDegrees),21.0)
        }
        override fun trace(info: PredictionSceneInfo,path: List<Coordinate>,center: Coordinate,sun: SolarPosition): ModeledPathPrediction {
            val a=path.first();val b=path.last();val middle=Coordinate((a.longitude+b.longitude)/2,(a.latitude+b.latitude)/2)
            return ModeledPathPrediction(measure(info,listOf(path),center,sun).single(),listOf(
                ModeledShadeSection(ModeledShadeState.SHADE,listOf(a,middle),0.0,10.5),
                ModeledShadeSection(ModeledShadeState.SUNLIGHT,listOf(middle,b),10.5,21.0)))
        }
    }
    private fun mvc(enabled: Boolean=true)=MockMvcBuilders.standaloneSetup(ModelPredictionController(SearchModelPredictionUseCase(source(enabled),clock)))
        .setControllerAdvice(RouteExceptionHandler(clock),GlobalExceptionHandler(clock)).addFilters<StandaloneMockMvcBuilder>(RequestIdFilter()).build()
    private fun request(extra: String="")=post("/api/v1/routes/predictions/search").contentType(MediaType.APPLICATION_JSON)
        .content("""{"origin":{"longitude":127.0374,"latitude":37.5483},"destination":{"longitude":127.0376,"latitude":37.5484}$extra}""")
    @Test fun `model prediction never exposes actual shade metrics or AVAILABLE coverage`() {
        mvc().perform(request()).andExpect(status().isOk)
            .andExpect(jsonPath("$.quality").value("EXPERIMENTAL_PARTIAL_MODEL"))
            .andExpect(jsonPath("$.routes[0].prediction.status").value("MODEL_BASED_PREDICTION"))
            .andExpect(jsonPath("$.routes[0].prediction.modeledShadeRatio").value(.5))
            .andExpect(jsonPath("$.routes[0].shadeEvaluation").doesNotExist())
            .andExpect(jsonPath("$.routes[0].metrics").doesNotExist())
            .andExpect(jsonPath("$.predictionDataVersion").value("model-fixture"))
            .andExpect(jsonPath("$.dataVersion").value("fixture-graph"))
            .andExpect(jsonPath("$.routes[0].shadeSegments[0].state").value("SHADE"))
            .andExpect(jsonPath("$.routes[0].shadeSegments[0].geometry.type").value("LineString"))
            .andExpect(jsonPath("$.routes[0].shadeSegments[1].state").value("SUNLIGHT"))
            .andExpect(jsonPath("$.routes[0].shadeSegments[1].fromDistanceMeters").value(10.5))
            .andExpect(jsonPath("$.routes[0].guidance.status").value("AVAILABLE"))
            .andExpect(jsonPath("$.routes[0].guidance.steps[0].type").value("DEPART"))
            .andExpect(jsonPath("$.routes[0].guidance.steps[1].type").value("ARRIVE"))
            .andExpect(jsonPath("$.routes[0].guidance.steps[1].geometryIndex").value(1))
    }
    @Test fun `missing model scene uses common JSON error with request ID`() {
        mvc(false).perform(request()).andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("PREDICTION_DATA_UNAVAILABLE"))
            .andExpect(jsonPath("$.requestId").isString).andExpect(header().exists("X-Request-Id"))
    }
    @Test fun `invalid requests and unsupported solar times preserve distinct failures`() {
        mvc().perform(request(",\"mode\":\"sunlight\"")).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_ROUTE_QUERY"))
        mvc().perform(request(",\"departureAt\":\"2026-10-28T00:00:00+09:00\"")).andExpect(status().`is`(422))
            .andExpect(jsonPath("$.code").value("SHADE_TIME_UNSUPPORTED"))
    }
    @Test fun `shade prediction keeps a real fastest time baseline`() {
        mvc().perform(request(",\"mode\":\"shade\"")).andExpect(status().isOk)
            .andExpect(jsonPath("$.routes[0].mode").value("shade"))
            .andExpect(jsonPath("$.routes[0].additionalDurationSeconds").value(0))
            .andExpect(jsonPath("$.baselineDurationSeconds").value(15))
            .andExpect(jsonPath("$.routes[0].guidance.status").value("AVAILABLE"))
    }
    @Test fun `coverage supplies the loaded bounds and actual walking edges without hardcoded client region`() {
        mvc().perform(get("/api/v1/routes/predictions/coverage")).andExpect(status().isOk)
            .andExpect(jsonPath("$.areaId").value("fixture"))
            .andExpect(jsonPath("$.bounds[0]").value(127.037))
            .andExpect(jsonPath("$.maximumSnappingDistanceMeters").value(50.0))
            .andExpect(jsonPath("$.walkingNetwork.type").value("MultiLineString"))
            .andExpect(jsonPath("$.walkingNetwork.coordinates[0][0][0]").value(127.0374))
        mvc(false).perform(get("/api/v1/routes/predictions/coverage")).andExpect(status().isServiceUnavailable)
    }
}
