package com.igilro.server.route.application

import com.igilro.server.route.domain.*
import com.igilro.server.route.domain.shade.*
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*

class SearchWalkingRouteUseCaseTest {
    private val now = Instant.parse("2026-10-10T03:00:00Z")
    private val a = Coordinate(127.03, 37.54)
    private val b = Coordinate(127.032, 37.54)
    private val graph = WalkingGraph("dataset", "test", Bounds(127.02, 37.53, 127.05, 37.56), mapOf(1L to a, 2L to b), listOf(WalkingEdge(1, 1, 2, 180.0)))
    private fun useCase(graphs: List<WalkingGraph>, evaluator: RouteShadeEvaluator = RouteShadeEvaluator { _, _, _ -> ShadeEvaluation(ShadeStatus.DATA_UNAVAILABLE) }) =
        SearchWalkingRouteUseCase(WalkingGraphRepository { graphs }, DijkstraRoutePlanner(), Clock.fixed(now, ZoneOffset.UTC), evaluator)

    @Test
    fun `shade evaluates the computed network geometry at the applied departure instant`() {
        val departure = Instant.parse("2026-10-10T05:00:00Z")
        val result = useCase(listOf(graph), RouteShadeEvaluator { area, coordinates, time ->
            assertEquals("test", area)
            assertEquals(listOf(a, b), coordinates)
            assertEquals(departure, time)
            ShadeEvaluation(ShadeStatus.AVAILABLE, 0.4, 1.0, "buildings-test")
        }).execute(RouteQuery(a, b, departureAt = departure))
        assertEquals(0.4, result.shade.ratio)
        assertEquals(180.0, result.route.distanceMeters)
    }

    @Test
    fun `missing dataset is distinguished from route not found`() {
        assertEquals(RouteFailureReason.DATA_UNAVAILABLE, assertFailsWith<RouteFailure> { useCase(emptyList()).execute(RouteQuery(a, b)) }.reason)
    }

    @Test
    fun `outside support area returns its own failure`() {
        assertEquals(RouteFailureReason.OUTSIDE_SUPPORTED_AREA, assertFailsWith<RouteFailure> { useCase(listOf(graph)).execute(RouteQuery(a, Coordinate(127.051, 37.54))) }.reason)
    }

    @Test
    fun `a more specific pilot area takes precedence over the enclosing regional graph`() {
        val pilot=WalkingGraph("pilot-version","test-pilot",Bounds(127.029,37.539,127.033,37.541),
            graph.nodes,graph.edges)
        for (graphs in listOf(listOf(graph,pilot),listOf(pilot,graph))) {
            val result=useCase(graphs).execute(RouteQuery(a,b))
            assertEquals("pilot-version",result.dataVersion)
        }
    }

    @Test
    fun `a query leaving a pilot still uses the enclosing regional graph`() {
        val pilot=WalkingGraph("pilot-version","test-pilot",Bounds(127.029,37.539,127.031,37.541),
            mapOf(1L to a,3L to Coordinate(127.0305,37.54)),listOf(WalkingEdge(3,1,3,45.0)))
        assertEquals(graph.dataVersion,useCase(listOf(pilot,graph)).execute(RouteQuery(a,b)).dataVersion)
    }

    @Test
    fun `a disconnected pilot does not silently borrow routes outside its reviewed graph`() {
        val pilot=WalkingGraph("pilot-version","test-pilot",Bounds(127.029,37.539,127.033,37.541),
            mapOf(1L to a,2L to Coordinate(127.0301,37.54),3L to Coordinate(127.0319,37.54),4L to b),
            listOf(WalkingEdge(1,1,2,9.0),WalkingEdge(2,3,4,9.0)))
        assertEquals(RouteFailureReason.NOT_FOUND,assertFailsWith<RouteFailure> {
            useCase(listOf(graph,pilot)).execute(RouteQuery(a,b))
        }.reason)
    }

    @Test
    fun `default evaluation time comes from the injected clock`() {
        assertEquals(now, useCase(listOf(graph)).execute(RouteQuery(a, b)).evaluatedAt)
    }

