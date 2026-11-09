package com.igilro.server.route.domain

import org.junit.jupiter.api.Test
import kotlin.test.*

class RunningRouteOptimizerTest {
    private val a = Coordinate(127.03, 37.54)
    private val b = Coordinate(127.032, 37.54)
    private val c = Coordinate(127.031, 37.54)
    private val bounds = Bounds(127.02, 37.53, 127.05, 37.56)
    private val optimizer = RunningRouteOptimizer()
    private val crossing = EdgeGuidanceMetadata(100, crossing = CrossingMetadata())

    @Test
    fun `avoids stairs and mapped crossings only within the time budget`() {
        val nodes = mapOf(
            1L to a, 2L to Coordinate(127.0302, 37.54), 3L to c,
            4L to Coordinate(127.0315, 37.54), 5L to b, 6L to Coordinate(127.031, 37.5404),
        )
        for (detour in listOf(182.0, 182.01)) {
            val graph = WalkingGraph("fixture", "test", bounds, nodes, listOf(
                WalkingEdge(1, 1, 2, 9.0, EdgeKind.STEPS),
                WalkingEdge(2, 2, 3, 28.0),
                WalkingEdge(3, 3, 4, 28.0),
                WalkingEdge(4, 4, 5, 70.0),
                WalkingEdge(5, 1, 6, detour / 2),
                WalkingEdge(6, 6, 5, detour / 2),
            ), edgeGuidance = mapOf(2L to crossing, 3L to crossing))
            val fastest = DijkstraRoutePlanner().find(graph, a, b, 50.0)
            val route = optimizer.find(graph, fastest)
            assertEquals(100.0, fastest.durationSeconds, 1e-8)
            assertEquals(RunningRouteMetrics(9.0, 1), metrics(graph, fastest))
            assertEquals(fastest.origin, route.origin)
            assertEquals(fastest.destination, route.destination)
            if (detour == 182.0) {
                assertEquals(130.0, route.durationSeconds, 1e-8)
                assertEquals(RunningRouteMetrics(0.0, 0), metrics(graph, route))
            } else {
                assertEquals(fastest.traversals, route.traversals)
            }
        }
    }

    @Test
    fun `arrival on the same crossing way is kept as a separate search state`() {
        val graph = WalkingGraph("fixture", "test", bounds, mapOf(1L to a, 2L to c, 3L to b), listOf(
            WalkingEdge(1, 1, 2, 5.0, EdgeKind.STEPS, backwardAllowed = false),
            WalkingEdge(2, 1, 2, 14.0, backwardAllowed = false),
            WalkingEdge(3, 2, 3, 56.0, backwardAllowed = false),
        ), edgeGuidance = mapOf(2L to crossing, 3L to crossing))
        val fastest = DijkstraRoutePlanner().find(graph, a, b, 50.0)
        val route = optimizer.find(graph, fastest)
        assertEquals(listOf(1L, 3L), fastest.traversals.map { it.edgeId })
        assertEquals(listOf(2L, 3L), route.traversals.map { it.edgeId })
        assertEquals(RunningRouteMetrics(0.0, 1), metrics(graph, route))
    }

    @Test
    fun `crossing nodes count between edges and partial stairs count only the distance traversed`() {
        val graph = WalkingGraph("fixture", "test", bounds, mapOf(1L to a, 2L to c, 3L to b), listOf(
            WalkingEdge(1, 1, 2, 80.0, EdgeKind.STEPS),
            WalkingEdge(2, 2, 3, 80.0),
        ), nodeGuidance = mapOf(2L to NodeGuidanceMetadata(CrossingMetadata())))
        val origin = Coordinate(127.0305, 37.54)
        val fastest = DijkstraRoutePlanner().find(graph, origin, b, 50.0)
        val route = optimizer.find(graph, fastest)
        val metrics = metrics(graph, route)
        assertEquals(40.0, metrics.stairsMeters, 1e-6)
        assertEquals(1, metrics.mappedCrossingCount)
        assertEquals(fastest.traversals, route.traversals)
    }

    private fun metrics(graph: WalkingGraph, route: WalkingRoute) =
        optimizer.metrics(graph, route, RouteGuidanceGenerator().generate(graph, route))
}
