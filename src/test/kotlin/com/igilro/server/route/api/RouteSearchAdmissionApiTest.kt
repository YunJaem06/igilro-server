package com.igilro.server.route.api

import com.igilro.server.route.application.*
import com.igilro.server.route.domain.*
import com.igilro.server.route.domain.prediction.*
import com.igilro.server.route.domain.shade.*
import com.igilro.server.shared.api.RequestIdFilter
import com.igilro.server.shared.api.error.GlobalExceptionHandler
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import java.time.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

class RouteSearchAdmissionApiTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-28T03:00:00Z"), ZoneOffset.UTC)
    private val a = Coordinate(127.0374, 37.5483)
    private val b = Coordinate(127.0376, 37.5484)
    private val graph = WalkingGraph("fixture-graph", "fixture", Bounds(127.037,37.548,127.038,37.549),
        mapOf(1L to a, 2L to b), listOf(WalkingEdge(1,1,2,20.0)))
    private val info = PredictionSceneInfo("fixture-model", graph, "synthetic fixture", emptyList(), 1, 1, .01)
    private val sourceCalls = AtomicInteger()
    private val source = object : ModelPredictionSource {
        override fun current(): PredictionSceneInfo { sourceCalls.incrementAndGet(); return info }
        override fun measure(info: PredictionSceneInfo, paths: List<List<Coordinate>>, center: Coordinate, sun: SolarPosition) =
            paths.map { ShadePathMeasurement(ShadeEvaluation(ShadeStatus.AVAILABLE, .5, 1.0, info.dataVersion, info.attribution, sun.altitudeDegrees),20.0) }
        override fun trace(info: PredictionSceneInfo, path: List<Coordinate>, center: Coordinate, sun: SolarPosition) =
            ModeledPathPrediction(measure(info,listOf(path),center,sun).single(),
                listOf(ModeledShadeSection(ModeledShadeState.SHADE,path,0.0,20.0)))
    }

    private fun mvc(admission: RouteSearchAdmission, repository: WalkingGraphRepository = WalkingGraphRepository { listOf(graph) }): MockMvc {
        val walking = SearchWalkingRouteUseCase(repository,DijkstraRoutePlanner(),clock,
            RouteShadeEvaluator { _,_,_ -> ShadeEvaluation(ShadeStatus.DATA_UNAVAILABLE) },admission)
        val model = SearchModelPredictionUseCase(source,clock,admission)
        return MockMvcBuilders.standaloneSetup(RouteController(walking),ModelPredictionController(model))
            .setControllerAdvice(RouteExceptionHandler(clock),GlobalExceptionHandler(clock))
            .addFilters<StandaloneMockMvcBuilder>(RequestIdFilter()).build()
    }

    private fun request(model: Boolean = false, mode: String = "fastest") =
        post(if (model) "/api/v1/routes/predictions/search" else "/api/v1/routes/search")
            .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON).content("""
                {"origin":{"longitude":127.0374,"latitude":37.5483},"destination":{"longitude":127.0376,"latitude":37.5484},"mode":"$mode"}
            """.trimIndent())

    @Test
    fun `ordinary and model POSTs share rate budget and preserve the common retryable error contract`() {
        val now = AtomicLong()
        val mvc = mvc(RouteSearchAdmission(1,1,1,now::get))
        mvc.perform(request()).andExpect(status().isOk)
        val id = UUID.randomUUID().toString()
        mvc.perform(request(model = true).header("X-Request-Id", id))
            .andExpect(status().isTooManyRequests)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(header().string("X-Request-Id",id))
            .andExpect(header().string("Retry-After","1"))
            .andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"))
            .andExpect(jsonPath("$.requestId").value(id))
            .andExpect(jsonPath("$.timestamp").value(clock.instant().toString()))
            .andExpect(jsonPath("$.message").isString)
            .andExpect(jsonPath("$.errors").isEmpty)
        assertEquals(0, sourceCalls.get(), "Rejected searches must not read the model source")
        now.set(1_000_000_000)
        mvc.perform(request(model = true)).andExpect(status().isOk)
        mvc.perform(request()).andExpect(status().isTooManyRequests)
    }

    @Test
    fun `busy ordinary search rejects model work immediately but coverage remains accessible`() {
        val admission = RouteSearchAdmission(1,2,1) { 0L }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val mvc = mvc(admission, WalkingGraphRepository {
            entered.countDown()
            assertTrue(release.await(5,TimeUnit.SECONDS))
            listOf(graph)
        })
        Executors.newSingleThreadExecutor().use { executor ->
            val running = executor.submit { mvc.perform(request()).andExpect(status().isOk) }
            try {
                assertTrue(entered.await(5,TimeUnit.SECONDS))
                val id = UUID.randomUUID().toString()
                mvc.perform(request(model = true).header("X-Request-Id",id))
                    .andExpect(status().isServiceUnavailable)
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(header().string("Retry-After","1"))
                    .andExpect(header().string("Cache-Control","no-store"))
                    .andExpect(header().string("X-Request-Id",id))
                    .andExpect(jsonPath("$.code").value("ROUTE_SEARCH_BUSY"))
                    .andExpect(jsonPath("$.requestId").value(id))
                    .andExpect(jsonPath("$.errors").isEmpty)
                assertEquals(0, sourceCalls.get())
                mvc.perform(get("/api/v1/routes/predictions/coverage")).andExpect(status().isOk)
            } finally { release.countDown() }
            running.get(5,TimeUnit.SECONDS)
        }
        mvc.perform(request(model = true)).andExpect(status().isOk)
    }

    @Test
    fun `malformed and unsupported requests fail validation without spending admission budget`() {
        val mvc = mvc(RouteSearchAdmission(1,1,1) { 0L })
        mvc.perform(post("/api/v1/routes/search").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        mvc.perform(request(mode = "sunlight")).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_ROUTE_QUERY"))
        mvc.perform(request(model = true, mode = "running")).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_ROUTE_QUERY"))
        mvc.perform(request()).andExpect(status().isOk)
    }

    @Test
    fun `business failure releases the slot and never refunds accepted work`() {
        val now = AtomicLong()
        val available = AtomicInteger()
        val mvc = mvc(RouteSearchAdmission(1,1,1,now::get),WalkingGraphRepository {
            if (available.get() == 0) emptyList() else listOf(graph)
        })
        mvc.perform(request()).andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("ROUTING_DATA_UNAVAILABLE"))
        available.set(1)
        mvc.perform(request(model = true)).andExpect(status().isTooManyRequests)
        now.set(1_000_000_000)
        mvc.perform(request(model = true)).andExpect(status().isOk)
    }
}