    @Test
    fun `supplied departure instant is retained`() {
        val departure = Instant.parse("2026-10-10T05:00:00Z")
        assertEquals(departure, useCase(listOf(graph)).execute(RouteQuery(a, b, departureAt = departure)).evaluatedAt)
    }

    @Test
    fun `unsupported modes and identical positions are invalid queries`() {
        listOf(RouteQuery(a, b, mode = "sunlight"), RouteQuery(a, b, travelMode = "driving"), RouteQuery(a, a)).forEach { query ->
            assertEquals(RouteFailureReason.INVALID_QUERY, assertFailsWith<RouteFailure> { useCase(listOf(graph)).execute(query) }.reason)
        }
    }

    @Test
    fun `shade is explicit about missing data and unsupported daylight conditions`() {
        for (status in ShadeStatus.entries.filter { it != ShadeStatus.AVAILABLE }) {
            val expected = if (status in setOf(ShadeStatus.NIGHT,ShadeStatus.LOW_SUN,ShadeStatus.UNSUPPORTED_TIME))
                RouteFailureReason.SHADE_TIME_UNSUPPORTED else RouteFailureReason.SHADE_DATA_UNAVAILABLE
            assertEquals(expected,assertFailsWith<RouteFailure> {
                useCase(listOf(graph),RouteShadeEvaluator { _,_,_ -> ShadeEvaluation(status) }).execute(RouteQuery(a,b,mode="shade"))
            }.reason)
        }
    }

    @Test
    fun `shade evaluates all candidates with a shared reference time and retains actual baseline`() {
        val departure=Instant.parse("2026-06-21T03:00:00Z")
        var called=false
        val search=SearchWalkingRouteUseCase(WalkingGraphRepository { listOf(graph) },DijkstraRoutePlanner(),Clock.fixed(now,ZoneOffset.UTC),
            RouteShadeEvaluator { _,_,_ -> ShadeEvaluation(ShadeStatus.AVAILABLE,0.5,1.0,"previous-snapshot") },
            RouteShadeBatchEvaluator { area,paths,reference,time ->
                called=true
                assertEquals("test",area)
                assertEquals(departure,time)
                assertEquals(127.031,reference.longitude,1e-10)
                assertEquals(listOf(a,b),paths.first())
                paths.map { ShadePathMeasurement(ShadeEvaluation(ShadeStatus.AVAILABLE,0.25,1.0,"batch-snapshot"),180.0) }
            }, setOf(graph.dataVersion))
        val result=search.execute(RouteQuery(a,b,mode="shade",departureAt=departure))
        assertTrue(called)
        assertEquals("shade",result.mode)
        assertEquals("batch-snapshot",result.shade.dataVersion)
        assertEquals(180/1.4,result.baselineDurationSeconds,1e-10)
    }

    @Test
    fun `complete buildings do not authorize an unverified ground network`() {
        var compared=false
        val search=SearchWalkingRouteUseCase(WalkingGraphRepository { listOf(graph) },DijkstraRoutePlanner(),Clock.fixed(now,ZoneOffset.UTC),
            RouteShadeEvaluator { _,_,_ -> ShadeEvaluation(ShadeStatus.AVAILABLE,0.5,1.0,"buildings") },
            RouteShadeBatchEvaluator { _,_,_,_ -> compared=true; emptyList() },setOf("a-different-graph-version"))
        assertEquals(RouteFailureReason.SHADE_DATA_UNAVAILABLE,assertFailsWith<RouteFailure> {
            search.execute(RouteQuery(a,b,mode="shade"))
        }.reason)
        assertFalse(compared)
    }

    @Test
    fun `too long a query inside a pilot area is rejected`() {
        val far = Coordinate(127.1, 37.54)
        val wide = WalkingGraph("wide", "test", Bounds(127.02, 37.53, 127.11, 37.56), mapOf(1L to a, 2L to far), listOf(WalkingEdge(1, 1, 2, 6200.0)))
        assertEquals(RouteFailureReason.INVALID_QUERY, assertFailsWith<RouteFailure> { useCase(listOf(wide)).execute(RouteQuery(a, far)) }.reason)
    }
}
