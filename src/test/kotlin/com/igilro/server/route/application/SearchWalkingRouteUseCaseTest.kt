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
        listOf(RouteQuery(a, b, mode = "shade"), RouteQuery(a, b, travelMode = "driving"), RouteQuery(a, a)).forEach { query ->
            assertEquals(RouteFailureReason.INVALID_QUERY, assertFailsWith<RouteFailure> { useCase(listOf(graph)).execute(query) }.reason)
        }
    }

    @Test
    fun `too long a query inside a pilot area is rejected`() {
        val far = Coordinate(127.1, 37.54)
        val wide = WalkingGraph("wide", "test", Bounds(127.02, 37.53, 127.11, 37.56), mapOf(1L to a, 2L to far), listOf(WalkingEdge(1, 1, 2, 6200.0)))
        assertEquals(RouteFailureReason.INVALID_QUERY, assertFailsWith<RouteFailure> { useCase(listOf(wide)).execute(RouteQuery(a, far)) }.reason)
    }
}
